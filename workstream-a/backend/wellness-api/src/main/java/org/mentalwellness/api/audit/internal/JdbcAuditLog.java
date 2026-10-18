package org.mentalwellness.api.audit.internal;

import java.util.TreeSet;

import org.mentalwellness.api.audit.AuditEvent;
import org.mentalwellness.api.audit.AuditLog;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcAuditLog implements AuditLog {

	private final JdbcClient jdbc;

	JdbcAuditLog(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordIndependently(AuditEvent event) {
		record(event);
	}

	@Override
	@Transactional
	public void record(AuditEvent event) {
		this.jdbc.sql("""
				INSERT INTO audit.audit_event
				  (action, outcome, actor_ref, actor_roles, resource_type, resource_id, access_reason, correlation_id)
				VALUES (:action, :outcome, :actor, :roles, :resourceType, :resourceId, :reason, :correlationId)
				""")
			.param("action", event.action())
			.param("outcome", event.outcome().name())
			.param("actor", event.actorRef())
			.param("roles", String.join(",", new TreeSet<>(event.actorRoles())))
			.param("resourceType", event.resourceType())
			.param("resourceId", event.resourceId())
			.param("reason", event.accessReason() == null ? null : event.accessReason().name())
			.param("correlationId", event.correlationId())
			.update();
	}

}
