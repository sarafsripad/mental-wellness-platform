package org.mentalwellness.api.consent;

import java.time.Instant;
import java.util.Set;

/**
 * The consent check every module calls before touching participant data (ADR-011, CLAUDE.md
 * rule 9). Checks run on the referenced consent event, never on the participant's latest event, so
 * a newer event cannot revive a withdrawn one.
 */
public interface ConsentGate {

	/**
	 * @param consentEventId the event the request references
	 * @param participantRef the participant the request is about
	 * @param required       scopes that must be in effect on that event
	 * @param capturedAt     when the data was captured; must not be before the consent was captured
	 * @return the verified snapshot
	 * @throws org.mentalwellness.api.shared.web.DomainException {@code CONSENT_PARTICIPANT_MISMATCH}
	 * if the event is unknown or belongs to someone else, {@code CONSENT_NOT_ACTIVE} if a required
	 * scope is not in effect or the capture time precedes consent
	 */
	ConsentSnapshot require(String consentEventId, String participantRef, Set<ConsentScope> required, Instant capturedAt);

	/** Re-checks scopes on an event without a capture time (e.g. at a later processing stage). */
	ConsentSnapshot requireStillActive(String consentEventId, Set<ConsentScope> required);

}
