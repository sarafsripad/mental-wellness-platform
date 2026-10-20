package org.mentalwellness.api.integration.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mentalwellness.api.IntegrationTest;
import org.mentalwellness.api.support.ApiDriver;
import org.mentalwellness.api.support.Fixtures;
import org.mentalwellness.api.support.Ids;
import org.mentalwellness.api.support.TestTokens;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

/** HMIS aggregate report (ADR-015): counts only, small cells suppressed, contract-valid. */
@IntegrationTest
class HmisReportTests {

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired HmisReports reports;

	@Test
	void suppressionReplacesOneToFour() {
		ObjectNode n = this.json.createObjectNode();
		HmisReports.count(n, "zero", 0);
		HmisReports.count(n, "one", 1);
		HmisReports.count(n, "four", 4);
		HmisReports.count(n, "five", 5);
		assertThat(n.toString()).isEqualTo("{\"zero\":0,\"one\":\"<5\",\"four\":\"<5\",\"five\":5}");
	}

	@Test
	void reportHoldsCountsOnlyAndMatchesItsContract(@TempDir Path dir) throws Exception {
		ApiDriver driver = new ApiDriver(this.mvc, this.json);
		TestTokens.Builder asha = TestTokens.token().roles("ASHA_WORKER").scope(Fixtures.USER_SCOPES);
		String lastCase = null;
		for (int i = 0; i < 6; i++) {
			String ppt = Ids.of("ppt");
			String ce = Ids.of("ce");
			Instant at = Instant.now().minusSeconds(600);
			assertThat(driver.postJson("/v1/consent-events", asha, Fixtures.ashaConsent(ce, ppt, "hi-IN", at)).getResponse().getStatus()).isEqualTo(201);
			lastCase = Ids.of("case");
			Map<String, Object> c = Fixtures.caseCreate(lastCase, ppt, ce, at);
			c.put("age_band", "AGE_60_PLUS");
			c.put("gender", "OTHER");
			assertThat(driver.postJson("/v1/cases", asha, c).getResponse().getStatus()).isEqualTo(201);
		}
		HmisReports.Result result = this.reports.run(YearMonth.now(ZoneOffset.UTC), dir);
		String text = Files.readString(result.file());
		JsonNode report = this.json.readTree(text);
		assertThat(result.delivery()).isEqualTo("NOT_CONFIGURED");
		assertThat(text).doesNotContain(lastCase).doesNotContain("ppt_").doesNotContain("ce_").doesNotContain("case_");
		JsonNode group = null;
		for (JsonNode g : report.get("visits_by_group")) {
			if (g.get("age_band").asString().equals("AGE_60_PLUS") && g.get("gender").asString().equals("OTHER")) {
				group = g;
			}
		}
		assertThat(group).isNotNull();
		assertThat(group.get("visits").asLong()).isGreaterThanOrEqualTo(6);
		assertThat(group.get("urgent_review_flags").isIntegralNumber() ? group.get("urgent_review_flags").asLong() : -1).isZero();
	}

}
