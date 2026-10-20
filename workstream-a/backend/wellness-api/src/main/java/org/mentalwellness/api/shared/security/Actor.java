package org.mentalwellness.api.shared.security;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The authenticated caller: token subject and platform roles. The subject is Keycloak's random user
 * ID, which is pseudonymous; it is never a name or username.
 */
public record Actor(String subject, Set<String> roles) {

	public static final String ASHA_WORKER = "ASHA_WORKER";
	public static final String CLINICIAN = "CLINICIAN";
	public static final String ADMIN = "ADMIN";
	public static final String PROCESSING_WORKER = "PROCESSING_WORKER";
	public static final String PARTNER_SYSTEM = "PARTNER_SYSTEM";
	public static final String COUNSELLOR = "COUNSELLOR";
	public static final String HEALTH_STAFF = "HEALTH_STAFF";

	public Actor {
		roles = Set.copyOf(roles);
	}

	public static Actor current() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
			throw new AuthenticationCredentialsNotFoundException("No authenticated caller");
		}
		Set<String> roles = auth.getAuthorities()
			.stream()
			.map(GrantedAuthority::getAuthority)
			.filter(a -> a.startsWith("ROLE_"))
			.map(a -> a.substring("ROLE_".length()))
			.collect(Collectors.toSet());
		return new Actor(auth.getName(), roles);
	}

	public boolean has(String role) {
		return this.roles.contains(role);
	}

	/** True when the caller can only reach a record through the ADMIN role (so a reason code is required). */
	public boolean adminOnly() {
		return has(ADMIN) && !has(ASHA_WORKER);
	}

}
