package org.mentalwellness.api.support;

import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

/**
 * Mints signed test tokens shaped like the local Keycloak realm's (issuer, audience, realm roles,
 * scopes). Keys are generated per JVM; nothing here is a real credential.
 */
public final class TestTokens {

	public static final String ISSUER = "http://localhost:8081/realms/wellness-local";
	public static final String AUDIENCE = "wellness-api";

	private static final RSAKey SIGNING_KEY = generate();
	private static final RSAKey OTHER_KEY = generate();

	private TestTokens() {
	}

	public static RSAPublicKey publicKey() {
		try {
			return SIGNING_KEY.toRSAPublicKey();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public static Builder token() {
		return new Builder();
	}

	public static final class Builder {

		private String subject = UUID.randomUUID().toString();
		private String issuer = ISSUER;
		private List<String> audience = List.of(AUDIENCE);
		private List<String> roles = List.of();
		private String scope = "";
		private Instant expiresAt = Instant.now().plusSeconds(300);
		private RSAKey key = SIGNING_KEY;

		public Builder subject(String subject) { this.subject = subject; return this; }
		public Builder issuer(String issuer) { this.issuer = issuer; return this; }
		public Builder audience(String... audience) { this.audience = List.of(audience); return this; }
		public Builder roles(String... roles) { this.roles = List.of(roles); return this; }
		public Builder scope(String scope) { this.scope = scope; return this; }
		public Builder expiresAt(Instant expiresAt) { this.expiresAt = expiresAt; return this; }
		public Builder signedWithUnknownKey() { this.key = OTHER_KEY; return this; }

		public String subject() { return this.subject; }

		private JWTClaimsSet claims() {
			Instant now = Instant.now();
			return new JWTClaimsSet.Builder().issuer(this.issuer)
				.subject(this.subject)
				.audience(this.audience)
				.issueTime(Date.from(now.minusSeconds(5)))
				.notBeforeTime(Date.from(now.minusSeconds(5)))
				.expirationTime(Date.from(this.expiresAt))
				.claim("scope", this.scope)
				.claim("realm_access", Map.of("roles", this.roles))
				.build();
		}

		/** RS256-signed compact JWT. */
		public String build() {
			try {
				SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(this.key.getKeyID()).build(),
						claims());
				jwt.sign(new RSASSASigner(this.key));
				return jwt.serialize();
			}
			catch (JOSEException ex) {
				throw new IllegalStateException(ex);
			}
		}

		/** HS256-signed with a shared secret: must be rejected (algorithm confusion). */
		public String buildHs256() {
			try {
				SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims());
				jwt.sign(new MACSigner("test-only-shared-secret-0123456789abcdef".getBytes()));
				return jwt.serialize();
			}
			catch (JOSEException ex) {
				throw new IllegalStateException(ex);
			}
		}

		/** Unsigned ({@code alg: none}): must be rejected. */
		public String buildUnsigned() {
			return new PlainJWT(claims()).serialize();
		}

	}

	private static RSAKey generate() {
		try {
			return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
