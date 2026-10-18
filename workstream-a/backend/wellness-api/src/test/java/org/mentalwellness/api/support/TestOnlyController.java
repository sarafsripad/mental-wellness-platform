package org.mentalwellness.api.support;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints that exist only in tests, to exercise security and error handling. */
@RestController
public class TestOnlyController {

	public static final String SENSITIVE_MESSAGE = "sensitive-detail-that-must-not-leak-7f3a";

	@GetMapping("/test-only/ping")
	Map<String, Object> ping() {
		return Map.of("ok", true);
	}

	@GetMapping("/test-only/admin")
	@PreAuthorize("hasRole('ADMIN')")
	Map<String, Object> adminOnly() {
		return Map.of("ok", true);
	}

	@GetMapping("/test-only/boom")
	Map<String, Object> boom() {
		throw new IllegalStateException(SENSITIVE_MESSAGE);
	}

	@PostMapping("/test-only/echo")
	Map<String, Object> echo(@Valid @RequestBody EchoRequest request) {
		return Map.of("ok", true);
	}

	public record EchoRequest(
			@JsonProperty("session_id") @NotBlank @Pattern(regexp = "^sess_[a-f0-9]{32}$") String sessionId) {
	}

}
