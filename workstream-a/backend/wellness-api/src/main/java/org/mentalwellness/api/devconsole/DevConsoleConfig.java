package org.mentalwellness.api.devconsole;

import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the local test console. Off unless {@code wellness.dev-console.enabled=true}; when off,
 * nothing is registered and {@code /dev-console/**} falls under the API's normal rules (401).
 *
 * <p>Safeguards: refuses to start if the environment accepts PARTICIPANT data; the page's own
 * security chain only serves static files (all API calls still need a bearer token); strict CSP
 * with no inline script, no framing and no third-party origins except the local Keycloak.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty("wellness.dev-console.enabled")
class DevConsoleConfig implements WebMvcConfigurer {

	static final String PATH = "/dev-console/";

	private final String keycloak;

	DevConsoleConfig(@Value("${wellness.data.allowed-origins}") Set<String> allowedOrigins,
			@Value("${wellness.dev-console.keycloak-origin:http://localhost:8081}") String keycloak) {
		if (allowedOrigins.contains("PARTICIPANT")) {
			throw new IllegalStateException("The dev console may not run in an environment that accepts PARTICIPANT data");
		}
		this.keycloak = keycloak;
	}

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler(PATH + "**")
			.addResourceLocations("classpath:/dev-console/")
			.setCacheControl(CacheControl.noStore());
	}

	@Override
	public void addViewControllers(ViewControllerRegistry registry) {
		registry.addViewController(PATH).setViewName("forward:" + PATH + "index.html");
		registry.addRedirectViewController("/dev-console", PATH);
	}

	/** Static files only; evaluated before the API chain. */
	@Bean
	@Order(1)
	SecurityFilterChain devConsoleSecurity(HttpSecurity http) throws Exception {
		String csp = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; media-src 'self' blob:; "
				+ "connect-src 'self' " + this.keycloak + "; form-action " + this.keycloak + "; base-uri 'none'; "
				+ "frame-ancestors 'none'";
		http.securityMatcher("/dev-console", "/dev-console/**")
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.csrf(csrf -> csrf.disable())
			.requestCache(cache -> cache.disable())
			.headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives(csp))
				.referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
		return http.build();
	}

}
