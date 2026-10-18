package org.mentalwellness.api.shared.web;

import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdTests {

	@ParameterizedTest
	@ValueSource(strings = { "6f1c2b8e-3d4a-4f5b-9c6d-7e8f9a0b1c2d", "6F1C2B8E-3D4A-4F5B-9C6D-7E8F9A0B1C2D" })
	void acceptsCanonicalUuids(String value) {
		assertThat(CorrelationId.parse(value)).contains(UUID.fromString(value));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "1-1-1-1-1", "6f1c2b8e3d4a4f5b9c6d7e8f9a0b1c2d", "<script>", "6f1c2b8e-3d4a-4f5b-9c6d-7e8f9a0b1c2d\n" })
	void rejectsAnythingElse(String value) {
		assertThat(CorrelationId.parse(value)).isEmpty();
	}

}
