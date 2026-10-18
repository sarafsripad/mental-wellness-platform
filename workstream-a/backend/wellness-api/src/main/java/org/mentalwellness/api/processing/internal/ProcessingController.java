package org.mentalwellness.api.processing.internal;

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

/** Public processing operations of wellness-api.v1.yaml. */
@RestController
@Validated
class ProcessingController {

	/** Suggested polling delay while a job is not finished. */
	static final String RETRY_AFTER_SECONDS = "5";

	private final ProcessingService service;

	ProcessingController(ProcessingService service) {
		this.service = service;
	}

	@PostMapping("/v1/processing-jobs")
	@PreAuthorize("hasAnyRole('ASHA_WORKER', 'ADMIN') and hasAuthority('SCOPE_processing:write')")
	ResponseEntity<ProcessingApi.ProcessingJob> submit(@RequestHeader("Idempotency-Key") UUID key,
			@RequestHeader(value = AccessReasons.HEADER, required = false) String reason,
			@Valid @RequestBody ProcessingApi.ProcessingJobCreate request) {
		Actor actor = Actor.current();
		var submitted = this.service.submit(actor, request, AccessReasons.resolve(actor, reason).orElse(null));
		if (!submitted.created()) {
			return ResponseEntity.ok(submitted.job());
		}
		return ResponseEntity.status(HttpStatus.ACCEPTED)
			.location(URI.create("/v1/processing-jobs/" + submitted.job().jobId()))
			.body(submitted.job());
	}

	@GetMapping("/v1/processing-jobs/{jobId}")
	@PreAuthorize("hasAnyRole('ASHA_WORKER', 'CLINICIAN', 'ADMIN') and hasAuthority('SCOPE_processing:read')")
	ResponseEntity<ProcessingApi.ProcessingJob> get(@PathVariable @Pattern(regexp = ProcessingApi.JOB_ID) String jobId,
			@RequestHeader(value = AccessReasons.HEADER, required = false) String reason) {
		Actor actor = Actor.current();
		ProcessingApi.ProcessingJob job = this.service.get(actor, jobId, AccessReasons.resolve(actor, reason).orElse(null));
		boolean active = job.status() == ProcessingApi.JobStatus.QUEUED || job.status() == ProcessingApi.JobStatus.RUNNING;
		return active ? ResponseEntity.ok().header("Retry-After", RETRY_AFTER_SECONDS).body(job) : ResponseEntity.ok(job);
	}

}
