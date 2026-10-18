package org.mentalwellness.api.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.test.web.servlet.MvcResult;

/**
 * Saves real requests and responses from integration tests to {@code build/contract-samples/}.
 * {@code tests/contracts/test_backend_samples.py} then validates every sample against the
 * OpenAPI schema of its operation and status, with the same validator used for the contract.
 */
public final class ContractSamples {

	private static final Path DIR = Path.of("build", "contract-samples");
	private static final AtomicInteger COUNTER = new AtomicInteger();
	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private ContractSamples() {
	}

	/** Records the request body (if any) and the response of one call to {@code operationId}. */
	public static void record(String operationId, String requestBody, MvcResult result) {
		try {
			int status = result.getResponse().getStatus();
			String responseBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
			Map<String, Object> sample = new LinkedHashMap<>();
			sample.put("file", "wellness-api.v1.yaml");
			sample.put("operation_id", operationId);
			sample.put("status", String.valueOf(status));
			sample.put("content_type", String.valueOf(result.getResponse().getContentType()));
			sample.put("request", requestBody == null ? null : JSON.readTree(requestBody));
			sample.put("response", responseBody.isEmpty() ? null : JSON.readTree(responseBody));
			Files.createDirectories(DIR);
			Files.writeString(DIR.resolve("%s-%d-%04d.json".formatted(operationId, status, COUNTER.incrementAndGet())),
					JSON.writerWithDefaultPrettyPrinter().writeValueAsString(sample));
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not record contract sample", ex);
		}
	}

}
