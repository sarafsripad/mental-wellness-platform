package org.mentalwellness.api.careteam.internal;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.mentalwellness.api.careteam.CareRole;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Bodies of the care-team operations (wellness-api.v1.yaml 1.4, tag {@code care-team}). */
final class CareTeamApi {

	private CareTeamApi() {
	}

	static final String FACILITY = "^[a-z0-9-]{3,40}$";
	static final String SUBJECT = "^[A-Za-z0-9_-]{1,64}$";

	enum Kind { PHC, CHC, DISTRICT_HOSPITAL, TELE_CENTRE, COUNSELLING_CENTRE }

	enum Reason { ASHA_REFERRAL, CLINIC_REGISTRATION, TRANSFER }

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record FacilityPut(@NotNull Kind kind, @NotNull @Size(min = 1, max = 80) @Pattern(regexp = ".*\\S.*") String facilityLabel,
			@NotNull Boolean active) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record Facility(String facilityCode, Kind kind, String facilityLabel, boolean active) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record MembershipPut(@NotNull CareRole careRole, @NotNull Boolean active) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record Membership(String facilityCode, String facilityLabel, Kind kind, CareRole careRole) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record MyMemberships(String staffRef, List<Membership> memberships) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record CareTeamAdd(@NotNull Reason reasonCode, @NotNull @Pattern(regexp = "^ce_[a-f0-9]{32}$") String consentEventId) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record CareTeamFacility(String facilityCode, String facilityLabel, Kind kind, Reason reasonCode, Instant addedAtUtc) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record CareTeam(String participantRef, String ashaWorkerRef, List<CareTeamFacility> facilities) {
	}

}
