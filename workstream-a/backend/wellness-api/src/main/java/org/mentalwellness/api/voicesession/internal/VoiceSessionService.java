package org.mentalwellness.api.voicesession.internal;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.mentalwellness.api.audit.AccessReason;
import org.mentalwellness.api.audit.AuditEvent;
import org.mentalwellness.api.audit.AuditLog;
import org.mentalwellness.api.audit.AuditOutcome;
import org.mentalwellness.api.consent.ConsentGate;
import org.mentalwellness.api.consent.ConsentScope;
import org.mentalwellness.api.consent.ParticipantAccess;
import org.mentalwellness.api.shared.idempotency.IdempotencyService;
import org.mentalwellness.api.shared.security.Actor;
import org.mentalwellness.api.shared.web.CorrelationId;
import org.mentalwellness.api.shared.web.DomainException;
import org.mentalwellness.api.shared.web.ProblemCode;
import org.mentalwellness.api.voicesession.RecordingAudio;
import org.mentalwellness.api.voicesession.SessionStatusContributor;
import org.mentalwellness.api.voicesession.StoredRecording;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Voice sessions and recordings (slice S5). Consent is checked with {@link ConsentGate} on the
 * session's own consent event at every step: session creation, recording registration and upload.
 */
@Service
class VoiceSessionService implements RecordingAudio {

	static final Duration FUTURE_SKEW = Duration.ofMinutes(5);

	static final Set<Integer> SAMPLING_RATES = Set.of(16000, 22050, 44100, 48000);

	private final VoiceSessionRepository repository;

	private final AudioStore store;

	private final ConsentGate consent;

	private final ParticipantAccess participants;

	private final IdempotencyService idempotency;

	private final AuditLog audit;

	private final Set<String> allowedOrigins;

	private final List<SessionStatusContributor> statusContributors;

	private final Clock clock;

	VoiceSessionService(VoiceSessionRepository repository, AudioStore store, ConsentGate consent,
			ParticipantAccess participants, IdempotencyService idempotency, AuditLog audit,
			@Value("${wellness.data.allowed-origins}") Set<String> allowedOrigins,
			List<SessionStatusContributor> statusContributors, Clock clock) {
		this.repository = repository;
		this.store = store;
		this.consent = consent;
		this.participants = participants;
		this.idempotency = idempotency;
		this.audit = audit;
		this.allowedOrigins = Set.copyOf(allowedOrigins);
		this.statusContributors = statusContributors;
		this.clock = clock;
	}

	// --- sessions ------------------------------------------------------------------

	@Transactional
	public IdempotencyService.Result<VoiceApi.VoiceSession> createSession(Actor actor, UUID key,
			VoiceApi.VoiceSessionCreate request) {
		return this.idempotency.execute(actor.subject(), "createVoiceSession", key, request, VoiceApi.VoiceSession.class,
				() -> doCreateSession(actor, request));
	}

	private VoiceApi.VoiceSession doCreateSession(Actor actor, VoiceApi.VoiceSessionCreate request) {
		if (!this.allowedOrigins.contains(request.dataOrigin().name())) {
			throw rejected(actor, "VOICE_SESSION_REJECTED_CONSENT", "voice_session", request.sessionId(),
					ProblemCode.DATA_ORIGIN_NOT_PERMITTED);
		}
		if (request.capturedAtUtc().isAfter(this.clock.instant().plus(FUTURE_SKEW))) {
			throw DomainException.invalid("/captured_at_utc", "notInFuture");
		}
		if (!this.participants.isAssignedTo(request.participantRef(), actor.subject())) {
			throw new AccessDeniedException("participant not assigned to caller");
		}
		try {
			this.consent.require(request.consentEventId(), request.participantRef(),
					Set.of(ConsentScope.SCREENING_PARTICIPATION, ConsentScope.AUDIO_RECORDING), request.capturedAtUtc());
		}
		catch (DomainException ex) {
			throw rejected(actor, "VOICE_SESSION_REJECTED_CONSENT", "voice_session", request.sessionId(), ex.code());
		}
		if (this.repository.sessionExists(request.sessionId())) {
			throw new DomainException(ProblemCode.RESOURCE_CONFLICT);
		}
		this.repository.insertSession(request, actor.subject());
		this.audit.record(event(actor, "VOICE_SESSION_CREATED", AuditOutcome.SUCCESS, "voice_session", request.sessionId(), null));
		return view(request.sessionId());
	}

	@Transactional // not read-only: reads are audited
	public VoiceApi.VoiceSession getSession(Actor actor, String sessionId, AccessReason reason) {
		VoiceSessionRepository.SessionRow session = visibleSession(actor, sessionId);
		this.audit.record(event(actor, "VOICE_SESSION_READ", AuditOutcome.SUCCESS, "voice_session", session.sessionId(), reason));
		return view(sessionId);
	}

