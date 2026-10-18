package org.mentalwellness.api.shared.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Builds authorities from a Keycloak token: {@code SCOPE_<scope>} from the {@code scope} claim and
 * {@code ROLE_<role>} for the platform roles in {@code realm_access.roles}. Other realm roles (for
 * example Keycloak's own defaults) are ignored. The principal name is the token subject.
 */
public class RealmRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	/** Roles the platform recognises (contracts: {@code x-roles}). */
	public static final Set<String> PLATFORM_ROLES = Set.of("ASHA_WORKER", "CLINICIAN", "ADMIN", "PROCESSING_WORKER");

	private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		List<GrantedAuthority> authorities = new ArrayList<>(this.scopes.convert(jwt));
		if (jwt.getClaim("realm_access") instanceof Map<?, ?> realmAccess
				&& realmAccess.get("roles") instanceof Collection<?> roles) {
			roles.stream()
				.filter(String.class::isInstance)
				.map(String.class::cast)
				.filter(PLATFORM_ROLES::contains)
				.map(role -> new SimpleGrantedAuthority("ROLE_" + role))
				.forEach(authorities::add);
		}
		return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
	}

}
