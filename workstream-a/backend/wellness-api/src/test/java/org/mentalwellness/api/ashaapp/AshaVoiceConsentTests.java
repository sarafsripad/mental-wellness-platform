package org.mentalwellness.api.ashaapp;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mentalwellness.api.IntegrationTest;
import org.mentalwellness.api.support.ApiDriver;
import org.mentalwellness.api.support.ContractProblemSchema;
import org.mentalwellness.api.support.Fixtures;
import org.mentalwellness.api.support.Ids;
import org.mentalwellness.api.support.TestTokens;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

/** The ASHA consent form 0.3.0 asks about voice recording separately (ADR-017). */
@IntegrationTest
class AshaVoiceConsentTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	ObjectMapper json;

	ApiDriver driver;

	@BeforeEach
	void driver() {
		this.driver = new ApiDriver(this.mvc, this.json);
	}

	final TestTokens.Builder asha = TestTokens.token().roles("ASHA_WORKER").scope(Fixtures.USER_SCOPES);

	@Test
	void voiceSessionsNeedTheAudioQuestionAnsweredYes() throws Exception {
		Instant consentedAt = Instant.now().minusSeconds(120);
		String yes = Ids.of("ppt");
		String yesEvent = Ids.of("ce");
		assertThat(status(this.driver.postJson("/v1/consent-events", this.asha,
				Fixtures.ashaConsentWithAudio(yesEvent, yes, consentedAt, true)))).isEqualTo(201);
		assertThat(status(this.driver.postJson("/v1/voice-sessions", this.asha,
				Fixtures.session(Ids.of("sess"), yes, yesEvent, Instant.now().minusSeconds(30), "SYNTHETIC")))).isEqualTo(201);

		String no = Ids.of("ppt");
		String noEvent = Ids.of("ce");
		assertThat(status(this.driver.postJson("/v1/consent-events", this.asha,
				Fixtures.ashaConsentWithAudio(noEvent, no, consentedAt, false)))).isEqualTo(201);
		ContractProblemSchema.assertConforms(this.driver.body(this.driver.postJson("/v1/voice-sessions", this.asha,
				Fixtures.session(Ids.of("sess"), no, noEvent, Instant.now().minusSeconds(30), "SYNTHETIC"))), "CONSENT_NOT_ACTIVE", 422);
	}

	@Test
	void theAudioQuestionMustBeAnswered() throws Exception {
		Map<String, Object> skipped = Fixtures.ashaConsentWithAudio(Ids.of("ce"), Ids.of("ppt"), Instant.now(), true);
		skipped.put("scope_decisions", List.of(Map.of("scope", "SCREENING_PARTICIPATION", "granted", true),
				Map.of("scope", "EXTERNAL_REFERRAL_SHARING", "granted", false)));
		ContractProblemSchema.assertConforms(this.driver.body(this.driver.postJson("/v1/consent-events", this.asha, skipped)),
				"CONSENT_SCOPE_INCOMPLETE", 422);
	}

	private static int status(MvcResult r) {
		return r.getResponse().getStatus();
	}

}
