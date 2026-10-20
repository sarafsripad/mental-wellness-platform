package org.mentalwellness.api.careteam;

import java.util.Set;

import org.mentalwellness.api.shared.security.Actor;

/** The care-team access rule (ADR-016), for every module that serves facility staff. */
public interface CareTeams {

	/**
	 * True if the actor may act on the participant in one of {@code roles}: they hold the role in
	 * their token, have an active membership with that role at an active facility, and that facility
	 * is on the participant's care team.
	 */
	boolean staffAccess(Actor actor, String participantRef, Set<CareRole> roles);

	/** True if {@code facilityCode} is an active facility where the actor holds one of {@code roles}. */
	boolean worksAt(Actor actor, String facilityCode, Set<CareRole> roles);

	/** True if the facility is on the participant's care team. */
	boolean onCareTeam(String participantRef, String facilityCode);

	/** The facility's display label, if it exists. */
	java.util.Optional<String> facilityLabel(String facilityCode);

	/**
	 * Adds a facility to the participant's care team (idempotent). Callers check consent and
	 * authority first.
	 */
	void addFacility(String participantRef, String facilityCode, String reasonCode, Actor actor);

}
