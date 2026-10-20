package org.mentalwellness.api.identity.internal;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Bodies of identity-api.v1.yaml. Identifiers come back only from the audited lookup
 * ({@link IdentityDetails}); every other response carries pseudonymous references only.
 */
final class IdentityApi {

	private IdentityApi() {
	}

	static final String PARTICIPANT_REF = "^ppt_[a-f0-9]{32}$";
	static final String PHONE = "^\\+?[0-9]{10,13}$";
	static final String CARD_CODE = "^[A-Z2-9]{8}$";

	enum DataOrigin { SYNTHETIC, PARTICIPANT }

	/** Why a staff member needs to see who the person is; recorded in the audit event. */
	enum LookupReason { DIRECT_CARE, TELE_CONSULTATION, FOLLOW_UP_CONTACT }

	/**
	 * {@code toString} is overridden so a name, village or phone can never reach a log line or
	 * exception message by accident.
	 */
	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record IdentityRecordPut(
			@NotNull @Pattern(regexp = "^ce_[a-f0-9]{32}$") String consentEventId,
			@NotNull DataOrigin dataOrigin,
			@NotNull @Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*") String displayName,
			@NotNull @Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*") String villageName,
			@Pattern(regexp = PHONE) String contactPhone) {

		IdentityRecordPut(String consentEventId, DataOrigin dataOrigin, String displayName, String villageName) {
			this(consentEventId, dataOrigin, displayName, villageName, null);
		}

		@Override
		public String toString() {
			return "IdentityRecordPut[consentEventId=" + this.consentEventId + ", dataOrigin=" + this.dataOrigin
					+ ", displayName=<redacted>, villageName=<redacted>, contactPhone=<redacted>]";
		}

	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record IdentityStored(String patientCardCode) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record LookupRequest(@NotNull LookupReason reasonCode) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record IdentityDetails(String participantRef, String displayName, String villageName, String contactPhone,
			String patientCardCode) {

		@Override
		public String toString() {
			return "IdentityDetails[" + this.participantRef + ", <redacted>]";
		}

	}

	/** Either a card code, or a name and village (exact match after normalisation). */
	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record SearchRequest(@Pattern(regexp = CARD_CODE) String patientCardCode,
			@Size(min = 1, max = 100) String displayName, @Size(min = 1, max = 100) String villageName) {

		@Override
		public String toString() {
			return "SearchRequest[<redacted>]";
		}

	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record SearchResult(List<String> participantRefs) {
	}

}
