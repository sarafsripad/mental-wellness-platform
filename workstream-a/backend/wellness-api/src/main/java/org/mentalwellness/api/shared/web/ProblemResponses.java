package org.mentalwellness.api.shared.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Writes problem bodies directly to a servlet response (used outside Spring MVC, e.g. by security). */
@Component
public class ProblemResponses {

	public static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

	private final ObjectMapper objectMapper;

	public ProblemResponses(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public void write(HttpServletResponse response, ProblemCode code) throws IOException {
		UUID correlationId = CorrelationId.current().orElseGet(UUID::randomUUID);
		response.setStatus(code.status().value());
		response.setContentType(PROBLEM_JSON.toString());
		this.objectMapper.writeValue(response.getOutputStream(), ApiProblem.of(code, correlationId));
	}

}
