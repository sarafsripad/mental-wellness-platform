package org.mentalwellness.api.shared.security;

import java.util.List;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

/** Token validation rules, kept in one place so tests exercise exactly what production uses. */
public final class JwtValidation {

	private JwtValidation() {
	}

	/** Expiry and not-before (default clock skew), issuer, and audience. */
	public static OAuth2TokenValidator<Jwt> validator(String issuer, String audience) {
		return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
				audienceValidator(audience));
	}

	static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
		OAuth2Error error = new OAuth2Error("invalid_token", "The required audience is missing", null);
		return jwt -> {
			List<String> aud = jwt.getAudience();
			return aud != null && aud.contains(audience) ? OAuth2TokenValidatorResult.success()
					: OAuth2TokenValidatorResult.failure(error);
		};
	}

}
