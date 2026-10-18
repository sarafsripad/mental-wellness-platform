package org.mentalwellness.api.processing.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Bodies of the public processing operations and the internal worker API. */
final class ProcessingApi {

	private ProcessingApi() {
	}

	static final String JOB_ID = "^job_[a-f0-9]{32}$";
	static final String WORKER_ID = "^wrk_[a-f0-9]{16}$";
	static final String PROFILE = "EGEMAPS_V02_FUNCTIONALS_V1";

	enum JobStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

	enum FailureCode { AUDIO_QUALITY_FAILED, AUDIO_DECODE_FAILED, EXTRACTION_ERROR, TIMEOUT, CONSENT_WITHDRAWN, INTERNAL_ERROR }

	enum QualityStatus { PASS, DEGRADED, FAIL }

	enum QualityFlag { CLIPPING, LOW_LEVEL, SHORT_DURATION, LONG_DURATION, HIGH_NOISE, LOW_SPEECH_RATIO, SILENCE, FORMAT_MISMATCH }

	// --- public API ----------------------------------------------------------------

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record ProcessingJobCreate(@NotNull @Pattern(regexp = "^rec_[a-f0-9]{32}$") String recordingId,
			@NotNull @Pattern(regexp = "^" + PROFILE + "$") String pipelineProfile) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record QualityOutcome(@NotNull QualityStatus status, @NotNull List<@NotNull QualityFlag> flags) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record Failure(FailureCode code, boolean retryable) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record ProcessingJob(String jobId, String recordingId, String sessionId, String pipelineProfile, JobStatus status,
			int attempt, QualityOutcome qualityOutcome, String featureRecordId, Failure failure, Instant createdAtUtc,
			Instant updatedAtUtc) {
	}

	// --- worker API ----------------------------------------------------------------

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record ClaimRequest(@NotEmpty List<@NotNull @Pattern(regexp = "^" + PROFILE + "$") String> supportedProfiles,
			@NotNull @Pattern(regexp = "^\\d+\\.\\d+\\.\\d+$") String workerVersion) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record ConsentReference(String consentEventId, List<String> scopesVerified, Instant verifiedAtUtc) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record LeaseAudio(String format, int samplingRateHz, int channels, BigDecimal durationSeconds, String sha256) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record JobLease(String jobId, String recordingId, String sessionId, String pipelineProfile, String leaseToken,
			int leaseSeconds, UUID correlationId, String languageTag, String dataOrigin, String captureDeviceClass,
			Instant recordedAtUtc, ConsentReference consentReference, LeaseAudio audio) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record ResultAccepted(String jobId, String featureRecordId, JobStatus jobStatus) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record FailureReport(@NotNull FailureCode code, @NotNull Boolean retryable, @Valid QualityOutcome qualityOutcome,
			Integer toolExitCode) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record FailureAccepted(String jobId, JobStatus jobStatus) {
	}

}
