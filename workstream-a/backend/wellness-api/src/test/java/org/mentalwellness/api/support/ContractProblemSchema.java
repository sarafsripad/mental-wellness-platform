package org.mentalwellness.api.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code Problem} schema from {@code contracts/openapi/wellness-api.v1.yaml}, read from the repo so
 * the implementation is checked against the real contract rather than a copy.
 */
public final class ContractProblemSchema {

	private static final Map<String, Object> PROBLEM = load();

	private ContractProblemSchema() {
	}

	public static Path contractsDir() {
		String dir = System.getProperty("wellness.contracts.dir");
		assertThat(dir).as("system property wellness.contracts.dir (set by build.gradle.kts)").isNotBlank();
		return Path.of(dir);
	}

	@SuppressWarnings("unchecked")
	public static Set<String> properties() {
		return ((Map<String, Object>) PROBLEM.get("properties")).keySet();
	}

	@SuppressWarnings("unchecked")
	public static List<String> required() {
		return (List<String>) PROBLEM.get("required");
	}

	@SuppressWarnings("unchecked")
	public static List<String> codes() {
		Map<String, Object> props = (Map<String, Object>) PROBLEM.get("properties");
		return (List<String>) ((Map<String, Object>) props.get("code")).get("enum");
	}

	/** Asserts a response body is a contract-conforming problem with the expected code and status. */
	public static void assertConforms(Map<String, Object> body, String expectedCode, int expectedStatus) {
		assertThat(body.keySet()).as("problem fields allowed by the contract").isSubsetOf(properties());
		assertThat(body.keySet()).as("required problem fields").containsAll(required());
		assertThat(codes()).as("code is in the contract enum").contains((String) body.get("code"));
		assertThat(body.get("code")).isEqualTo(expectedCode);
		assertThat(body.get("status")).isEqualTo(expectedStatus);
		assertThat(UUID.fromString((String) body.get("correlation_id"))).isNotNull();
		assertThat(((String) body.getOrDefault("detail", "")).length()).isLessThanOrEqualTo(300);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> load() {
		Path file = contractsDir().resolve("openapi/wellness-api.v1.yaml");
		try (InputStream in = Files.newInputStream(file)) {
			Map<String, Object> doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
			Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("schemas");
			return (Map<String, Object>) schemas.get("Problem");
		}
		catch (IOException ex) {
			throw new IllegalStateException("Cannot read " + file, ex);
		}
	}

}
