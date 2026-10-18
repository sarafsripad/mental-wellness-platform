package org.mentalwellness.api.processing.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.mentalwellness.api.audit.AuditEvent;
import org.mentalwellness.api.audit.AuditLog;
import org.mentalwellness.api.audit.AuditOutcome;
import org.mentalwellness.api.consent.ConsentGate;
import org.mentalwellness.api.consent.ConsentScope;
import org.mentalwellness.api.consent.ConsentSnapshot;
import org.mentalwellness.api.shared.security.Actor;
import org.mentalwellness.api.shared.web.ApiProblem;
import org.mentalwellness.api.shared.web.CorrelationId;
import org.mentalwellness.api.shared.web.DomainException;
import org.mentalwellness.api.shared.web.WorkerProblemCode;
import org.mentalwellness.api.voicesession.RecordingAudio;
import org.mentalwellness.api.voicesession.StoredRecording;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal worker API (processing-worker.internal.v1.yaml). The worker holds no database or storage
 * credentials; everything goes through leases checked here. Consent is re-checked at claim, at
 * audio fetch and at result intake, and server-known fields in feature records are overwritten.
 */
@Service
class WorkerService {

	static final Duration LEASE = Duration.ofSeconds(300);

	static final int MAX_ATTEMPTS = 3;

	private final JobRepository jobs;

	private final RecordingAudio recordings;

	private final ConsentGate consent;

	private final FeatureRecordSchema schema;

	private final AuditLog audit;

	private final ObjectMapper json;

	private final ObjectMapper canonical;

	private final Clock clock;

	WorkerService(JobRepository jobs, RecordingAudio recordings, ConsentGate consent, FeatureRecordSchema schema,
			AuditLog audit, JsonMapper json, Clock clock) {
		this.jobs = jobs;
		this.recordings = recordings;
		this.consent = consent;
		this.schema = schema;
		this.audit = audit;
		this.json = json;
		this.canonical = json.rebuild()
			.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
			.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
			.build();
		this.clock = clock;
	}

	// --- claim ---------------------------------------------------------------------

	/** Leases the oldest claimable job; jobs whose consent or audio is gone are cancelled and skipped. */
	@Transactional
	public Optional<ProcessingApi.JobLease> claim(Actor worker, String workerId, ProcessingApi.ClaimRequest request) {
		for (int i = 0; i < 20; i++) {
			Optional<JobRepository.JobRow> next = this.jobs.nextQueued(request.supportedProfiles());
			if (next.isEmpty()) {
				return Optional.empty();
			}
			JobRepository.JobRow job = next.get();
			Optional<StoredRecording> recording = this.recordings.findStored(job.recordingId());
			Optional<ConsentSnapshot> snapshot = recording.isEmpty() ? Optional.empty() : stillConsented(job);
			if (snapshot.isEmpty()) {
				this.jobs.fail(job.jobId(), "CANCELLED", "CONSENT_WITHDRAWN", false, null, null);
				continue;
			}
			String token = ProcessingService.randomHex(32);
			this.jobs.lease(job.jobId(), sha256(token), workerId, this.clock.instant().plus(LEASE));
			StoredRecording r = recording.get();
			record(worker, "PROCESSING_JOB_CLAIMED", AuditOutcome.SUCCESS, "processing_job", job.jobId(), job.correlationId());
			return Optional.of(new ProcessingApi.JobLease(job.jobId(), job.recordingId(), job.sessionId(),
					job.pipelineProfile(), token, (int) LEASE.toSeconds(), job.correlationId(), r.languageTag(),
					r.dataOrigin(), r.deviceClass(), r.recordedAt(), reference(snapshot.get()),
					new ProcessingApi.LeaseAudio(r.format(), r.samplingRateHz(), r.channels(), r.durationSeconds(), r.sha256())));
		}
		return Optional.empty();
	}

	// --- audio ---------------------------------------------------------------------

	record Audio(byte[] content, String sha256, String format) {
	}

	// Business-rule refusals are thrown before any write, or after recording a cancellation that
	// must persist (e.g. CONSENT_WITHDRAWN), so they must not roll the transaction back.
	@Transactional(noRollbackFor = DomainException.class)
	public Audio audio(Actor worker, String jobId, String leaseToken, String workerId) {
		JobRepository.JobRow job = leased(jobId, leaseToken, workerId);
		if (stillConsented(job).isEmpty() || this.recordings.findStored(job.recordingId()).isEmpty()) {
			this.jobs.fail(jobId, "CANCELLED", "CONSENT_WITHDRAWN", false, null, null);
			record(worker, "RECORDING_CONTENT_ACCESS_REJECTED", AuditOutcome.DENIED, "recording", job.recordingId(), job.correlationId());
			throw new DomainException(WorkerProblemCode.CONSENT_WITHDRAWN);
		}
		StoredRecording r = this.recordings.findStored(job.recordingId()).orElseThrow();
		byte[] content = this.recordings.read(job.recordingId());
		record(worker, "RECORDING_CONTENT_ACCESSED", AuditOutcome.SUCCESS, "recording", job.recordingId(), job.correlationId());
		return new Audio(content, r.sha256(), r.format());
	}

