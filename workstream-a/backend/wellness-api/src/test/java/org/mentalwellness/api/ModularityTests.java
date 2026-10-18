package org.mentalwellness.api;

import org.junit.jupiter.api.Test;

import org.springframework.modulith.core.ApplicationModules;

import static org.assertj.core.api.Assertions.assertThat;

/** Module boundaries (ADR-004): no cycles, no access to another module's internal types. */
class ModularityTests {

	static final ApplicationModules MODULES = ApplicationModules.of(WellnessApiApplication.class);

	@Test
	void moduleBoundariesAreRespected() {
		MODULES.verify();
	}

	@Test
	void expectedModulesExist() {
		assertThat(MODULES.stream().map(m -> m.getIdentifier().toString()))
			.contains("audit", "cases", "consent", "identity", "processing", "researchexport", "shared", "voicesession");
	}

}
