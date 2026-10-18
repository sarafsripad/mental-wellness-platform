package org.mentalwellness.api.identity.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Request body of identity-api.v1.yaml. There is no response body: identifiers are never returned. */
final class IdentityApi {

	private IdentityApi() {
	}

	static final String PARTICIPANT_REF = "^ppt_[a-f0-9]{32}$";

	enum DataOrigin { SYNTHETIC, PARTICIPANT }

	/**
	 * {@code toString} is overridden so a name or village can never reach a log line or exception
	 * message by accident.
	 */
	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record IdentityRecordPut(
			@NotNull @Pattern(regexp = "^ce_[a-f0-9]{32}$") String consentEventId,
			@NotNull DataOrigin dataOrigin,
			@NotNull @Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*") String displayName,
			@NotNull @Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*") String villageName) {

		@Override
		public String toString() {
			return "IdentityRecordPut[consentEventId=" + this.consentEventId + ", dataOrigin=" + this.dataOrigin
					+ ", displayName=<redacted>, villageName=<redacted>]";
		}

	}

}
