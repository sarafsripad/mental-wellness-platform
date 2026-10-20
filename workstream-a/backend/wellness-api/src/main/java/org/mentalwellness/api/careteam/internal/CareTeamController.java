package org.mentalwellness.api.careteam.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.mentalwellness.api.audit.AccessReason;
import org.mentalwellness.api.shared.security.AccessReasons;
import org.mentalwellness.api.shared.security.Actor;
import org.mentalwellness.api.shared.web.DomainException;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Care-team operations of wellness-api.v1.yaml (1.4, tag {@code care-team}). */
@RestController
@Validated
class CareTeamController {

	private final CareTeamService service;

	CareTeamController(CareTeamService service) {
		this.service = service;
	}

	@PutMapping("/v1/admin/facilities/{facilityCode}")
	@PreAuthorize("hasRole('ADMIN') and hasAuthority('SCOPE_careteam:admin')")
	CareTeamApi.Facility putFacility(@PathVariable @Pattern(regexp = CareTeamApi.FACILITY) String facilityCode,
			@RequestHeader(value = AccessReasons.HEADER, required = false) String reason,
			@Valid @RequestBody CareTeamApi.FacilityPut body) {
		Actor actor = Actor.current();
		return this.service.putFacility(actor, requiredReason(reason), facilityCode, body);
	}

	@PutMapping("/v1/admin/facilities/{facilityCode}/memberships/{subject}")
	@PreAuthorize("hasRole('ADMIN') and hasAuthority('SCOPE_careteam:admin')")
	CareTeamApi.Membership putMembership(@PathVariable @Pattern(regexp = CareTeamApi.FACILITY) String facilityCode,
			@PathVariable @Pattern(regexp = CareTeamApi.SUBJECT) String subject,
			@RequestHeader(value = AccessReasons.HEADER, required = false) String reason,
			@Valid @RequestBody CareTeamApi.MembershipPut body) {
		return this.service.putMembership(Actor.current(), requiredReason(reason), facilityCode, subject, body);
	}

	@PutMapping("/v1/participants/{participantRef}/care-team/{facilityCode}")
	@PreAuthorize("(hasRole('ASHA_WORKER') and hasAuthority('SCOPE_careteam:write')) or (hasRole('ADMIN') and hasAuthority('SCOPE_careteam:admin'))")
	CareTeamApi.CareTeam add(@PathVariable @Pattern(regexp = "^ppt_[a-f0-9]{32}$") String participantRef,
			@PathVariable @Pattern(regexp = CareTeamApi.FACILITY) String facilityCode,
			@RequestHeader(value = AccessReasons.HEADER, required = false) String reason,
			@Valid @RequestBody CareTeamApi.CareTeamAdd body) {
		Actor actor = Actor.current();
		return this.service.add(actor, AccessReasons.resolve(actor, reason).orElse(null), participantRef, facilityCode, body);
	}

	@GetMapping("/v1/participants/{participantRef}/care-team")
	@PreAuthorize("(hasAnyRole('ASHA_WORKER', 'CLINICIAN', 'COUNSELLOR', 'HEALTH_STAFF') and hasAuthority('SCOPE_careteam:read')) or (hasRole('ADMIN') and hasAuthority('SCOPE_careteam:admin'))")
	CareTeamApi.CareTeam read(@PathVariable @Pattern(regexp = "^ppt_[a-f0-9]{32}$") String participantRef,
			@RequestHeader(value = AccessReasons.HEADER, required = false) String reason) {
		Actor actor = Actor.current();
		return this.service.read(actor, AccessReasons.resolve(actor, reason).orElse(null), participantRef);
	}

	@GetMapping("/v1/me/care-memberships")
	@PreAuthorize("hasAnyRole('CLINICIAN', 'COUNSELLOR', 'HEALTH_STAFF') and hasAuthority('SCOPE_careteam:read')")
	CareTeamApi.MyMemberships mine() {
		return this.service.mine(Actor.current());
	}

	/** Administration always needs a reason code, even for users who also hold other roles. */
	private static AccessReason requiredReason(String header) {
		if (header == null) {
			throw DomainException.invalid("/" + AccessReasons.HEADER, "required");
		}
		try {
			return AccessReason.valueOf(header);
		}
		catch (IllegalArgumentException ex) {
			throw DomainException.invalid("/" + AccessReasons.HEADER, "enum");
		}
	}

}
