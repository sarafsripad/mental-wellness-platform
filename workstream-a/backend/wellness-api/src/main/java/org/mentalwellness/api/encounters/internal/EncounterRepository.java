package org.mentalwellness.api.encounters.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class EncounterRepository {

	private static final String ENCOUNTER_COLUMNS = """
			encounter_id, participant_ref, consent_event_id, facility_code, encounter_type, series_id, care_role,
			data_origin, started_at, ended_at, urgent_review_flag, created_by_subject""";

	private final JdbcClient jdbc;

	EncounterRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	record EncounterRow(String encounterId, String participantRef, String consentEventId, String facilityCode,
			String encounterType, String seriesId, String careRole, String dataOrigin, Instant startedAt, Instant endedAt,
			boolean urgentReviewFlag, String createdBySubject) {
	}

	record ResponseRow(String responseId, String instrumentId, String instrumentVersion, Integer totalScore,
			String severityBand) {
	}

	record NoteRow(String noteId, byte[] noteEnc, String authorSubject, Instant createdAt) {
	}

	record DiagnosisRow(String diagnosisId, String codeSystem, String code, String certainty, String recordedBySubject,
			Instant recordedAt) {
	}

	record PlanRow(String planId, Instant nextContactAt, String nextContactMode, String referralTarget) {
	}

	record TaskRow(String taskId, String planId, String participantRef, String assignee, String taskType, Instant dueBy,
			String status, Instant completedAt, String encounterId, String consentEventId, String facilityCode) {
	}

	// --- encounters ----------------------------------------------------------------

	boolean encounterExists(String id) {
		return count("SELECT count(*) FROM encounters.encounter WHERE encounter_id = ?", id) > 0;
	}

	void insertEncounter(EncounterApi.EncounterCreate e, String careRole, String subject) {
		this.jdbc.sql("""
				INSERT INTO encounters.encounter (encounter_id, participant_ref, consent_event_id, facility_code, encounter_type,
				  series_id, care_role, data_origin, started_at, created_by_subject)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""")
			.params(e.encounterId(), e.participantRef(), e.consentEventId(), e.facilityCode(), e.encounterType().name(),
					e.seriesId(), careRole, e.dataOrigin().name(), Timestamp.from(e.startedAtUtc()), subject)
			.update();
	}

	Optional<EncounterRow> find(String id) {
		return this.jdbc.sql("SELECT " + ENCOUNTER_COLUMNS + " FROM encounters.encounter WHERE encounter_id = ?")
			.param(id)
			.query(EncounterRepository::encounter)
			.optional();
	}

	Optional<EncounterRow> lock(String id) {
		return this.jdbc.sql("SELECT " + ENCOUNTER_COLUMNS + " FROM encounters.encounter WHERE encounter_id = ? FOR UPDATE")
			.param(id)
			.query(EncounterRepository::encounter)
			.optional();
	}

	List<EncounterRow> forParticipant(String ppt) {
		return this.jdbc.sql("SELECT " + ENCOUNTER_COLUMNS + " FROM encounters.encounter WHERE participant_ref = ? ORDER BY started_at")
			.param(ppt)
			.query(EncounterRepository::encounter)
			.list();
	}

	void raiseUrgent(String id) {
		this.jdbc.sql("UPDATE encounters.encounter SET urgent_review_flag = true WHERE encounter_id = ?").param(id).update();
	}

	void close(String id, Instant endedAt) {
		this.jdbc.sql("UPDATE encounters.encounter SET ended_at = ? WHERE encounter_id = ?")
			.params(Timestamp.from(endedAt), id)
			.update();
	}

	// --- instruments -----------------------------------------------------------------

	boolean responseExists(String responseId) {
		return count("SELECT count(*) FROM encounters.instrument_response WHERE response_id = ?", responseId) > 0;
	}

	boolean instrumentAnswered(String encounterId, String instrumentId) {
		return this.jdbc.sql("SELECT count(*) FROM encounters.instrument_response WHERE encounter_id = ? AND instrument_id = ?")
			.params(encounterId, instrumentId).query(Integer.class).single() > 0;
	}

	void insertResponse(String encounterId, EncounterApi.InstrumentResponseCreate r, InstrumentCatalogue.Scored scored,
			String subject) {
		this.jdbc.sql("""
				INSERT INTO encounters.instrument_response (response_id, encounter_id, instrument_id, instrument_version,
				  language_tag, answered_at, total_score, severity_band, created_by_subject)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""")
			.params(r.responseId(), encounterId, r.instrument().instrumentId(), r.instrument().instrumentVersion(),
					r.languageTag(), Timestamp.from(r.answeredAtUtc()), scored.totalScore(), scored.severityBand(), subject)
			.update();
		for (EncounterApi.Answer a : r.items()) {
			this.jdbc.sql("INSERT INTO encounters.instrument_answer (response_id, item_id, answer_code) VALUES (?, ?, ?)")
				.params(r.responseId(), a.itemId(), a.answerCode())
				.update();
		}
	}

	List<ResponseRow> responses(String encounterId) {
		return this.jdbc.sql("""
				SELECT response_id, instrument_id, instrument_version, total_score, severity_band
				FROM encounters.instrument_response WHERE encounter_id = ? ORDER BY created_at
				""").param(encounterId).query(ResponseRow.class).list();
	}

	List<EncounterApi.Answer> answers(String responseId) {
		return this.jdbc.sql("SELECT item_id, answer_code FROM encounters.instrument_answer WHERE response_id = ? ORDER BY item_id")
			.param(responseId)
			.query((rs, n) -> new EncounterApi.Answer(rs.getString(1), rs.getString(2)))
			.list();
	}

	// --- notes and diagnoses ---------------------------------------------------------

	boolean noteExists(String noteId) {
		return count("SELECT count(*) FROM encounters.clinical_note WHERE note_id = ?", noteId) > 0;
	}

	void insertNote(String noteId, String encounterId, byte[] sealed, int keyVersion, String subject) {
		this.jdbc.sql("""
				INSERT INTO encounters.clinical_note (note_id, encounter_id, note_enc, key_version, author_subject)
				VALUES (?, ?, ?, ?, ?)
				""").params(noteId, encounterId, sealed, keyVersion, subject).update();
	}

	Instant noteCreatedAt(String noteId) {
		return this.jdbc.sql("SELECT created_at FROM encounters.clinical_note WHERE note_id = ?").param(noteId)
			.query((rs, n) -> instant(rs, 1)).single();
	}

	List<NoteRow> notes(String encounterId) {
		return this.jdbc.sql("""
				SELECT note_id, note_enc, author_subject, created_at FROM encounters.clinical_note
				WHERE encounter_id = ? ORDER BY created_at
				""").param(encounterId)
			.query((rs, n) -> new NoteRow(rs.getString(1), rs.getBytes(2), rs.getString(3), instant(rs, 4)))
			.list();
	}

	boolean diagnosisExists(String id) {
		return count("SELECT count(*) FROM encounters.diagnosis WHERE diagnosis_id = ?", id) > 0;
	}

	void insertDiagnosis(String encounterId, EncounterApi.DiagnosisCreate d, String subject) {
		this.jdbc.sql("""
				INSERT INTO encounters.diagnosis (diagnosis_id, encounter_id, code_system, code, certainty, recorded_by_subject)
				VALUES (?, ?, ?, ?, ?, ?)
				""").params(d.diagnosisId(), encounterId, d.codeSystem().name(), d.code(), d.certainty().name(), subject).update();
	}

	List<DiagnosisRow> diagnoses(String encounterId) {
		return this.jdbc.sql("""
				SELECT diagnosis_id, code_system, code, certainty, recorded_by_subject, recorded_at
				FROM encounters.diagnosis WHERE encounter_id = ? ORDER BY seq
				""").param(encounterId)
			.query((rs, n) -> new DiagnosisRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
					rs.getString(5), instant(rs, 6)))
			.list();
	}

	// --- care plans and tasks --------------------------------------------------------

	boolean planExists(String encounterId) {
		return count("SELECT count(*) FROM encounters.care_plan WHERE encounter_id = ?", encounterId) > 0;
	}

	boolean planIdOrTaskTaken(String planId, List<String> taskIds) {
		return count("SELECT count(*) FROM encounters.care_plan WHERE plan_id = ?", planId) > 0
				|| this.jdbc.sql("SELECT count(*) FROM encounters.care_task WHERE task_id = ANY(?)")
					.param(taskIds.toArray(String[]::new)).query(Integer.class).single() > 0;
	}

	void insertPlan(String encounterId, String participantRef, EncounterApi.CarePlanCreate p, String subject) {
		this.jdbc.sql("""
				INSERT INTO encounters.care_plan (plan_id, encounter_id, next_contact_at, next_contact_mode, referral_target,
				  created_by_subject) VALUES (?, ?, ?, ?, ?, ?)
				""")
			.params(p.planId(), encounterId, p.nextContactAtUtc() == null ? null : Timestamp.from(p.nextContactAtUtc()),
					p.nextContactMode() == null ? null : p.nextContactMode().name(), p.referralTarget().name(), subject)
			.update();
		for (EncounterApi.TaskCreate t : p.tasks()) {
			this.jdbc.sql("""
					INSERT INTO encounters.care_task (task_id, plan_id, participant_ref, assignee, task_type, due_by)
					VALUES (?, ?, ?, ?, ?, ?)
					""").params(t.taskId(), p.planId(), participantRef, t.assignee().name(), t.taskType().name(),
					Timestamp.from(t.dueByUtc())).update();
		}
	}

	Optional<PlanRow> plan(String encounterId) {
		return this.jdbc.sql("""
				SELECT plan_id, next_contact_at, next_contact_mode, referral_target FROM encounters.care_plan WHERE encounter_id = ?
				""").param(encounterId)
			.query((rs, n) -> new PlanRow(rs.getString(1), instant(rs, 2), rs.getString(3), rs.getString(4)))
			.optional();
	}

	private static final String TASK_COLUMNS = """
			t.task_id, t.plan_id, t.participant_ref, t.assignee, t.task_type, t.due_by, t.status, t.completed_at,
			e.encounter_id, e.consent_event_id, e.facility_code
			FROM encounters.care_task t JOIN encounters.care_plan p USING (plan_id) JOIN encounters.encounter e ON e.encounter_id = p.encounter_id""";

	List<TaskRow> tasksOfPlan(String planId) {
		return this.jdbc.sql("SELECT " + TASK_COLUMNS + " WHERE t.plan_id = ? ORDER BY t.due_by, t.task_id")
			.param(planId).query(EncounterRepository::task).list();
	}

	/** Tasks assigned to the ASHA worker for these participants. */
	List<TaskRow> ashaTasks(List<String> participantRefs, boolean openOnly) {
		if (participantRefs.isEmpty()) {
			return List.of();
		}
		return this.jdbc.sql("SELECT " + TASK_COLUMNS + """
				 WHERE t.assignee = 'ASHA_WORKER' AND t.participant_ref = ANY(:ppts) AND (NOT :openOnly OR t.status = 'OPEN')
				ORDER BY t.due_by, t.task_id LIMIT 200
				""")
			.param("ppts", participantRefs.toArray(String[]::new))
			.param("openOnly", openOnly)
			.query(EncounterRepository::task)
			.list();
	}

	Optional<TaskRow> lockTask(String taskId) {
		return this.jdbc.sql("SELECT " + TASK_COLUMNS + " WHERE t.task_id = ? FOR UPDATE OF t").param(taskId)
			.query(EncounterRepository::task).optional();
	}

	void completeTask(String taskId, String status, Instant at, String subject) {
		this.jdbc.sql("UPDATE encounters.care_task SET status = ?, completed_at = ?, completed_by_subject = ? WHERE task_id = ?")
			.params(status, Timestamp.from(at), subject, taskId).update();
	}

	// --- helpers ---------------------------------------------------------------------

	private int count(String sql, String param) {
		return this.jdbc.sql(sql).param(param).query(Integer.class).single();
	}

	private static EncounterRow encounter(ResultSet rs, int n) throws SQLException {
		return new EncounterRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
				rs.getString(6), rs.getString(7), rs.getString(8), instant(rs, 9), instant(rs, 10), rs.getBoolean(11),
				rs.getString(12));
	}

	private static TaskRow task(ResultSet rs, int n) throws SQLException {
		return new TaskRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), instant(rs, 6),
				rs.getString(7), instant(rs, 8), rs.getString(9), rs.getString(10), rs.getString(11));
	}

	private static Instant instant(ResultSet rs, int column) throws SQLException {
		Timestamp ts = rs.getTimestamp(column);
		return ts == null ? null : ts.toInstant();
	}

}
