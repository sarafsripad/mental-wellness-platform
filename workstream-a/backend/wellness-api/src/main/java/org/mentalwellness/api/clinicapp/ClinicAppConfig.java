package org.mentalwellness.api.clinicapp;

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
 * Serves the clinic app. Off unless {@code wellness.clinic-app.enabled=true} (run-local.sh);
 * when off, {@code /clinic/**} falls under the API's normal rules (401).
 *
 * <p>Same safeguards as the ASHA app: refuses to start where PARTICIPANT data is accepted, the
 * page's own chain only serves static files, and a strict CSP allows no inline script or style, no
 * framing and no third-party origin except the local Keycloak.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty("wellness.clinic-app.enabled")
class ClinicAppConfig implements WebMvcConfigurer {

	static final String PATH = "/clinic/";

	private final String keycloak;

	ClinicAppConfig(@Value("${wellness.data.allowed-origins}") Set<String> allowedOrigins,
			@Value("${wellness.clinic-app.keycloak-origin:http://localhost:8081}") String keycloak) {
		if (allowedOrigins.contains("PARTICIPANT")) {
			throw new IllegalStateException("The clinic test app may not run in an environment that accepts PARTICIPANT data");
		}
		this.keycloak = keycloak;
	}

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler(PATH + "**").addResourceLocations("classpath:/clinic/").setCacheControl(CacheControl.noStore());
	}

	@Override
	public void addViewControllers(ViewControllerRegistry registry) {
		registry.addViewController(PATH).setViewName("forward:" + PATH + "index.html");
		registry.addRedirectViewController("/clinic", PATH);
	}

	/** Static files only; evaluated before the API chain. */
	@Bean
	@Order(4)
	SecurityFilterChain clinicAppSecurity(HttpSecurity http) throws Exception {
		String csp = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
				+ "connect-src 'self' " + this.keycloak + "; form-action " + this.keycloak + "; base-uri 'none'; "
				+ "frame-ancestors 'none'";
		http.securityMatcher("/clinic", "/clinic/**")
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.csrf(csrf -> csrf.disable())
			.requestCache(cache -> cache.disable())
			.headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives(csp))
				.referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)));
		return http.build();
	}

}
