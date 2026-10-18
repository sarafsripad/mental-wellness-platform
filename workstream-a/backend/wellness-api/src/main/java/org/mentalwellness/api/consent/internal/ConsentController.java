package org.mentalwellness.api.consent.internal;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.mentalwellness.api.shared.security.AccessReasons;
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

/** Consent operations of wellness-api.v1.yaml. Roles and scopes per the contract's x-roles and security. */
@RestController
@Validated
class ConsentController {

	private final ConsentService service;

	ConsentController(ConsentService service) {
		this.service = service;
	}

	@PostMapping("/v1/consent-events")
	@PreAuthorize("hasRole('ASHA_WORKER') and hasAuthority('SCOPE_consent:write')")
	ResponseEntity<ConsentApi.ConsentEvent> create(@RequestHeader("Idempotency-Key") UUID key,
			@Valid @RequestBody ConsentApi.ConsentEventCreate request) {
		var result = this.service.create(Actor.current(), key, request);
		if (result.replayed()) {
			return ResponseEntity.ok(result.body());
		}
		return ResponseEntity.created(URI.create("/v1/consent-events/" + result.body().consentEventId())).body(result.body());
	}

	@GetMapping("/v1/consent-events/{consentEventId}")
	@PreAuthorize("hasAnyRole('ASHA_WORKER', 'CLINICIAN', 'ADMIN') and hasAuthority('SCOPE_consent:read')")
	ConsentApi.ConsentEvent get(@PathVariable @Pattern(regexp = ConsentApi.CONSENT_EVENT_ID) String consentEventId,
			@RequestHeader(value = AccessReasons.HEADER, required = false) String reason) {
		Actor actor = Actor.current();
		return this.service.get(actor, consentEventId, AccessReasons.resolve(actor, reason).orElse(null));
	}

	@PostMapping("/v1/consent-events/{consentEventId}/withdrawals")
	@PreAuthorize("hasAnyRole('ASHA_WORKER', 'CLINICIAN', 'ADMIN') and hasAuthority('SCOPE_consent:write')")
	ResponseEntity<ConsentApi.ConsentWithdrawal> withdraw(
			@PathVariable @Pattern(regexp = ConsentApi.CONSENT_EVENT_ID) String consentEventId,
			@RequestHeader("Idempotency-Key") UUID key,
			@RequestHeader(value = AccessReasons.HEADER, required = false) String reason,
			@Valid @RequestBody ConsentApi.ConsentWithdrawalCreate request) {
		Actor actor = Actor.current();
		var outcome = this.service.withdraw(actor, key, consentEventId, request,
				AccessReasons.resolve(actor, reason).orElse(null));
		HttpStatus status = outcome.changed() && !outcome.replayed() ? HttpStatus.ACCEPTED : HttpStatus.OK;
		return ResponseEntity.status(status).body(outcome.body());
	}

	@GetMapping("/v1/participants/{participantRef}/consent-status")
	@PreAuthorize("hasAnyRole('ASHA_WORKER', 'CLINICIAN', 'ADMIN') and hasAuthority('SCOPE_consent:read')")
	ConsentApi.ConsentStatus status(@PathVariable @Pattern(regexp = ConsentApi.PARTICIPANT_REF) String participantRef,
			@RequestHeader(value = AccessReasons.HEADER, required = false) String reason) {
		Actor actor = Actor.current();
		return this.service.status(actor, participantRef, AccessReasons.resolve(actor, reason).orElse(null));
	}

}
