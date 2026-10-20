package org.mentalwellness.api.shared.web;

import org.springframework.http.HttpStatus;

/**
 * Problem codes from {@code contracts/openapi/wellness-api.v1.yaml} ({@code Problem.code}). Titles and
 * details are fixed, non-identifying text: they never include request values. A contract test checks
 * that this enum and the contract agree.
 */
public enum ProblemCode implements ProblemKind {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed", "One or more fields failed validation."),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required", "A valid bearer access token is required."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied", "The caller is not permitted to perform this action."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "Not found", "The resource does not exist or is not visible to the caller."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", "This method is not supported for the resource."),
	NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Not acceptable", "The requested response media type is not available."),
	IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Idempotency conflict", "The idempotency key was already used with a different request."),
	RESOURCE_CONFLICT(HttpStatus.CONFLICT, "Resource conflict", "The request conflicts with the current state of the resource."),
	CONSENT_NOT_ACTIVE(HttpStatus.UNPROCESSABLE_CONTENT, "Consent not active", "The required consent scope is not in effect for this participant."),
	CONSENT_PARTICIPANT_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "Consent mismatch", "The consent event does not belong to this participant."),
	CONSENT_SCOPE_INCOMPLETE(HttpStatus.UNPROCESSABLE_CONTENT, "Consent scopes incomplete", "Consent scope decisions are missing or inconsistent."),
	ADULT_CONFIRMATION_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT, "Adult confirmation required", "Only adult participants can be enrolled in this version."),
	DATA_ORIGIN_NOT_PERMITTED(HttpStatus.UNPROCESSABLE_CONTENT, "Data origin not permitted", "This environment does not accept data of this origin."),
	CHECKSUM_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "Checksum mismatch", "The uploaded content does not match the registered checksum or size."),
	RECORDING_NOT_UPLOADED(HttpStatus.UNPROCESSABLE_CONTENT, "Recording not uploaded", "The recording content has not been uploaded yet."),
	PARTNER_NOT_ENABLED(HttpStatus.UNPROCESSABLE_CONTENT, "Partner not enabled", "This partner programme is not enabled in this environment."),
	CLINICAL_NOTES_DISABLED(HttpStatus.UNPROCESSABLE_CONTENT, "Clinical notes disabled", "Free-text clinical notes are not enabled in this environment."),
	PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Payload too large", "The request body exceeds the allowed size."),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type", "The request content type is not supported."),
	RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests", "Rate limit exceeded; retry later."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "An unexpected error occurred. Quote the correlation ID when reporting it.");

	private final HttpStatus status;

	private final String title;

	private final String detail;

	ProblemCode(HttpStatus status, String title, String detail) {
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
