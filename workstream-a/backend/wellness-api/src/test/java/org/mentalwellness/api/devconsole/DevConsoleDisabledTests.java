package org.mentalwellness.api.devconsole;

import org.junit.jupiter.api.Test;
import org.mentalwellness.api.IntegrationTest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** By default (every environment except run-local.sh) the console does not exist. */
@IntegrationTest
class DevConsoleDisabledTests {

	@Autowired
	MockMvc mvc;

	@Test
	void consoleIsNotServedByDefault() throws Exception {
		for (String path : new String[] { "/dev-console/", "/dev-console/index.html", "/dev-console/console.js" }) {
			int status = this.mvc.perform(get(path)).andReturn().getResponse().getStatus();
			assertThat(status).as(path).isEqualTo(401);
		}
	}

}
