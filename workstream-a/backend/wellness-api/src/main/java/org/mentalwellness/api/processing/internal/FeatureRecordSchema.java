package org.mentalwellness.api.processing.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

/**
 * Validates feature records against contracts/voice-features/feature-record.schema.json, which the
 * build packages from the repository's contracts directory (no hand-maintained copy).
 */
@Component
class FeatureRecordSchema {

	static final String RESOURCE = "/contracts/feature-record.schema.json";

	private final Schema schema;

	FeatureRecordSchema() throws IOException {
		try (InputStream in = FeatureRecordSchema.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("Feature-record schema missing from the classpath: " + RESOURCE);
			}
			this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
		}
	}

	/** Validation errors as JSON Pointers to the failing instance locations (never values). */
	List<String> errors(JsonNode record) {
		List<Error> errors = this.schema.validate(record);
		return errors.stream().map(e -> pointer(e.getInstanceLocation().toString())).distinct().toList();
	}

	private static String pointer(String location) {
		// The validator reports locations like "$.features[0].name"; convert to a JSON Pointer.
		String p = location.replaceFirst("^\\$", "").replaceAll("\\[(\\d+)\\]", ".$1").replace('.', '/');
		return p.isEmpty() ? "/" : p;
	}

}
