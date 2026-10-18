package org.mentalwellness.api.shared.web;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.mentalwellness.api.support.ContractProblemSchema;

import static org.assertj.core.api.Assertions.assertThat;

/** The implementation's problem codes and body fields must match the v1 contract exactly. */
class ProblemCodeContractTests {

	@Test
	void problemCodesMatchTheContract() {
		assertThat(Arrays.stream(ProblemCode.values()).map(Enum::name))
			.containsExactlyInAnyOrderElementsOf(ContractProblemSchema.codes());
	}

	@Test
	void problemRecordFieldsAreAllowedByTheContract() {
		assertThat(ContractProblemSchema.properties())
			.containsExactlyInAnyOrder("type", "title", "status", "code", "detail", "correlation_id", "errors");
	}

	@Test
	void detailsAreShortFixedTextWithinTheContractLimit() {
		for (ProblemCode code : ProblemCode.values()) {
			assertThat(code.detail()).hasSizeLessThanOrEqualTo(300);
			assertThat(code.type()).matches("https://mental-wellness-platform/problems/[a-z-]+");
		}
	}

}
