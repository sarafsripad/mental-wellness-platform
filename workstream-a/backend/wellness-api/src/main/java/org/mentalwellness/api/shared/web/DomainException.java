package org.mentalwellness.api.shared.web;

import java.util.List;

/**
 * A business-rule failure that maps directly to a contract problem code. Messages are never shown
 * to clients; the problem's fixed title and detail are used instead.
 */
public class DomainException extends RuntimeException {

	private final ProblemKind code;

	private final List<ApiProblem.FieldError> errors;

	public DomainException(ProblemKind code) {
		this(code, List.of());
	}

	public DomainException(ProblemKind code, List<ApiProblem.FieldError> errors) {
		super(code.name(), null, false, false);
		this.code = code;
		this.errors = List.copyOf(errors);
	}

	public static DomainException invalid(String pointer, String rule) {
		return new DomainException(ProblemCode.VALIDATION_FAILED, List.of(new ApiProblem.FieldError(pointer, rule)));
	}

	public ProblemKind code() {
		return this.code;
	}

	public List<ApiProblem.FieldError> errors() {
		return this.errors;
	}

}
