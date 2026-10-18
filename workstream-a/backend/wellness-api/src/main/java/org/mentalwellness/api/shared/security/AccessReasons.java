package org.mentalwellness.api.shared.security;

import java.util.Optional;

import org.mentalwellness.api.audit.AccessReason;
import org.mentalwellness.api.shared.web.DomainException;

/** Reads {@code X-Access-Reason}: required when a caller reaches participant data only through ADMIN. */
public final class AccessReasons {

	public static final String HEADER = "X-Access-Reason";

	private AccessReasons() {
	}

	public static Optional<AccessReason> resolve(Actor actor, String header) {
		AccessReason reason = null;
		if (header != null) {
			try {
				reason = AccessReason.valueOf(header);
			}
			catch (IllegalArgumentException ex) {
				throw DomainException.invalid("/" + HEADER, "enum");
			}
		}
		if (actor.adminOnly() && reason == null) {
			throw DomainException.invalid("/" + HEADER, "required");
		}
		return Optional.ofNullable(reason);
	}

}
