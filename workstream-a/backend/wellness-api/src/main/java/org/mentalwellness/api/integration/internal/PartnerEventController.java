package org.mentalwellness.api.integration.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.mentalwellness.api.shared.security.Actor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * integration-internal.v1.yaml: coded status events from partner systems. The caller must hold
 * PARTNER_SYSTEM and be the OAuth client configured for that partner ({@code azp} claim).
 */
@RestController
@Validated
class PartnerEventController {

	private final ReferralService service;

	PartnerEventController(ReferralService service) {
		this.service = service;
	}

	@PostMapping("/internal/v1/partners/{partnerId}/referral-events")
	@PreAuthorize("hasRole('PARTNER_SYSTEM') and hasAuthority('SCOPE_partner:events')")
	ResponseEntity<IntegrationApi.PartnerEventResult> event(
			@PathVariable @Pattern(regexp = IntegrationApi.PARTNER_ID) String partnerId,
			@Valid @RequestBody IntegrationApi.PartnerEvent event, JwtAuthenticationToken token) {
		String authorizedParty = token.getToken().getClaimAsString("azp");
		var result = this.service.receive(Actor.current(), authorizedParty == null ? "" : authorizedParty, partnerId, event);
		return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
	}

}
