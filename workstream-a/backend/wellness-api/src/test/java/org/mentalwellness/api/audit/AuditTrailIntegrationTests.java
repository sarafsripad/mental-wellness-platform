package org.mentalwellness.api.audit;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mentalwellness.api.IntegrationTest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class AuditTrailIntegrationTests {

	@Autowired
	AuditLog auditLog;

	@Autowired
	JdbcClient jdbc;

	@Test
	void flywayCreatedOneSchemaPerModule() {
		List<String> schemas = this.jdbc.sql("SELECT schema_name FROM information_schema.schemata").query(String.class).list();
		assertThat(schemas).contains("consent", "voice", "processing", "research_export", "audit");
		assertThat(this.jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success").query(Integer.class).single())
			.isGreaterThanOrEqualTo(1);
	}

	@Test
	void recordsEventWithCodesAndPseudonymsOnly() {
		UUID correlation = UUID.randomUUID();
		this.auditLog.record(new AuditEvent("CONSENT_EVENT_READ", AuditOutcome.SUCCESS, UUID.randomUUID().toString(),
				Set.of("ADMIN"), "consent_event", "ce_" + "0".repeat(32), AccessReason.AUDIT, correlation));
		var row = this.jdbc.sql("SELECT action, resource_id, access_reason FROM audit.audit_event WHERE correlation_id = ?")
			.param(correlation)
			.query()
			.singleRow();
		assertThat(row).containsEntry("action", "CONSENT_EVENT_READ")
			.containsEntry("resource_id", "ce_" + "0".repeat(32))
			.containsEntry("access_reason", "AUDIT");
	}

	@ParameterizedTest
	@ValueSource(strings = { "UPDATE audit.audit_event SET outcome = 'SUCCESS'", "DELETE FROM audit.audit_event",
			"TRUNCATE audit.audit_event" })
	void auditTableIsAppendOnly(String statement) {
		insertOne();
		// The trigger's message is on the root (PostgreSQL) exception, which proves the trigger fired.
		assertThatThrownBy(() -> this.jdbc.sql(statement).update()).isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("append-only");
	}

	@ParameterizedTest
	@ValueSource(strings = { "resource_id", "actor_ref", "action", "actor_roles" })
	void databaseRejectsFreeTextInAuditFields(String column) {
		String freeText = "Synthetic Person, +91 00000 00000";
		var values = new java.util.LinkedHashMap<String, String>();
		values.put("action", "CONSENT_EVENT_READ");
		values.put("outcome", "SUCCESS");
		values.put("actor_ref", "actor-1");
		values.put("actor_roles", "ADMIN");
		values.put("resource_id", "ce_" + "0".repeat(32));
		values.put(column, freeText);
		String sql = "INSERT INTO audit.audit_event (" + String.join(", ", values.keySet())
				+ ", correlation_id) VALUES (:action, :outcome, :actor_ref, :actor_roles, :resource_id, gen_random_uuid())";
		var statement = this.jdbc.sql(sql);
		values.forEach(statement::param);
		assertThatThrownBy(statement::update).isInstanceOf(DataAccessException.class)
			.hasMessageContaining("check constraint");
	}

	@Test
	void auditEventRejectsFreeTextBeforeReachingTheDatabase() {
		assertThatThrownBy(() -> new AuditEvent("CONSENT_EVENT_READ", AuditOutcome.SUCCESS, "actor-1", Set.of(),
				"consent_event", "Synthetic Person", null, UUID.randomUUID()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageNotContaining("Synthetic Person");
		assertThatThrownBy(() -> new AuditEvent("consent read", AuditOutcome.SUCCESS, "actor-1", Set.of(), null, null,
				null, UUID.randomUUID()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private void insertOne() {
		this.auditLog.record(new AuditEvent("TEST_EVENT", AuditOutcome.SUCCESS, "actor-1", Set.of(), null, null, null,
				UUID.randomUUID()));
	}

}
