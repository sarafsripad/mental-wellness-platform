package org.mentalwellness.api.programme;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mentalwellness.api.IntegrationTest;
import org.mentalwellness.api.support.ContractSamples;
import org.mentalwellness.api.support.Fixtures;
import org.mentalwellness.api.support.TestTokens;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Programme support contacts (API 1.3, ADR-015): configuration only, no participant data. */
@IntegrationTest
class SupportContactsTests {

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;

	MvcResult call(TestTokens.Builder who) throws Exception {
		var req = get("/v1/programme/support-contacts");
		if (who != null) {
			req.header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build());
		}
		MvcResult r = this.mvc.perform(req).andReturn();
		ContractSamples.record("listSupportContacts", null, r);
		return r;
	}

	@Test
	void workersAndCliniciansSeeTheConfiguredContactsMarkedUnverified() throws Exception {
		for (TestTokens.Builder who : List.of(TestTokens.token().roles("ASHA_WORKER").scope(Fixtures.USER_SCOPES),
				TestTokens.token().roles("CLINICIAN").scope(Fixtures.CLINICIAN_SCOPES))) {
			MvcResult r = call(who);
			assertThat(r.getResponse().getStatus()).isEqualTo(200);
			Map<String, List<Map<String, Object>>> body = this.json.readValue(r.getResponse().getContentAsString(),
					new TypeReference<Map<String, List<Map<String, Object>>>>() {
					});
			assertThat(body.get("contacts")).first()
				.satisfies(c -> assertThat(c).containsEntry("contact_id", "tele-manas")
					.containsEntry("dial_number", "14416")
					.containsEntry("tel_uri", "tel:14416")
					.containsEntry("verified", false)
					.containsEntry("verified_by", null));
			assertThat(body.get("contacts").get(1)).containsEntry("tel_uri", "tel:18008914416");
		}
	}

	@Test
	void otherCallersAreRefused() throws Exception {
		assertThat(call(null).getResponse().getStatus()).isEqualTo(401);
		assertThat(call(TestTokens.token().roles("ADMIN").scope(Fixtures.USER_SCOPES)).getResponse().getStatus()).isEqualTo(403);
		assertThat(call(TestTokens.token().roles("ASHA_WORKER").scope("consent:read")).getResponse().getStatus()).isEqualTo(403);
	}

}
