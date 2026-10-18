package org.mentalwellness.api.shared.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class RealmRoleConverterTests {

	private final RealmRoleConverter converter = new RealmRoleConverter();

	@Test
	void mapsPlatformRolesAndScopesAndUsesSubjectAsName() {
		var auth = this.converter.convert(jwt(Map.of("roles", List.of("ASHA_WORKER", "offline_access", "default-roles-x")),
				"consent:write voice:read"));
		assertThat(auth.getName()).isEqualTo("subject-1");
		assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
			.containsExactlyInAnyOrder("ROLE_ASHA_WORKER", "SCOPE_consent:write", "SCOPE_voice:read");
	}

	@Test
	void missingOrMalformedRealmAccessGrantsNoRoles() {
		assertThat(this.converter.convert(jwt(null, "")).getAuthorities()).isEmpty();
		assertThat(this.converter.convert(jwt(Map.of("roles", "ADMIN"), "")).getAuthorities()).isEmpty();
		assertThat(this.converter.convert(jwt(Map.of("roles", List.of(42, "admin")), "")).getAuthorities()).isEmpty();
	}

	private static Jwt jwt(Map<String, Object> realmAccess, String scope) {
		Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("subject-1").claim("scope", scope)
			.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
		if (realmAccess != null) {
			b.claim("realm_access", realmAccess);
		}
		return b.build();
	}

}
