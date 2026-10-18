package org.mentalwellness.api.shared.web;

import org.springframework.http.HttpStatus;

/** Problem codes of contracts/openapi/processing-worker.internal.v1.yaml ({@code Problem.code}). */
public enum WorkerProblemCode implements ProblemKind {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed", "One or more fields or headers failed validation."),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required", "A valid client-credentials token is required."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied", "The token lacks the processing:worker scope."),
	LEASE_EXPIRED(HttpStatus.CONFLICT, "Lease expired", "The job lease has expired. Discard the audio and any output."),
	LEASE_INVALID(HttpStatus.CONFLICT, "Lease invalid", "The lease token or worker ID does not match this job."),
	JOB_NOT_RUNNING(HttpStatus.CONFLICT, "Job not running", "The job is no longer running. Discard the audio and any output."),
	CONSENT_WITHDRAWN(HttpStatus.CONFLICT, "Consent withdrawn", "Consent was withdrawn. Discard the audio and any output."),
	SCHEMA_INVALID(HttpStatus.BAD_REQUEST, "Feature record invalid", "The feature record failed schema validation."),
	JOB_MISMATCH(HttpStatus.BAD_REQUEST, "Feature record does not match job", "The record's IDs or audio properties do not match the job."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "An unexpected error occurred.");

	private final HttpStatus status;

	private final String title;

	private final String detail;

	WorkerProblemCode(HttpStatus status, String title, String detail) {
		this.status = status;
		this.title = title;
		this.detail = detail;
	}

	@Override
	public HttpStatus status() {
		return this.status;
	}

	@Override
	public String title() {
		return this.title;
	}

	@Override
	public String detail() {
		return this.detail;
	}

}
