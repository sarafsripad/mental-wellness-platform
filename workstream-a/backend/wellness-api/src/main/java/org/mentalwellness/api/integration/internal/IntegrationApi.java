package org.mentalwellness.api.integration.internal;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Bodies of the referral operations (wellness-api.v1.yaml 1.3, integration-internal.v1.yaml). */
final class IntegrationApi {

	private IntegrationApi() {
	}

	static final String CASE_ID = "^case_[a-f0-9]{32}$";
	static final String PARTNER_ID = "^[a-z0-9-]{3,40}$";
	static final String PARTNER_TEXT = "^[A-Za-z0-9-]{1,64}$";

	/** Statuses a partner may report. */
	enum PartnerStatus { RECEIVED, CONTACT_ATTEMPTED, CONTACTED, APPOINTMENT_BOOKED, CLOSED, DECLINED }

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record ReferralCreate(
			@NotNull @Pattern(regexp = "^ref_[a-f0-9]{32}$") String referralId,
			@NotNull @Pattern(regexp = PARTNER_ID) String partnerId,
			@NotNull @Pattern(regexp = "^rvw_[a-f0-9]{32}$") String reviewId) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record Referral(String referralId, String caseId, String partnerId, String partnerName, String partnerMode,
			String decision, String followUpWithin, String handoffCode, String status, Instant statusAtUtc,
			Instant createdAtUtc) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record ReferralList(List<Referral> referrals) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record Partner(String partnerId, String partnerLabel, String kind, String mode) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record PartnerList(List<Partner> partners) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record PartnerEvent(
			@NotNull @Pattern(regexp = PARTNER_TEXT) String eventId,
			@NotNull @Pattern(regexp = "^ref_[a-f0-9]{32}$") String referralId,
			@NotNull PartnerStatus status,
			@NotNull Instant occurredAtUtc,
			@Pattern(regexp = PARTNER_TEXT) String partnerReference) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record PartnerEventResult(String referralId, String status, boolean applied) {
	}

}
