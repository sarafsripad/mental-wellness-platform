package org.mentalwellness.api.processing.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JobRepository {

	private final JdbcClient jdbc;

	JobRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	record JobRow(String jobId, String recordingId, String sessionId, String participantRef, String consentEventId,
			String pipelineProfile, String status, int attempt, String leaseTokenHash, String leaseWorkerId,
			Instant leaseExpiresAt, String qualityStatus, List<String> qualityFlags, String failureCode,
			Boolean failureRetryable, String featureRecordId, UUID correlationId, Instant createdAt, Instant updatedAt) {
	}

	private static final String COLUMNS = """
			job_id, recording_id, session_id, participant_ref, consent_event_id, pipeline_profile, status, attempt,
			lease_token_hash, lease_worker_id, lease_expires_at, quality_status, quality_flags, failure_code,
			failure_retryable, feature_record_id, correlation_id, created_at, updated_at
			""";

	Optional<JobRow> find(String jobId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM processing.job WHERE job_id = ?").param(jobId).query(JobRepository::row).optional();
	}

	Optional<JobRow> lock(String jobId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM processing.job WHERE job_id = ? FOR UPDATE").param(jobId)
			.query(JobRepository::row).optional();
	}

	Optional<JobRow> findByRecording(String recordingId, String profile) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM processing.job WHERE recording_id = ? AND pipeline_profile = ?")
			.params(recordingId, profile).query(JobRepository::row).optional();
	}

	/** Inserts unless (recording, profile) already has a job; returns true if inserted. */
	boolean insert(String jobId, String recordingId, String sessionId, String participantRef, String consentEventId,
			String profile, String subject, UUID correlationId) {
		return this.jdbc.sql("""
				INSERT INTO processing.job (job_id, recording_id, session_id, participant_ref, consent_event_id,
				  pipeline_profile, submitted_by_subject, correlation_id)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (recording_id, pipeline_profile) DO NOTHING
				""")
			.params(jobId, recordingId, sessionId, participantRef, consentEventId, profile, subject, correlationId)
			.update() == 1;
	}

	/** Oldest queued job for these profiles, locked; other workers skip it (ADR-007). */
	Optional<JobRow> nextQueued(List<String> profiles) {
		return this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM processing.job WHERE status = 'QUEUED' AND pipeline_profile = ANY(?)
				ORDER BY created_at, job_id LIMIT 1 FOR UPDATE SKIP LOCKED
				""")
			.param(profiles.toArray(String[]::new))
			.query(JobRepository::row)
			.optional();
	}

	void lease(String jobId, String tokenHash, String workerId, Instant expiresAt) {
		this.jdbc.sql("""
				UPDATE processing.job SET status = 'RUNNING', attempt = attempt + 1, lease_token_hash = ?,
				  lease_worker_id = ?, lease_expires_at = ?, updated_at = now()
				WHERE job_id = ? AND status = 'QUEUED'
				""")
			.params(tokenHash, workerId, Timestamp.from(expiresAt), jobId)
			.update();
	}

	void succeed(String jobId, String featureRecordId, String qualityStatus, List<String> flags) {
		this.jdbc.sql("""
				UPDATE processing.job SET status = 'SUCCEEDED', feature_record_id = ?, quality_status = ?, quality_flags = ?,
				  lease_expires_at = NULL, updated_at = now()
				WHERE job_id = ? AND status = 'RUNNING'
				""")
			.params(featureRecordId, qualityStatus, flags.toArray(String[]::new), jobId)
			.update();
	}

	void requeue(String jobId) {
		this.jdbc.sql("""
				UPDATE processing.job SET status = 'QUEUED', lease_token_hash = NULL, lease_worker_id = NULL,
				  lease_expires_at = NULL, updated_at = now()
				WHERE job_id = ?
				""").param(jobId).update();
	}

	void fail(String jobId, String status, String code, boolean retryable, String qualityStatus, List<String> flags) {
		this.jdbc.sql("""
				UPDATE processing.job SET status = ?, failure_code = ?, failure_retryable = ?,
				  quality_status = COALESCE(?, quality_status), quality_flags = COALESCE(?, quality_flags),
				  lease_token_hash = NULL, lease_worker_id = NULL, lease_expires_at = NULL, updated_at = now()
				WHERE job_id = ?
				""")
			.params(status, code, retryable, qualityStatus, flags == null ? null : flags.toArray(String[]::new), jobId)
			.update();
	}

	/** On withdrawal: queued and running jobs for the event are cancelled. */
	int cancelForConsentEvent(String consentEventId) {
		return this.jdbc.sql("""
				UPDATE processing.job SET status = 'CANCELLED', failure_code = 'CONSENT_WITHDRAWN', failure_retryable = false,
				  lease_token_hash = NULL, lease_worker_id = NULL, lease_expires_at = NULL, updated_at = now()
				WHERE consent_event_id = ? AND status IN ('QUEUED', 'RUNNING')
				""").param(consentEventId).update();
	}

	List<String> expiredLeases() {
		return this.jdbc.sql("""
				SELECT job_id FROM processing.job WHERE status = 'RUNNING' AND lease_expires_at < now()
				ORDER BY lease_expires_at LIMIT 100 FOR UPDATE SKIP LOCKED
				""").query(String.class).list();
	}

	List<String> statusesForSession(String sessionId) {
		return this.jdbc.sql("SELECT status FROM processing.job WHERE session_id = ?").param(sessionId).query(String.class).list();
	}

	// --- feature records -----------------------------------------------------------

	void insertFeatureRecord(String recordId, String jobId, String recordingId, String sessionId, String consentEventId,
			String dataOrigin, String recordJson, String recordHash) {
		this.jdbc.sql("""
				INSERT INTO processing.feature_record (record_id, job_id, recording_id, session_id, consent_event_id,
				  schema_version, data_origin, record, record_hash)
				VALUES (?, ?, ?, ?, ?, '2.0.0', ?, CAST(? AS jsonb), ?)
				""")
			.params(recordId, jobId, recordingId, sessionId, consentEventId, dataOrigin, recordJson, recordHash)
			.update();
	}

	Optional<String> featureRecordHash(String jobId) {
		return this.jdbc.sql("SELECT record_hash FROM processing.feature_record WHERE job_id = ?").param(jobId)
			.query(String.class).optional();
	}

	boolean featureRecordExists(String recordId) {
		return this.jdbc.sql("SELECT count(*) FROM processing.feature_record WHERE record_id = ?").param(recordId)
			.query(Integer.class).single() > 0;
	}

	java.util.List<org.mentalwellness.api.processing.FeatureRecords.Stored> featureRecords(String where, Object... params) {
		return this.jdbc.sql("""
				SELECT f.record_id, f.consent_event_id, j.participant_ref, f.session_id, f.flagged_at IS NOT NULL, f.record::text
				FROM processing.feature_record f JOIN processing.job j ON j.job_id = f.job_id
				""" + where + " ORDER BY f.created_at, f.record_id")
			.params(params)
			.query((rs, n) -> new org.mentalwellness.api.processing.FeatureRecords.Stored(rs.getString(1), rs.getString(2),
					rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getString(6)))
			.list();
	}

	boolean hasFeatureRecords(String consentEventId) {
		return this.jdbc.sql("SELECT count(*) FROM processing.feature_record WHERE consent_event_id = ?").param(consentEventId)
			.query(Integer.class).single() > 0;
	}

	int flagFeatureRecords(String consentEventId) {
		return this.jdbc.sql("UPDATE processing.feature_record SET flagged_at = now() WHERE consent_event_id = ? AND flagged_at IS NULL")
			.param(consentEventId).update();
	}

	private static JobRow row(ResultSet rs, int n) throws SQLException {
		Array flags = rs.getArray(13);
		return new JobRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
				rs.getString(6), rs.getString(7), rs.getInt(8), rs.getString(9), rs.getString(10), instant(rs, 11),
				rs.getString(12), flags == null ? null : Arrays.asList((String[]) flags.getArray()), rs.getString(14),
				(Boolean) rs.getObject(15), rs.getString(16), (UUID) rs.getObject(17), instant(rs, 18), instant(rs, 19));
	}

	private static Instant instant(ResultSet rs, int column) throws SQLException {
		Timestamp t = rs.getTimestamp(column);
		return t == null ? null : t.toInstant();
	}

}
