package org.mentalwellness.api.support;

import org.mentalwellness.api.shared.security.JwtValidation;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Swaps only the key source: tokens are verified against the test key instead of Keycloak's JWKS,
 * with the production validators (issuer, audience, expiry) and the RS256-only rule.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfiguration {

	@Bean
	@Primary
	JwtDecoder testJwtDecoder() {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(TestTokens.publicKey())
			.signatureAlgorithm(SignatureAlgorithm.RS256)
			.build();
		decoder.setJwtValidator(JwtValidation.validator(TestTokens.ISSUER, TestTokens.AUDIENCE));
		return decoder;
	}

}
