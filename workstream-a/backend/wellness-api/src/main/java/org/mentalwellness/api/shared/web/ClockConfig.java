package org.mentalwellness.api.shared.web;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** A single UTC clock, so time-dependent rules can be tested with a fixed clock. */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
