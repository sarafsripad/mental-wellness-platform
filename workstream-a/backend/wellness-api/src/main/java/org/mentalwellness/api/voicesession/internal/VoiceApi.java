package org.mentalwellness.api.voicesession.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Bodies of the voice-session operations, mirroring wellness-api.v1.yaml. */
final class VoiceApi {

	private VoiceApi() {
	}

	static final String SESSION_ID = "^sess_[a-f0-9]{32}$";
	static final String RECORDING_ID = "^rec_[a-f0-9]{32}$";
	static final long MAX_BYTES = 26_214_400L;

	enum DataOrigin { SYNTHETIC, AUTHORIZED_TEST_AUDIO, PARTICIPANT }

	enum DeviceClass { ANDROID_PHONE, IOS_PHONE, TABLET, UNKNOWN }

	enum OsFamily { ANDROID, IOS }

	enum Format {
		WAV_PCM_S16LE("audio/wav"), FLAC("audio/flac");

		final String mediaType;

		Format(String mediaType) {
			this.mediaType = mediaType;
		}
	}

	enum SessionStatus { CREATED, AWAITING_CONTENT, UPLOADED, PROCESSING, PROCESSED, PROCESSING_FAILED, CANCELLED }

	enum UploadStatus { AWAITING_CONTENT, STORED, REJECTED, DELETED }

	// --- requests ------------------------------------------------------------------

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record VoiceSessionCreate(
			@NotNull @Pattern(regexp = SESSION_ID) String sessionId,
			@NotNull @Pattern(regexp = "^ppt_[a-f0-9]{32}$") String participantRef,
			@NotNull @Pattern(regexp = "^ce_[a-f0-9]{32}$") String consentEventId,
			@NotNull @Pattern(regexp = "^(en-IN|kn-IN)$") String languageTag,
			@NotNull @Valid PromptSet promptSet,
			@NotNull Instant capturedAtUtc,
			@NotNull @Valid Device device,
			@NotNull DataOrigin dataOrigin) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record PromptSet(@NotNull @Pattern(regexp = "^[a-z0-9-]{3,64}$") String promptSetId,
			@NotNull @Pattern(regexp = "^\\d+\\.\\d+\\.\\d+$") String promptSetVersion) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record Device(@NotNull DeviceClass deviceClass, @NotNull @Pattern(regexp = "^\\d+\\.\\d+\\.\\d+$") String appVersion,
			@NotNull OsFamily osFamily) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record RecordingCreate(
			@NotNull @Pattern(regexp = RECORDING_ID) String recordingId,
			@NotNull @Pattern(regexp = "^[a-z0-9-]{3,64}$") String promptId,
			@NotNull Format format,
			@NotNull Integer samplingRateHz,
			@NotNull @Min(1) @Max(1) Integer channels,
			@NotNull @DecimalMin("3") @DecimalMax("180") BigDecimal durationSeconds,
			@NotNull @Min(1) @Max(MAX_BYTES) Long sizeBytes,
			@NotNull @Pattern(regexp = "^[a-f0-9]{64}$") String sha256,
			@NotNull Instant recordedAtUtc,
			@Valid DevicePrescreen devicePrescreen) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record DevicePrescreen(@DecimalMax("0") BigDecimal peakDbfs, Boolean clippingDetected, Boolean lowLevelDetected) {
	}

	// --- responses -----------------------------------------------------------------

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record Recording(String recordingId, String sessionId, String promptId, Format format, BigDecimal durationSeconds,
			String sha256, UploadStatus uploadStatus, String uploadPath, Instant storedAtUtc) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record VoiceSession(String sessionId, String participantRef, String consentEventId, String languageTag,
			SessionStatus status, DataOrigin dataOrigin, List<Recording> recordings, Instant createdAtUtc) {
	}

}
