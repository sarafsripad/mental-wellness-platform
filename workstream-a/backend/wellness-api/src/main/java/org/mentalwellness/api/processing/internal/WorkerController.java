package org.mentalwellness.api.processing.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.mentalwellness.api.shared.security.Actor;
import tools.jackson.databind.JsonNode;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal worker API (processing-worker.internal.v1.yaml). Only the processing-worker service
 * account may call it. In deployed environments it must also be unreachable from the public ingress.
 */
@RestController
@Validated
@PreAuthorize("hasRole('PROCESSING_WORKER') and hasAuthority('SCOPE_processing:worker')")
class WorkerController {

	private final WorkerService service;

	WorkerController(WorkerService service) {
		this.service = service;
	}

	@PostMapping("/internal/v1/processing-jobs/claim")
	ResponseEntity<ProcessingApi.JobLease> claim(
			@RequestHeader("X-Worker-Id") @Pattern(regexp = ProcessingApi.WORKER_ID) String workerId,
			@Valid @RequestBody ProcessingApi.ClaimRequest request) {
		return this.service.claim(Actor.current(), workerId, request)
			.map(ResponseEntity::ok)
			.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@GetMapping("/internal/v1/processing-jobs/{jobId}/audio")
	ResponseEntity<byte[]> audio(@PathVariable @Pattern(regexp = ProcessingApi.JOB_ID) String jobId,
			@RequestHeader("X-Lease-Token") @NotBlank @Size(min = 32, max = 128) String leaseToken,
			@RequestHeader("X-Worker-Id") @Pattern(regexp = ProcessingApi.WORKER_ID) String workerId,
			@RequestHeader("X-Correlation-ID") String correlationId) {
		WorkerService.Audio audio = this.service.audio(Actor.current(), jobId, leaseToken, workerId);
		MediaType type = "FLAC".equals(audio.format()) ? MediaType.parseMediaType("audio/flac") : MediaType.parseMediaType("audio/wav");
		return ResponseEntity.ok().contentType(type).header("X-Content-SHA256", audio.sha256()).body(audio.content());
	}

	@PostMapping("/internal/v1/processing-jobs/{jobId}/result")
	ResponseEntity<ProcessingApi.ResultAccepted> result(@PathVariable @Pattern(regexp = ProcessingApi.JOB_ID) String jobId,
			@RequestHeader("X-Lease-Token") @NotBlank @Size(min = 32, max = 128) String leaseToken,
			@RequestHeader("X-Worker-Id") @Pattern(regexp = ProcessingApi.WORKER_ID) String workerId,
			@RequestHeader("X-Correlation-ID") String correlationId, @RequestBody JsonNode record) {
		var accepted = this.service.result(Actor.current(), jobId, leaseToken, workerId, record);
		return ResponseEntity.status(accepted.created() ? HttpStatus.CREATED : HttpStatus.OK).body(accepted.body());
	}

	@PostMapping("/internal/v1/processing-jobs/{jobId}/failure")
	ProcessingApi.FailureAccepted failure(@PathVariable @Pattern(regexp = ProcessingApi.JOB_ID) String jobId,
			@RequestHeader("X-Lease-Token") @NotBlank @Size(min = 32, max = 128) String leaseToken,
			@RequestHeader("X-Worker-Id") @Pattern(regexp = ProcessingApi.WORKER_ID) String workerId,
			@RequestHeader("X-Correlation-ID") String correlationId, @Valid @RequestBody ProcessingApi.FailureReport report) {
		return this.service.failure(Actor.current(), jobId, leaseToken, workerId, report);
	}

}
