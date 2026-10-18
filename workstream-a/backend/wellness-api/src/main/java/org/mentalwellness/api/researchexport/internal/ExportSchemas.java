package org.mentalwellness.api.researchexport.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

/** The feature-record and manifest contracts, packaged from contracts/ at build time. */
@Component
class ExportSchemas {

	private final Schema record = load("/contracts/feature-record.schema.json");

	private final Schema manifest = load("/contracts/export-manifest.schema.json");

	ExportSchemas() throws IOException {
	}

	List<String> recordErrors(JsonNode node) {
		return this.record.validate(node).stream().map(e -> e.getInstanceLocation().toString()).toList();
	}

	List<String> manifestErrors(JsonNode node) {
		return this.manifest.validate(node).stream().map(e -> e.getInstanceLocation().toString()).toList();
	}

	private static Schema load(String resource) {
		try (InputStream in = ExportSchemas.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException("Contract missing from the classpath: " + resource);
			}
			return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