	// --- recordings ----------------------------------------------------------------

	@Transactional
	public IdempotencyService.Result<VoiceApi.Recording> registerRecording(Actor actor, UUID key, String sessionId,
			VoiceApi.RecordingCreate request) {
		VoiceSessionRepository.SessionRow session = ownedSession(actor, sessionId);
		if (!SAMPLING_RATES.contains(request.samplingRateHz())) {
			throw DomainException.invalid("/sampling_rate_hz", "enum");
		}
		if (request.recordedAtUtc().isAfter(this.clock.instant().plus(FUTURE_SKEW))) {
			throw DomainException.invalid("/recorded_at_utc", "notInFuture");
		}
		record Hashed(String sessionId, VoiceApi.RecordingCreate body) {
		}
		return this.idempotency.execute(actor.subject(), "submitRecordingMetadata", key, new Hashed(sessionId, request),
				VoiceApi.Recording.class, () -> {
					requireRecordingConsent(actor, session, request.recordedAtUtc(), request.recordingId());
					if (this.repository.recordingExists(request.recordingId())) {
						throw new DomainException(ProblemCode.RESOURCE_CONFLICT);
					}
					this.repository.insertRecording(sessionId, request);
					this.audit.record(event(actor, "RECORDING_REGISTERED", AuditOutcome.SUCCESS, "recording",
							request.recordingId(), null));
					return recordingView(this.repository.findRecording(request.recordingId()).orElseThrow());
				});
	}

	@Transactional
	public VoiceApi.Recording upload(Actor actor, String sessionId, String recordingId, String contentType,
			String declaredSha256, byte[] content) {
		VoiceSessionRepository.SessionRow session = ownedSession(actor, sessionId);
		VoiceSessionRepository.RecordingRow recording = this.repository.lockRecording(recordingId)
			.filter(r -> r.sessionId().equals(sessionId))
			.orElseThrow(() -> new DomainException(ProblemCode.NOT_FOUND));
		VoiceApi.Format format = VoiceApi.Format.valueOf(recording.format());
		if (contentType == null || !contentType.toLowerCase().startsWith(format.mediaType) || !magicMatches(format, content)) {
			throw rejected(actor, "RECORDING_CONTENT_REJECTED", "recording", recordingId, ProblemCode.UNSUPPORTED_MEDIA_TYPE);
		}
		String actualSha256 = sha256(content);
		if ("STORED".equals(recording.uploadStatus())) {
			if (actualSha256.equals(recording.sha256())) {
				return recordingView(recording); // identical re-upload: idempotent
			}
			throw rejected(actor, "RECORDING_CONTENT_REJECTED", "recording", recordingId, ProblemCode.RESOURCE_CONFLICT);
		}
		if (!"AWAITING_CONTENT".equals(recording.uploadStatus())) {
			throw rejected(actor, "RECORDING_CONTENT_REJECTED", "recording", recordingId, ProblemCode.CONSENT_NOT_ACTIVE);
		}
		if (content.length != recording.sizeBytes() || !actualSha256.equals(recording.sha256())
				|| !actualSha256.equals(declaredSha256)) {
			throw rejected(actor, "RECORDING_CONTENT_REJECTED", "recording", recordingId, ProblemCode.CHECKSUM_MISMATCH);
		}
		requireRecordingConsent(actor, session, recording.recordedAt(), recordingId);
		String key = AudioStore.keyFor(recordingId);
		this.store.put(key, content, format.mediaType);
		this.repository.markStored(recordingId, key);
		this.audit.record(event(actor, "RECORDING_CONTENT_STORED", AuditOutcome.SUCCESS, "recording", recordingId, null));
		return recordingView(this.repository.findRecording(recordingId).orElseThrow());
	}

	// --- RecordingAudio (for the processing module) --------------------------------

	@Override
	@Transactional(readOnly = true)
	public Optional<StoredRecording> findStored(String recordingId) {
		return this.repository.findRecording(recordingId)
			.filter(r -> "STORED".equals(r.uploadStatus()))
			.flatMap(r -> this.repository.findSession(r.sessionId())
				.filter(s -> s.cancelledAt() == null)
				.map(s -> new StoredRecording(r.recordingId(), s.sessionId(), s.participantRef(), s.consentEventId(),
						s.languageTag(), s.dataOrigin(), s.deviceClass(), r.recordedAt(), r.format(), r.samplingRateHz(),
						r.channels(), r.durationSeconds(), r.sizeBytes(), r.sha256())));
	}

	@Override
	@Transactional(readOnly = true)
	public byte[] read(String recordingId) {
		VoiceSessionRepository.RecordingRow r = this.repository.findRecording(recordingId)
			.filter(x -> "STORED".equals(x.uploadStatus()))
			.orElseThrow(() -> new DomainException(ProblemCode.NOT_FOUND));
		byte[] content = this.store.get(r.objectKey());
		if (!sha256(content).equals(r.sha256())) {
			throw new IllegalStateException("Stored audio failed its integrity check");
		}
		return content;
	}

