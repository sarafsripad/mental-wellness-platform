package org.mentalwellness.api.consent;

import java.time.Instant;
import java.util.Set;

/**
 * Result of a successful consent check: which scopes were in effect, and when they were checked.
 * Copied into feature records as {@code consent_reference}.
 */
public record ConsentSnapshot(String consentEventId, String participantRef, Set<ConsentScope> scopesVerified,
		Instant verifiedAt) {

	public ConsentSnapshot {
		scopesVerified = Set.copyOf(scopesVerified);
	}

}
