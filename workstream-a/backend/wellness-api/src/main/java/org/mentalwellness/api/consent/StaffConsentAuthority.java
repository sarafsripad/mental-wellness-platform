package org.mentalwellness.api.consent;

import org.mentalwellness.api.shared.security.Actor;

/**
 * Extension point for facility staff (ADR-016), implemented by the care-team module: consent can
 * ask about staff without depending on care teams (which depend on consent).
 */
public interface StaffConsentAuthority {

	/**
	 * May this staff member record consent for the participant at the facility? A new participant
	 * needs the staff member to work there; an existing one must already have them on their care team.
	 */
	boolean mayRecord(Actor actor, String participantRef, String facilityCode, boolean participantExists);

	/** Called after a staff member recorded consent: the facility joins the participant's care team. */
	void recorded(Actor actor, String participantRef, String facilityCode);

	/** May this staff member read the participant's consent (they are on the care team)? */
	boolean mayRead(Actor actor, String participantRef);

}
