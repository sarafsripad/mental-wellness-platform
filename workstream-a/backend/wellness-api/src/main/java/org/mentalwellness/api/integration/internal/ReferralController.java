package org.mentalwellness.api.integration.internal;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.mentalwellness.api.shared.security.Actor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Partner referral operations of wellness-api.v1.yaml (1.3, tag {@code referrals}). */
@RestController
@Validated
class ReferralController {

	private final ReferralService service;

	ReferralController(ReferralService service) {
		this.service = service;
	}

	@GetMapping("/v1/integration/partners")
	@PreAuthorize("hasRole('CLINICIAN') and hasAuthority('SCOPE_review:read')")
	IntegrationApi.PartnerList partners() {
		return this.service.referralPartners();
	}

	@PostMapping("/v1/review-queue/{caseId}/referrals")
	@PreAuthorize("hasRole('CLINICIAN') and hasAuthority('SCOPE_review:write')")
	ResponseEntity<IntegrationApi.Referral> create(@PathVariable @Pattern(regexp = IntegrationApi.CASE_ID) String caseId,
			@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody IntegrationApi.ReferralCreate request) {
		var result = this.service.create(Actor.current(), key, caseId, request);
		return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
	}

	@GetMapping("/v1/cases/{caseId}/referrals")
	@PreAuthorize("(hasRole('ASHA_WORKER') and hasAuthority('SCOPE_cases:read')) or (hasRole('CLINICIAN') and hasAuthority('SCOPE_review:read'))")
	IntegrationApi.ReferralList forCase(@PathVariable @Pattern(regexp = IntegrationApi.CASE_ID) String caseId) {
		return this.service.forCase(Actor.current(), caseId);
	}

}
