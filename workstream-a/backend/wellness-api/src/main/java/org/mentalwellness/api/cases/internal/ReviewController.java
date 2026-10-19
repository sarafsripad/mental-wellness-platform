package org.mentalwellness.api.cases.internal;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Review operations of wellness-api.v1.yaml (1.2, tag {@code review}). CLINICIAN only. */
@RestController
@Validated
class ReviewController {

	private final ReviewService service;

	ReviewController(ReviewService service) {
		this.service = service;
	}

	@GetMapping("/v1/review-queue")
	@PreAuthorize("hasRole('CLINICIAN') and hasAuthority('SCOPE_review:read')")
	CaseApi.ReviewQueue queue(@RequestParam(name = "status", defaultValue = "AWAITING_REVIEW") CaseApi.QueueFilter status,
			@RequestParam(name = "limit", defaultValue = "50") @Min(1) @Max(200) int limit) {
		return this.service.queue(Actor.current(), status, limit);
	}

	@GetMapping("/v1/review-queue/{caseId}")
	@PreAuthorize("hasRole('CLINICIAN') and hasAuthority('SCOPE_review:read')")
	CaseApi.ReviewCase get(@PathVariable @Pattern(regexp = CaseApi.CASE_ID) String caseId) {
		return this.service.reviewCase(Actor.current(), caseId);
	}

	@PostMapping("/v1/review-queue/{caseId}/reviews")
	@PreAuthorize("hasRole('CLINICIAN') and hasAuthority('SCOPE_review:write')")
	ResponseEntity<CaseApi.ClinicianReview> review(@PathVariable @Pattern(regexp = CaseApi.CASE_ID) String caseId,
			@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody CaseApi.ClinicianReviewCreate request) {
		var result = this.service.submit(Actor.current(), key, caseId, request);
		return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
	}

}
