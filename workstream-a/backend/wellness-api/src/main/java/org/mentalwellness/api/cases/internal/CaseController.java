package org.mentalwellness.api.cases.internal;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.mentalwellness.api.shared.idempotency.IdempotencyService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Case operations of wellness-api.v1.yaml (1.1, tag {@code cases}). */
@RestController
@Validated
class CaseController {

	private final CaseService service;

	CaseController(CaseService service) {
		this.service = service;
	}

	@PostMapping("/v1/cases")
	@PreAuthorize("hasRole('ASHA_WORKER') and hasAuthority('SCOPE_cases:write')")
	ResponseEntity<CaseApi.Case> create(@RequestHeader("Idempotency-Key") UUID key,
			@Valid @RequestBody CaseApi.CaseCreate request) {
		var result = this.service.createCase(Actor.current(), key, request);
		return result.replayed() ? ResponseEntity.ok(result.body())
				: ResponseEntity.created(URI.create("/v1/cases/" + result.body().caseId())).body(result.body());
	}

	@GetMapping("/v1/cases")
	@PreAuthorize("hasRole('ASHA_WORKER') and hasAuthority('SCOPE_cases:read')")
	CaseApi.CaseList list(@RequestParam(name = "follow_up", required = false) Boolean followUp,
			@RequestParam(name = "limit", defaultValue = "50") @Min(1) @Max(200) int limit) {
		return this.service.listMyCases(Actor.current(), Boolean.TRUE.equals(followUp), limit);
	}

	@GetMapping("/v1/cases/{caseId}")
	@PreAuthorize("hasAnyRole('ASHA_WORKER', 'ADMIN') and hasAuthority('SCOPE_cases:read')")
	CaseApi.Case get(@PathVariable @Pattern(regexp = CaseApi.CASE_ID) String caseId,
			@RequestHeader(value = AccessReasons.HEADER, required = false) String reason) {
		Actor actor = Actor.current();
		return this.service.getCase(actor, caseId, AccessReasons.resolve(actor, reason).orElse(null));
	}

	@PostMapping("/v1/cases/{caseId}/observations")
	@PreAuthorize("hasRole('ASHA_WORKER') and hasAuthority('SCOPE_cases:write')")
	ResponseEntity<CaseApi.Case> observations(@PathVariable @Pattern(regexp = CaseApi.CASE_ID) String caseId,
			@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody CaseApi.ObservationSetCreate request) {
		return created(this.service.submitObservations(Actor.current(), key, caseId, request));
	}

	@PostMapping("/v1/cases/{caseId}/questionnaire-responses")
	@PreAuthorize("hasRole('ASHA_WORKER') and hasAuthority('SCOPE_cases:write')")
	ResponseEntity<CaseApi.Case> questionnaire(@PathVariable @Pattern(regexp = CaseApi.CASE_ID) String caseId,
			@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody CaseApi.QuestionnaireResponseCreate request) {
		return created(this.service.submitQuestionnaireResponse(Actor.current(), key, caseId, request));
	}

	@PostMapping("/v1/cases/{caseId}/completion")
	@PreAuthorize("hasRole('ASHA_WORKER') and hasAuthority('SCOPE_cases:write')")
	ResponseEntity<CaseApi.Case> complete(@PathVariable @Pattern(regexp = CaseApi.CASE_ID) String caseId,
			@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody CaseApi.CaseCompletion request) {
		return created(this.service.completeCase(Actor.current(), key, caseId, request));
	}

	private static ResponseEntity<CaseApi.Case> created(IdempotencyService.Result<CaseApi.Case> result) {
		return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
	}

}
