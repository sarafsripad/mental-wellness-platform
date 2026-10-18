package org.mentalwellness.api.identity.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.mentalwellness.api.shared.security.Actor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** identity-api.v1.yaml: write-only. There is deliberately no GET. */
@RestController
@Validated
@ConditionalOnWebApplication
class IdentityController {

	private final IdentityService service;

	IdentityController(IdentityService service) {
		this.service = service;
	}

	@PutMapping("/v1/participants/{participantRef}/identity")
	@PreAuthorize("hasRole('ASHA_WORKER') and hasAuthority('SCOPE_cases:write')")
	ResponseEntity<Void> put(@PathVariable @Pattern(regexp = IdentityApi.PARTICIPANT_REF) String participantRef,
			@Valid @RequestBody IdentityApi.IdentityRecordPut request) {
		this.service.put(Actor.current(), participantRef, request);
		return ResponseEntity.noContent().build();
	}

}
