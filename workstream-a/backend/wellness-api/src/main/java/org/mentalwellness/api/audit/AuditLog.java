package org.mentalwellness.api.audit;

/** Records audit events. Append-only: there is no way to change or remove an event. */
public interface AuditLog {

	/** Records the event as part of the current transaction: it is kept only if the work commits. */
	void record(AuditEvent event);

	/**
	 * Records the event in its own transaction, so it is kept even if the current work rolls back.
	 * Used for rejected and denied requests.
	 */
	void recordIndependently(AuditEvent event);

}
