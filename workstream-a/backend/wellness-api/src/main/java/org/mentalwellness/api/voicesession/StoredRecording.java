package org.mentalwellness.api.voicesession;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What the processing module needs about a recording whose audio is stored. No identity data: the
 * participant is referenced only by pseudonym.
 */
public record StoredRecording(String recordingId, String sessionId, String participantRef, String consentEventId,
		String languageTag, String dataOrigin, String deviceClass, String appVersion, Instant recordedAt, String format,
		int samplingRateHz, int channels, BigDecimal durationSeconds, long sizeBytes, String sha256, String uploadStatus,
		boolean sessionCancelled) {

	/** Audio is stored and usable: uploaded, not deleted, session not cancelled. */
	public boolean usable() {
		return "STORED".equals(this.uploadStatus) && !this.sessionCancelled;
	}

}
