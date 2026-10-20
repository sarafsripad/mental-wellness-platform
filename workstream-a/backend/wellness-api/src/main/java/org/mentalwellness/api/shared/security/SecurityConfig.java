package org.mentalwellness.api.shared.security;

import java.util.List;

import jakarta.servlet.DispatcherType;
import org.mentalwellness.api.audit.AuditLog;
import org.mentalwellness.api.shared.web.ProblemResponses;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless OIDC bearer-token security (ADR-011). Every request needs a valid token except the
 * health probes, which report status only. There is no session, form login, basic auth or CSRF
 * (no cookies are used). Authorization by role and participant assignment is done per operation,
 * starting in slice step S4.
 */
@Configuration
// Only for the web server; the one-shot export (run-export.sh) runs without one.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
class SecurityConfig {

	/** OAuth scopes used by the contracts (wellness-api.v1.yaml and processing-worker.internal.v1.yaml). */
	static final List<String> API_SCOPES = List.of("consent:read", "consent:write", "voice:read", "voice:write",
			"processing:read", "processing:write", "processing:worker", "cases:read", "cases:write", "review:read",
			"review:write", "partner:events", "careteam:read", "careteam:write", "careteam:admin", "encounters:read", "encounters:write",
			"identity:lookup");

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, ProblemResponses problems, AuditLog auditLog,
			JwtProperties jwtProperties) throws Exception {
		SecurityProblemHandlers handlers = new SecurityProblemHandlers(problems, auditLog);
		http.authorizeHttpRequests(auth -> auth
				.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
				.requestMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
				.anyRequest().authenticated())
			.oauth2ResourceServer(oauth -> oauth
				.jwt(jwt -> jwt.jwtAuthenticationConverter(new RealmRoleConverter()))
				// RFC 9728 metadata at /.well-known/oauth-protected-resource: describe this API accurately.
				.protectedResourceMetadata(metadata -> metadata.protectedResourceMetadataCustomizer(m -> m
					.resourceName("Mental Wellness Platform API")
					.authorizationServer(jwtProperties.issuer())
					.scopes(scopes -> scopes.addAll(API_SCOPES))
					.tlsClientCertificateBoundAccessTokens(false)))
				.authenticationEntryPoint(handlers)
				.accessDeniedHandler(handlers))
			.exceptionHandling(ex -> ex.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.csrf(csrf -> csrf.disable())
			.formLogin(form -> form.disable())
			.httpBasic(basic -> basic.disable())
			.logout(logout -> logout.disable())
			.requestCache(cache -> cache.disable());
		return http.build();
	}

	@Bean
	JwtDecoder jwtDecoder(JwtProperties properties) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri().toString())
			.jwsAlgorithm(SignatureAlgorithm.RS256)
			.build();
		decoder.setJwtValidator(JwtValidation.validator(properties.issuer(), properties.audience()));
		return decoder;
	}

}
