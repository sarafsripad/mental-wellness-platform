package org.mentalwellness.api.audit;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One audit record. Holds codes and pseudonymous IDs only; the constructor rejects anything that
 * looks like free text, so names, request bodies or audio can't be logged by mistake. The database
 * applies the same rules as check constraints.
 *
 * @param action        upper-case event code from the contract's {@code x-audit-events}, e.g. {@code CONSENT_EVENT_CREATED}
 * @param outcome       what happened
 * @param actorRef      pseudonymous actor: the token subject, or {@code anonymous}
 * @param actorRoles    the actor's platform roles
 * @param resourceType  lower-case resource type, e.g. {@code consent_event}; may be null
 * @param resourceId    pseudonymous resource ID such as {@code ce_<32 hex>}; may be null
 * @param accessReason  ADMIN access reason; may be null
 * @param correlationId correlation ID of the request
 */
public record AuditEvent(String action, AuditOutcome outcome, String actorRef, Set<String> actorRoles,
		String resourceType, String resourceId, AccessReason accessReason, UUID correlationId) {

	private static final Pattern ACTION = Pattern.compile("[A-Z][A-Z_]{2,63}");
	private static final Pattern ACTOR = Pattern.compile("[A-Za-z0-9_-]{1,64}");
	private static final Pattern ROLE = Pattern.compile("[A-Z][A-Z_]{1,31}");
	private static final Pattern RESOURCE_TYPE = Pattern.compile("[a-z][a-z_]{1,31}");
	private static final Pattern RESOURCE_ID = Pattern.compile("[a-z]{2,5}_[a-f0-9]{32}");

	public AuditEvent {
		require(ACTION, Objects.requireNonNull(action, "action"), "action");
		Objects.requireNonNull(outcome, "outcome");
		require(ACTOR, Objects.requireNonNull(actorRef, "actorRef"), "actorRef");
		actorRoles = Set.copyOf(Objects.requireNonNull(actorRoles, "actorRoles"));
		actorRoles.forEach(r -> require(ROLE, r, "actorRoles"));
		if (resourceType != null) {
			require(RESOURCE_TYPE, resourceType, "resourceType");
		}
		if (resourceId != null) {
			require(RESOURCE_ID, resourceId, "resourceId");
		}
		Objects.requireNonNull(correlationId, "correlationId");
	}

	private static void require(Pattern pattern, String value, String field) {
		if (!pattern.matcher(value).matches()) {
			// The value itself is deliberately not included in the message.
			throw new IllegalArgumentException("Audit field '" + field + "' does not match " + pattern.pattern());
		}
	}

}
