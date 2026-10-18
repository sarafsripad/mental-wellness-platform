package org.mentalwellness.api.shared.security;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class JwtValidationTests {

	private static final String ISSUER = "http://localhost:8081/realms/wellness-local";

	private final OAuth2TokenValidator<Jwt> validator = JwtValidation.validator(ISSUER, "wellness-api");

	@Test
	void acceptsMatchingIssuerAudienceAndLifetime() {
		assertThat(this.validator.validate(jwt(ISSUER, List.of("wellness-api"), Instant.now().plusSeconds(60))).hasErrors())
			.isFalse();
	}

	@Test
	void acceptsWhenAudienceIsOneOfSeveral() {
		assertThat(this.validator.validate(jwt(ISSUER, List.of("account", "wellness-api"), Instant.now().plusSeconds(60)))
			.hasErrors()).isFalse();
	}

	@Test
	void rejectsMissingOrWrongAudience() {
		assertThat(this.validator.validate(jwt(ISSUER, List.of("account"), Instant.now().plusSeconds(60))).hasErrors()).isTrue();
		assertThat(this.validator.validate(jwt(ISSUER, null, Instant.now().plusSeconds(60))).hasErrors()).isTrue();
	}

	@Test
	void rejectsWrongIssuer() {
		assertThat(this.validator.validate(jwt("http://evil.example/realms/wellness-local", List.of("wellness-api"),
				Instant.now().plusSeconds(60))).hasErrors()).isTrue();
	}

	@Test
	void rejectsExpiredBeyondClockSkew() {
		assertThat(this.validator.validate(jwt(ISSUER, List.of("wellness-api"), Instant.now().minusSeconds(120))).hasErrors())
			.isTrue();
	}

	private static Jwt jwt(String issuer, List<String> audience, Instant expiresAt) {
		Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").issuer(issuer).subject("s")
			.issuedAt(expiresAt.minusSeconds(300)).expiresAt(expiresAt);
		if (audience != null) {
			b.audience(audience);
		}
		return b.build();
	}

}
