package org.mentalwellness.api.careteam;

/** A staff member's role at a facility. Must also be one of their realm roles. */
public enum CareRole {

	/** Doctor or other prescribing clinician. */
	CLINICIAN,

	/** Trained counsellor. */
	COUNSELLOR,

	/** ANM, nurse or other health staff: no access to notes, diagnoses or scores. */
	HEALTH_STAFF

}
