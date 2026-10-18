package org.mentalwellness.api.devconsole;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mentalwellness.api.IntegrationTest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@IntegrationTest
@TestPropertySource(properties = "wellness.dev-console.enabled=true")
class DevConsoleEnabledTests {

	@Autowired
	MockMvc mvc;

	@Test
	void pageAndScriptAreServedWithAStrictPolicy() throws Exception {
		MvcResult page = this.mvc.perform(get("/dev-console/index.html")).andReturn();
		assertThat(page.getResponse().getStatus()).isEqualTo(200);
		assertThat(page.getResponse().getContentAsString()).contains("Wellness Test Console").contains("synthetic data only");
		String csp = page.getResponse().getHeader("Content-Security-Policy");
		assertThat(csp).contains("default-src 'none'").contains("script-src 'self'").contains("frame-ancestors 'none'")
			.contains("connect-src 'self' http://localhost:8081").doesNotContain("unsafe-inline").doesNotContain("unsafe-eval");
		assertThat(page.getResponse().getHeader("Cache-Control")).contains("no-store");
		assertThat(page.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
		assertThat(this.mvc.perform(get("/dev-console/console.js")).andReturn().getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void rootForwardsToThePage() throws Exception {
		assertThat(this.mvc.perform(get("/dev-console/")).andReturn().getResponse().getForwardedUrl())
			.isEqualTo("/dev-console/index.html");
	}

	@Test
	void theApiStillRequiresATokenWhileTheConsoleIsOn() throws Exception {
		assertThat(this.mvc.perform(get("/v1/voice-sessions/sess_" + "0".repeat(32))).andReturn().getResponse().getStatus())
			.isEqualTo(401);
	}

	@Test
	void thePageCannotBreakOutOfItsDirectory() throws Exception {
		int status = this.mvc.perform(get("/dev-console/../application.properties")).andReturn().getResponse().getStatus();
		assertThat(status).isIn(400, 401, 404);
	}

	@Test
	void refusesToRunWhereParticipantDataIsAccepted() {
		assertThatThrownBy(() -> new DevConsoleConfig(Set.of("SYNTHETIC", "PARTICIPANT"), "http://localhost:8081"))
			.isInstanceOf(IllegalStateException.class);
	}

}