	// --- result --------------------------------------------------------------------

	record Accepted(ProcessingApi.ResultAccepted body, boolean created) {
	}

	// Business-rule refusals are thrown before any write, or after recording a cancellation that
	// must persist (e.g. CONSENT_WITHDRAWN), so they must not roll the transaction back.
	@Transactional(noRollbackFor = DomainException.class)
	public Accepted result(Actor worker, String jobId, String leaseToken, String workerId, JsonNode submitted) {
		String hash = sha256(this.canonical.writeValueAsString(submitted));
		JobRepository.JobRow locked = this.jobs.lock(jobId)
			.orElseThrow(() -> new DomainException(WorkerProblemCode.LEASE_INVALID));
		if ("SUCCEEDED".equals(locked.status())) {
			// Identical resubmission by the same lease holder is idempotent.
			if (tokenMatches(locked, leaseToken, workerId) && this.jobs.featureRecordHash(jobId).filter(hash::equals).isPresent()) {
				return new Accepted(new ProcessingApi.ResultAccepted(jobId, locked.featureRecordId(), ProcessingApi.JobStatus.SUCCEEDED), false);
			}
			throw new DomainException(WorkerProblemCode.JOB_NOT_RUNNING);
		}
		JobRepository.JobRow job = leased(jobId, leaseToken, workerId);
		if (!(submitted instanceof ObjectNode record)) {
			throw reject(worker, job, WorkerProblemCode.SCHEMA_INVALID, List.of("/"));
		}
		List<String> errors = this.schema.errors(record);
		if (!errors.isEmpty()) {
			throw reject(worker, job, WorkerProblemCode.SCHEMA_INVALID, errors);
		}
		StoredRecording r = this.recordings.findStored(job.recordingId()).orElse(null);
		Optional<ConsentSnapshot> snapshot = stillConsented(job);
		if (r == null || snapshot.isEmpty()) {
			this.jobs.fail(jobId, "CANCELLED", "CONSENT_WITHDRAWN", false, null, null);
			record(worker, "FEATURE_RECORD_REJECTED", AuditOutcome.REJECTED, "processing_job", jobId, job.correlationId());
			throw new DomainException(WorkerProblemCode.CONSENT_WITHDRAWN);
		}
		if (!text(record, "processing_job_id").equals(jobId) || !text(record, "recording_id").equals(job.recordingId())
				|| !text(record, "session_id").equals(job.sessionId())
				|| record.path("audio").path("sampling_rate_hz").asInt() != r.samplingRateHz()
				|| record.path("audio").path("channels").asInt() != r.channels()) {
			throw reject(worker, job, WorkerProblemCode.JOB_MISMATCH, List.of());
		}
		String recordId = text(record, "record_id");
		if (this.jobs.featureRecordExists(recordId)) {
			throw reject(worker, job, WorkerProblemCode.JOB_MISMATCH, List.of("/record_id"));
		}

		// Never trust worker-supplied context: overwrite it from server records (contract).
		ObjectNode stored = record.deepCopy();
		stored.put("language_tag", r.languageTag());
		ObjectNode consentRef = stored.putObject("consent_reference");
		consentRef.put("consent_event_id", snapshot.get().consentEventId());
		ArrayNode scopes = consentRef.putArray("scopes_verified");
		snapshot.get().scopesVerified().stream().map(Enum::name).sorted().forEach(scopes::add);
		consentRef.put("verified_at_utc", snapshot.get().verifiedAt().toString());
		ObjectNode provenance = (ObjectNode) stored.get("provenance");
		provenance.put("data_origin", r.dataOrigin());
		provenance.put("capture_device_class", r.deviceClass());
		provenance.put("recorded_at_utc", r.recordedAt().toString());
		provenance.put("app_version", r.appVersion());
		List<String> afterOverwrite = this.schema.errors(stored);
		if (!afterOverwrite.isEmpty()) {
			throw new IllegalStateException("Server-overwritten feature record failed schema validation");
		}

		this.jobs.insertFeatureRecord(recordId, jobId, job.recordingId(), job.sessionId(), job.consentEventId(),
				r.dataOrigin(), this.json.writeValueAsString(stored), hash);
		List<String> flags = stored.path("quality").path("flags").valueStream().map(JsonNode::asString).toList();
		this.jobs.succeed(jobId, recordId, stored.path("quality").path("status").asString(), flags);
		record(worker, "FEATURE_RECORD_STORED", AuditOutcome.SUCCESS, "feature_record", recordId, job.correlationId());
		return new Accepted(new ProcessingApi.ResultAccepted(jobId, recordId, ProcessingApi.JobStatus.SUCCEEDED), true);
	}

