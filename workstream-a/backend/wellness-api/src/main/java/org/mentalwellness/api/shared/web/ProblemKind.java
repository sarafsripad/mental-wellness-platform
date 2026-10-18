package org.mentalwellness.api.shared.web;

import org.springframework.http.HttpStatus;

/**
 * A problem type from one of the contracts: {@link ProblemCode} (public API) or
 * {@link WorkerProblemCode} (internal worker API). Titles and details are fixed text.
 */
public interface ProblemKind {

	String name();

	HttpStatus status();

	String title();

	String detail();

	default String type() {
		return "https://mental-wellness-platform/problems/" + name().toLowerCase().replace('_', '-');
	}

}