	// --- helpers -------------------------------------------------------------------

	private void requireRecordingConsent(Actor actor, VoiceSessionRepository.SessionRow session, java.time.Instant recordedAt,
			String recordingId) {
		if (session.cancelledAt() != null) {
			throw rejected(actor, "RECORDING_CONTENT_REJECTED", "recording", recordingId, ProblemCode.CONSENT_NOT_ACTIVE);
		}
		try {
			this.consent.require(session.consentEventId(), session.participantRef(), Set.of(ConsentScope.AUDIO_RECORDING),
					recordedAt);
		}
		catch (DomainException ex) {
			throw rejected(actor, "RECORDING_CONTENT_REJECTED", "recording", recordingId, ex.code());
		}
	}

	/** The session, if the caller may read it: the assigned worker, or ADMIN. Others get 404. */
	private VoiceSessionRepository.SessionRow visibleSession(Actor actor, String sessionId) {
		return this.repository.findSession(sessionId)
			.filter(s -> actor.has(Actor.ADMIN)
					|| (actor.has(Actor.ASHA_WORKER) && this.participants.isAssignedTo(s.participantRef(), actor.subject())))
			.orElseThrow(() -> new DomainException(ProblemCode.NOT_FOUND));
	}

	/** The session, if the caller is the participant's assigned worker (writes). Others get 404. */
	private VoiceSessionRepository.SessionRow ownedSession(Actor actor, String sessionId) {
		return this.repository.findSession(sessionId)
			.filter(s -> this.participants.isAssignedTo(s.participantRef(), actor.subject()))
			.orElseThrow(() -> new DomainException(ProblemCode.NOT_FOUND));
	}

	private VoiceApi.VoiceSession view(String sessionId) {
		VoiceSessionRepository.SessionRow s = this.repository.findSession(sessionId).orElseThrow();
		List<VoiceApi.Recording> recordings = this.repository.recordings(sessionId).stream().map(this::recordingView).toList();
		return new VoiceApi.VoiceSession(s.sessionId(), s.participantRef(), s.consentEventId(), s.languageTag(),
				status(s, recordings), VoiceApi.DataOrigin.valueOf(s.dataOrigin()), recordings, s.createdAt());
	}

	private VoiceApi.SessionStatus status(VoiceSessionRepository.SessionRow s, List<VoiceApi.Recording> recordings) {
		if (s.cancelledAt() != null) {
			return VoiceApi.SessionStatus.CANCELLED;
		}
		if (recordings.isEmpty()) {
			return VoiceApi.SessionStatus.CREATED;
		}
		if (recordings.stream().anyMatch(r -> r.uploadStatus() == VoiceApi.UploadStatus.AWAITING_CONTENT)) {
			return VoiceApi.SessionStatus.AWAITING_CONTENT;
		}
		return this.statusContributors.stream()
			.map(c -> c.processingStatus(s.sessionId()))
			.flatMap(Optional::stream)
			.findFirst()
			.map(VoiceApi.SessionStatus::valueOf)
			.orElse(VoiceApi.SessionStatus.UPLOADED);
	}

	private VoiceApi.Recording recordingView(VoiceSessionRepository.RecordingRow r) {
		boolean awaiting = "AWAITING_CONTENT".equals(r.uploadStatus());
		return new VoiceApi.Recording(r.recordingId(), r.sessionId(), r.promptId(), VoiceApi.Format.valueOf(r.format()),
				r.durationSeconds(), r.sha256(), VoiceApi.UploadStatus.valueOf(r.uploadStatus()),
				awaiting ? "/v1/voice-sessions/" + r.sessionId() + "/recordings/" + r.recordingId() + "/content" : null,
				r.storedAt());
	}

	static boolean magicMatches(VoiceApi.Format format, byte[] content) {
		return switch (format) {
			case WAV_PCM_S16LE -> content.length >= 12 && content[0] == 'R' && content[1] == 'I' && content[2] == 'F'
					&& content[3] == 'F' && content[8] == 'W' && content[9] == 'A' && content[10] == 'V' && content[11] == 'E';
			case FLAC -> content.length >= 4 && content[0] == 'f' && content[1] == 'L' && content[2] == 'a' && content[3] == 'C';
		};
	}

	static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private DomainException rejected(Actor actor, String action, String type, String id, ProblemCode code) {
		this.audit.recordIndependently(event(actor, action, AuditOutcome.REJECTED, type, id, null));
		return new DomainException(code);
	}

	private AuditEvent event(Actor actor, String action, AuditOutcome outcome, String type, String id, AccessReason reason) {
		return new AuditEvent(action, outcome, actor.subject(), actor.roles(), type, id, reason,
				CorrelationId.current().orElseGet(UUID::randomUUID));
	}

}
