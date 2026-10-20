package org.mentalwellness.api.consent;

/**
 * v1 assignment rule (wellness-api.v1.yaml): a participant is assigned to the ASHA worker who
 * recorded their first consent event. Moves to the case module once it exists.
 */
public interface ParticipantAccess {

	/** True if the participant exists and is assigned to the worker with this token subject. */
	boolean isAssignedTo(String participantRef, String workerSubject);

	/** The token subject of the participant's ASHA worker, if the participant exists. */
	java.util.Optional<String> assignedWorker(String participantRef);

}
