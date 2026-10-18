package org.mentalwellness.api.processing.internal;

import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.security.SecureRandom;

import org.mentalwellness.api.audit.AccessReason;
import org.mentalwellness.api.audit.AuditEvent;
import org.mentalwellness.api.audit.AuditLog;
import org.mentalwellness.api.audit.AuditOutcome;
import org.mentalwellness.api.consent.ConsentGate;
import org.mentalwellness.api.consent.ConsentScope;
import org.mentalwellness.api.consent.ParticipantAccess;
import org.mentalwellness.api.shared.security.Actor;
import org.mentalwellness.api.shared.web.CorrelationId;
import org.mentalwellness.api.shared.web.DomainException;
import org.mentalwellness.api.shared.web.ProblemCode;
import org.mentalwellness.api.voicesession.RecordingAudio;
import org.mentalwellness.api.voicesession.SessionStatusContributor;
import org.mentalwellness.api.voicesession.StoredRecording;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public processing operations (slice S6) and the session processing status. */
@Service
class ProcessingService implements SessionStatusContributor {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JobRepository jobs;

	private final RecordingAudio recordings;

	private final ConsentGate consent;

	private final ParticipantAccess participants;

	private final AuditLog audit;

	ProcessingService(JobRepository jobs, RecordingAudio recordings, ConsentGate consent, ParticipantAccess participants,
			AuditLog audit) {
		this.jobs = jobs;
		this.recordings = recordings;
		this.consent = consent;
		this.participants = participants;
		this.audit = audit;
	}

	record Submitted(ProcessingApi.ProcessingJob job, boolean created) {
	}

	@Transactional
	public Submitted submit(Actor actor, ProcessingApi.ProcessingJobCreate request, AccessReason reason) {
		StoredRecording recording = this.recordings.find(request.recordingId())
			.filter(r -> canAccess(actor, r.participantRef()))
			.orElseThrow(() -> new DomainException(ProblemCode.NOT_FOUND));
		Optional<JobRepository.JobRow> existing = this.jobs.findByRecording(recording.recordingId(), request.pipelineProfile());
		if (existing.isPresent()) {
			return new Submitted(view(existing.get()), false);
		}
		if (!recording.usable()) {
			throw new DomainException(recording.sessionCancelled() ? ProblemCode.CONSENT_NOT_ACTIVE : ProblemCode.RECORDING_NOT_UPLOADED);
		}
		this.consent.require(recording.consentEventId(), recording.participantRef(), Set.of(ConsentScope.AUDIO_RECORDING),
				recording.recordedAt());
		String jobId = "job_" + randomHex(16);
		UUID correlationId = CorrelationId.current().orElseGet(UUID::randomUUID);
		boolean inserted = this.jobs.insert(jobId, recording.recordingId(), recording.sessionId(), recording.participantRef(),
				recording.consentEventId(), request.pipelineProfile(), actor.subject(), correlationId);
		JobRepository.JobRow job = this.jobs.findByRecording(recording.recordingId(), request.pipelineProfile()).orElseThrow();
		if (inserted) {
			this.audit.record(new AuditEvent("PROCESSING_JOB_SUBMITTED", AuditOutcome.SUCCESS, actor.subject(), actor.roles(),
					"processing_job", job.jobId(), reason, correlationId));
		}
		return new Submitted(view(job), inserted);
	}

	@Transactional // not read-only: reads are audited
	public ProcessingApi.ProcessingJob get(Actor actor, String jobId, AccessReason reason) {
		JobRepository.JobRow job = this.jobs.find(jobId)
			.filter(j -> canAccess(actor, j.participantRef()))
			.orElseThrow(() -> new DomainException(ProblemCode.NOT_FOUND));
		this.audit.record(new AuditEvent("PROCESSING_JOB_READ", AuditOutcome.SUCCESS, actor.subject(), actor.roles(),
				"processing_job", jobId, reason, CorrelationId.current().orElseGet(UUID::randomUUID)));
		return view(job);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<String> processingStatus(String sessionId) {
		List<String> statuses = this.jobs.statusesForSession(sessionId);
		if (statuses.isEmpty()) {
			return Optional.empty();
		}
		if (statuses.stream().anyMatch(s -> s.equals("QUEUED") || s.equals("RUNNING"))) {
			return Optional.of("PROCESSING");
		}
		if (statuses.stream().allMatch("SUCCEEDED"::equals)) {
			return Optional.of("PROCESSED");
		}
		return Optional.of("PROCESSING_FAILED");
	}

	private boolean canAccess(Actor actor, String participantRef) {
		return actor.has(Actor.ADMIN)
				|| (actor.has(Actor.ASHA_WORKER) && this.participants.isAssignedTo(participantRef, actor.subject()));
	}

	static ProcessingApi.ProcessingJob view(JobRepository.JobRow j) {
		ProcessingApi.QualityOutcome quality = j.qualityStatus() == null ? null
				: new ProcessingApi.QualityOutcome(ProcessingApi.QualityStatus.valueOf(j.qualityStatus()),
						j.qualityFlags() == null ? List.of()
								: j.qualityFlags().stream().map(ProcessingApi.QualityFlag::valueOf).toList());
		ProcessingApi.Failure failure = j.failureCode() == null ? null
				: new ProcessingApi.Failure(ProcessingApi.FailureCode.valueOf(j.failureCode()), Boolean.TRUE.equals(j.failureRetryable()));
		return new ProcessingApi.ProcessingJob(j.jobId(), j.recordingId(), j.sessionId(), j.pipelineProfile(),
				ProcessingApi.JobStatus.valueOf(j.status()), j.attempt(), quality, j.featureRecordId(), failure, j.createdAt(),
				j.updatedAt());
	}

	static String randomHex(int bytes) {
		byte[] b = new byte[bytes];
		RANDOM.nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

}
