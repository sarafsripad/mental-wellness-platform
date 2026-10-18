package org.mentalwellness.api.audit;

/** Records audit events. Append-only: there is no way to change or remove an event. */
public interface AuditLog {

	void record(AuditEvent event);

}
