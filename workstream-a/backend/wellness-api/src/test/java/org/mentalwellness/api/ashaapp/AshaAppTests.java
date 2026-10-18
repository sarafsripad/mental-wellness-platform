package org.mentalwellness.api.ashaapp;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mentalwellness.api.IntegrationTest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class AshaAppTests {

	/** By default (every environment except run-local.sh) the app does not exist. */
	@Nested
	@IntegrationTest
	class Disabled {

		@Autowired
		MockMvc mvc;

		@Test
		void appIsNotServedByDefault() throws Exception {
			for (String path : new String[] { "/asha/", "/asha/index.html", "/asha/app.js", "/asha/i18n.js" }) {
				assertThat(this.mvc.perform(get(path)).andReturn().getResponse().getStatus()).as(path).isEqualTo(401);
			}
		}

	}

	@Nested
	@IntegrationTest
	@TestPropertySource(properties = "wellness.asha-app.enabled=true")
	class Enabled {

		@Autowired
		MockMvc mvc;

		@Test
		void filesAreServedWithAStrictPolicy() throws Exception {
			MvcResult page = this.mvc.perform(get("/asha/index.html")).andReturn();
			assertThat(page.getResponse().getStatus()).isEqualTo(200);
			assertThat(page.getResponse().getContentAsString()).contains("ASHA Care").contains("SYNTHETIC TEST DATA ONLY")
				.doesNotContain("style=").doesNotContain("<script>");
			String csp = page.getResponse().getHeader("Content-Security-Policy");
			assertThat(csp).contains("default-src 'none'").contains("script-src 'self'").contains("style-src 'self'")
				.contains("frame-ancestors 'none'").contains("connect-src 'self' http://localhost:8081")
				.doesNotContain("unsafe-inline").doesNotContain("unsafe-eval");
			assertThat(page.getResponse().getHeader("Cache-Control")).contains("no-store");
			assertThat(page.getResponse().getHeader("Referrer-Policy")).isEqualTo("no-referrer");
			for (String file : new String[] { "/asha/app.js", "/asha/i18n.js", "/asha/app.css" }) {
				assertThat(this.mvc.perform(get(file)).andReturn().getResponse().getStatus()).as(file).isEqualTo(200);
			}
		}

		@Test
		void theScriptNeverUsesHtmlInjectionOrPersistentStorage() throws Exception {
			for (String file : new String[] { "/asha/app.js", "/asha/i18n.js" }) {
				String js = this.mvc.perform(get(file)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
				assertThat(js).as(file).doesNotContain("innerHTML").doesNotContain("outerHTML")
					.doesNotContain("insertAdjacentHTML").doesNotContain("document.write").doesNotContain("eval(")
					.doesNotContain("localStorage").doesNotContain("indexedDB").doesNotContain("console.log");
			}
		}

		@Test
		void rootForwardsToThePage() throws Exception {
			assertThat(this.mvc.perform(get("/asha/")).andReturn().getResponse().getForwardedUrl()).isEqualTo("/asha/index.html");
		}

		@Test
		void theApiStillRequiresATokenWhileTheAppIsOn() throws Exception {
			assertThat(this.mvc.perform(get("/v1/cases")).andReturn().getResponse().getStatus()).isEqualTo(401);
		}

		@Test
		void refusesToRunWhereParticipantDataIsAccepted() {
			assertThatThrownBy(() -> new AshaAppConfig(Set.of("SYNTHETIC", "PARTICIPANT"), "http://localhost:8081"))
				.isInstanceOf(IllegalStateException.class);
		}

	}

}
