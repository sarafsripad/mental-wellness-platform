package org.mentalwellness.api.consent.internal;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.mentalwellness.api.consent.ConsentScope;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Data access for the consent schema. Inserts only: consent history is append-only. */
@Repository
class ConsentRepository {

	private final JdbcClient jdbc;

	ConsentRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	record StoredEvent(String consentEventId, String participantRef, String formId, String formVersion,
			String languageTag, String capturedBySubject, Instant capturedAt, Instant receivedAt) {
	}

	record Decision(ConsentScope scope, boolean granted) {
	}

	// --- forms and assignment ------------------------------------------------------

	Optional<Set<ConsentScope>> offeredScopes(String formId, String formVersion, String languageTag) {
		return this.jdbc.sql("""
				SELECT offered_scopes::text[] FROM consent.consent_form
				WHERE form_id = ? AND form_version = ? AND language_tag = ?
				""")
			.params(formId, formVersion, languageTag)
			.query((rs, n) -> toScopes(rs.getArray(1)))
			.optional();
	}

	Optional<String> assignedWorker(String participantRef) {
		return this.jdbc.sql("SELECT worker_subject FROM consent.participant_assignment WHERE participant_ref = ?")
			.param(participantRef)
			.query(String.class)
			.optional();
	}

	/** Assigns the participant if unassigned; returns the (possibly pre-existing) assigned worker. */
	String assignIfAbsent(String participantRef, String workerSubject) {
		this.jdbc.sql("""
				INSERT INTO consent.participant_assignment (participant_ref, worker_subject) VALUES (?, ?)
				ON CONFLICT (participant_ref) DO NOTHING
				""")
			.params(participantRef, workerSubject)
			.update();
		return assignedWorker(participantRef).orElseThrow();
	}

	// --- events --------------------------------------------------------------------

	boolean eventExists(String consentEventId) {
		return this.jdbc.sql("SELECT count(*) FROM consent.consent_event WHERE consent_event_id = ?")
			.param(consentEventId)
			.query(Integer.class)
			.single() > 0;
	}

	void insertEvent(ConsentApi.ConsentEventCreate e, String capturedBySubject) {
		this.jdbc.sql("""
				INSERT INTO consent.consent_event (consent_event_id, participant_ref, form_id, form_version, language_tag,
				  capture_method, read_aloud, comprehension_confirmed, witness_present, adult_confirmed, assisted_consent,
				  captured_by_subject, captured_at)
				VALUES (:id, :participant, :formId, :formVersion, :language, :method, :readAloud, :comprehension,
				  :witness, :adult, :assisted, :subject, :capturedAt)
				""")
			.param("id", e.consentEventId())
			.param("participant", e.participantRef())
			.param("formId", e.consentForm().formId())
			.param("formVersion", e.consentForm().formVersion())
			.param("language", e.consentForm().languageTag())
			.param("method", e.capture().method().name())
			.param("readAloud", e.capture().readAloud())
			.param("comprehension", e.capture().comprehensionConfirmed())
			.param("witness", e.capture().witnessPresent())
			.param("adult", e.safeguards().adultConfirmed())
			.param("assisted", e.safeguards().assistedConsent())
			.param("subject", capturedBySubject)
			.param("capturedAt", Timestamp.from(e.capture().capturedAtUtc()))
			.update();
		for (ConsentApi.ScopeDecision d : e.scopeDecisions()) {
			this.jdbc.sql("INSERT INTO consent.scope_decision VALUES (?, CAST(? AS consent.scope), ?)")
				.params(e.consentEventId(), d.scope().name(), d.granted())
				.update();
		}
	}

	Optional<StoredEvent> findEvent(String consentEventId) {
		return this.jdbc.sql("""
				SELECT consent_event_id, participant_ref, form_id, form_version, language_tag, captured_by_subject,
				  captured_at, received_at
				FROM consent.consent_event WHERE consent_event_id = ?
				""")
			.param(consentEventId)
			.query((rs, n) -> new StoredEvent(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
					rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant()))
			.optional();
	}

	/** Locks the event row so concurrent withdrawals on the same event are serialised. */
	void lockEvent(String consentEventId) {
		this.jdbc.sql("SELECT 1 FROM consent.consent_event WHERE consent_event_id = ? FOR UPDATE")
			.param(consentEventId)
			.query(Integer.class)
			.optional();
	}

	Optional<String> latestEventFor(String participantRef) {
		return this.jdbc.sql("""
				SELECT consent_event_id FROM consent.consent_event WHERE participant_ref = ?
				ORDER BY captured_at DESC, received_at DESC LIMIT 1
				""")
			.param(participantRef)
			.query(String.class)
			.optional();
	}

	List<Decision> decisions(String consentEventId) {
		return this.jdbc.sql("SELECT scope::text, granted FROM consent.scope_decision WHERE consent_event_id = ? ORDER BY scope")
			.param(consentEventId)
			.query((rs, n) -> new Decision(ConsentScope.valueOf(rs.getString(1)), rs.getBoolean(2)))
			.list();
	}

	Set<ConsentScope> withdrawnScopes(String consentEventId) {
		List<String> rows = this.jdbc.sql("""
				SELECT ws.scope::text FROM consent.withdrawn_scope ws
				JOIN consent.withdrawal w ON w.withdrawal_id = ws.withdrawal_id
				WHERE w.consent_event_id = ?
				""")
			.param(consentEventId)
			.query(String.class)
			.list();
		return rows.stream().map(ConsentScope::valueOf).collect(Collectors.toCollection(() -> EnumSet.noneOf(ConsentScope.class)));
	}

	// --- withdrawals ---------------------------------------------------------------

	boolean withdrawalExists(String withdrawalId) {
		return this.jdbc.sql("SELECT count(*) FROM consent.withdrawal WHERE withdrawal_id = ?")
			.param(withdrawalId)
			.query(Integer.class)
			.single() > 0;
	}

	Instant insertWithdrawal(String consentEventId, ConsentApi.ConsentWithdrawalCreate w, Set<ConsentScope> withdrawn,
			String recordedBySubject) {
		Instant recordedAt = this.jdbc.sql("""
				INSERT INTO consent.withdrawal (withdrawal_id, consent_event_id, withdrawn_at, channel, reason_code,
				  recorded_by_subject)
				VALUES (?, ?, ?, ?, ?, ?) RETURNING recorded_at
				""")
			.params(w.withdrawalId(), consentEventId, Timestamp.from(w.withdrawnAtUtc()), w.channel().name(),
					w.reasonCode() == null ? null : w.reasonCode().name(), recordedBySubject)
			.query(Timestamp.class)
			.single()
			.toInstant();
		for (ConsentScope scope : withdrawn) {
			this.jdbc.sql("INSERT INTO consent.withdrawn_scope VALUES (?, CAST(? AS consent.scope))")
				.params(w.withdrawalId(), scope.name())
				.update();
		}
		return recordedAt;
	}

	private static Set<ConsentScope> toScopes(Array array) throws SQLException {
		return Arrays.stream((String[]) array.getArray())
			.map(ConsentScope::valueOf)
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(ConsentScope.class)));
	}

}
