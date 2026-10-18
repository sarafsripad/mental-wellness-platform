package org.mentalwellness.api.shared.web;

import java.util.UUID;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces Spring Boot's default /error page, so errors raised outside Spring MVC (for example in
 * servlet filters) still return a contract problem body instead of a page with paths and timestamps.
 */
@RestController
public class ProblemErrorController implements ErrorController {

	@RequestMapping("/error")
	ResponseEntity<ApiProblem> error(HttpServletRequest request) {
		Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
		ProblemCode code = switch (status instanceof Integer s ? s : 500) {
			case 400 -> ProblemCode.VALIDATION_FAILED;
			case 401 -> ProblemCode.UNAUTHENTICATED;
			case 403 -> ProblemCode.FORBIDDEN;
			case 404 -> ProblemCode.NOT_FOUND;
			case 405 -> ProblemCode.METHOD_NOT_ALLOWED;
			case 406 -> ProblemCode.NOT_ACCEPTABLE;
			case 413 -> ProblemCode.PAYLOAD_TOO_LARGE;
			case 415 -> ProblemCode.UNSUPPORTED_MEDIA_TYPE;
			case 429 -> ProblemCode.RATE_LIMITED;
			default -> ProblemCode.INTERNAL_ERROR;
		};
		UUID correlationId = CorrelationId.current().orElseGet(UUID::randomUUID);
		return ResponseEntity.status(code.status())
			.contentType(ProblemResponses.PROBLEM_JSON)
			.body(ApiProblem.of(code, correlationId));
	}

}
