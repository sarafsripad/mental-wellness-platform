package org.mentalwellness.api.shared.web;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RFC 9457 problem body matching the contract's {@code Problem} schema exactly. It never contains
 * submitted values, stack traces or internal identifiers.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiProblem(String type, String title, int status, String code, String detail,
		@JsonProperty("correlation_id") UUID correlationId, List<FieldError> errors) {

	/** A field-level validation failure: JSON Pointer and rule name, never the rejected value. */
	public record FieldError(String pointer, String rule) {
	}

	public static ApiProblem of(ProblemCode code, UUID correlationId) {
		return of(code, correlationId, List.of());
	}

	public static ApiProblem of(ProblemCode code, UUID correlationId, List<FieldError> errors) {
		return new ApiProblem(code.type(), code.title(), code.status().value(), code.name(), code.detail(),
				correlationId, List.copyOf(errors));
	}

}
