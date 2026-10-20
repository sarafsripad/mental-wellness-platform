package org.mentalwellness.api.identity.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.mentalwellness.api.shared.security.Actor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** identity-api.v1.yaml 1.1: store, audited lookup and search. There is deliberately no GET. */
@RestController
@Validated
@ConditionalOnWebApplication
class IdentityController {

	private final IdentityService service;

	IdentityController(IdentityService service) {
		this.service = service;
	}

	@PutMapping("/v1/participants/{participantRef}/identity")
	@PreAuthorize("(hasRole('ASHA_WORKER') and hasAuthority('SCOPE_cases:write')) or (hasAnyRole('CLINICIAN', 'COUNSELLOR', 'HEALTH_STAFF') and hasAuthority('SCOPE_encounters:write'))")
	IdentityApi.IdentityStored put(@PathVariable @Pattern(regexp = IdentityApi.PARTICIPANT_REF) String participantRef,
			@Valid @RequestBody IdentityApi.IdentityRecordPut request) {
		return this.service.put(Actor.current(), participantRef, request);
	}

	@PostMapping("/v1/participants/{participantRef}/identity/lookup")
	@PreAuthorize("hasAnyRole('CLINICIAN', 'COUNSELLOR', 'HEALTH_STAFF') and hasAuthority('SCOPE_identity:lookup')")
	ResponseEntity<IdentityApi.IdentityDetails> lookup(
			@PathVariable @Pattern(regexp = IdentityApi.PARTICIPANT_REF) String participantRef,
			@Valid @RequestBody IdentityApi.LookupRequest request) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.service.lookup(Actor.current(), participantRef, request.reasonCode()));
	}

	@PostMapping("/v1/identity/search")
	@PreAuthorize("hasAnyRole('CLINICIAN', 'COUNSELLOR', 'HEALTH_STAFF') and hasAuthority('SCOPE_identity:lookup')")
	IdentityApi.SearchResult search(@Valid @RequestBody IdentityApi.SearchRequest request) {
		return this.service.search(Actor.current(), request);
	}

}