	// --- failure -------------------------------------------------------------------

	// Business-rule refusals are thrown before any write, or after recording a cancellation that
	// must persist (e.g. CONSENT_WITHDRAWN), so they must not roll the transaction back.
	@Transactional(noRollbackFor = DomainException.class)
	public ProcessingApi.FailureAccepted failure(Actor worker, String jobId, String leaseToken, String workerId,
			ProcessingApi.FailureReport report) {
		JobRepository.JobRow job = leased(jobId, leaseToken, workerId);
		boolean retry = Boolean.TRUE.equals(report.retryable()) && job.attempt() < MAX_ATTEMPTS;
		String qualityStatus = report.qualityOutcome() == null ? null : report.qualityOutcome().status().name();
		List<String> flags = report.qualityOutcome() == null ? null
				: report.qualityOutcome().flags().stream().map(Enum::name).toList();
		if (retry) {
			this.jobs.requeue(jobId);
		}
		else {
			this.jobs.fail(jobId, "FAILED", report.code().name(), Boolean.TRUE.equals(report.retryable()), qualityStatus, flags);
		}
		record(worker, "PROCESSING_JOB_FAILED", AuditOutcome.FAILED, "processing_job", jobId, job.correlationId());
		return new ProcessingApi.FailureAccepted(jobId, retry ? ProcessingApi.JobStatus.QUEUED : ProcessingApi.JobStatus.FAILED);
	}

	// --- lease expiry --------------------------------------------------------------

	/** Expired leases go back to the queue, or fail with TIMEOUT after the last attempt. */
	@Scheduled(fixedDelayString = "${wellness.processing.lease-sweep-ms:30000}")
	@Transactional
	public void expireLeases() {
		for (String jobId : this.jobs.expiredLeases()) {
			JobRepository.JobRow job = this.jobs.find(jobId).orElseThrow();
			if (job.attempt() < MAX_ATTEMPTS) {
				this.jobs.requeue(jobId);
			}
			else {
				this.jobs.fail(jobId, "FAILED", "TIMEOUT", false, null, null);
			}
		}
	}

	// --- helpers -------------------------------------------------------------------

	/** The job, if this worker holds a current lease on it. */
	private JobRepository.JobRow leased(String jobId, String leaseToken, String workerId) {
		JobRepository.JobRow job = this.jobs.lock(jobId).orElseThrow(() -> new DomainException(WorkerProblemCode.LEASE_INVALID));
		if (!"RUNNING".equals(job.status())) {
			throw new DomainException("CONSENT_WITHDRAWN".equals(job.failureCode()) ? WorkerProblemCode.CONSENT_WITHDRAWN
					: WorkerProblemCode.JOB_NOT_RUNNING);
		}
		if (!tokenMatches(job, leaseToken, workerId)) {
			throw new DomainException(WorkerProblemCode.LEASE_INVALID);
		}
		if (job.leaseExpiresAt().isBefore(this.clock.instant())) {
			throw new DomainException(WorkerProblemCode.LEASE_EXPIRED);
		}
		return job;
	}

	private static boolean tokenMatches(JobRepository.JobRow job, String token, String workerId) {
		return job.leaseTokenHash() != null && token != null
				&& MessageDigest.isEqual(job.leaseTokenHash().getBytes(StandardCharsets.US_ASCII),
						sha256(token).getBytes(StandardCharsets.US_ASCII))
				&& workerId.equals(job.leaseWorkerId());
	}

	private Optional<ConsentSnapshot> stillConsented(JobRepository.JobRow job) {
		try {
			return Optional.of(this.consent.requireStillActive(job.consentEventId(), Set.of(ConsentScope.AUDIO_RECORDING)));
		}
		catch (DomainException ex) {
			return Optional.empty();
		}
	}

	private static ProcessingApi.ConsentReference reference(ConsentSnapshot s) {
		return new ProcessingApi.ConsentReference(s.consentEventId(),
				s.scopesVerified().stream().map(Enum::name).sorted().toList(), s.verifiedAt());
	}

	private DomainException reject(Actor worker, JobRepository.JobRow job, WorkerProblemCode code, List<String> pointers) {
		this.audit.recordIndependently(new AuditEvent("FEATURE_RECORD_REJECTED", AuditOutcome.REJECTED, worker.subject(),
				worker.roles(), "processing_job", job.jobId(), null, job.correlationId()));
		return new DomainException(code, pointers.stream().map(p -> new ApiProblem.FieldError(p, "schema")).toList());
	}

	private void record(Actor worker, String action, AuditOutcome outcome, String type, String id, UUID correlationId) {
		this.audit.record(new AuditEvent(action, outcome, worker.subject(), worker.roles(), type, id, null,
				CorrelationId.current().orElse(correlationId)));
	}

	private static String text(JsonNode node, String field) {
		return node.path(field).asString("");
	}

	static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
