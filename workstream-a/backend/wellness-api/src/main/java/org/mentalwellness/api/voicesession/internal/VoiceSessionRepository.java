package org.mentalwellness.api.voicesession.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class VoiceSessionRepository {

	private final JdbcClient jdbc;

	VoiceSessionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	record SessionRow(String sessionId, String participantRef, String consentEventId, String languageTag,
			String dataOrigin, String deviceClass, Instant capturedAt, Instant createdAt, Instant cancelledAt) {
	}

	record RecordingRow(String recordingId, String sessionId, String promptId, String format, int samplingRateHz,
			int channels, BigDecimal durationSeconds, long sizeBytes, String sha256, Instant recordedAt,
			String uploadStatus, String objectKey, Instant storedAt) {
	}

	// --- sessions ------------------------------------------------------------------

	boolean sessionExists(String sessionId) {
		return this.jdbc.sql("SELECT count(*) FROM voice.session WHERE session_id = ?").param(sessionId).query(Integer.class).single() > 0;
	}

	void insertSession(VoiceApi.VoiceSessionCreate s, String subject) {
		this.jdbc.sql("""
				INSERT INTO voice.session (session_id, participant_ref, consent_event_id, language_tag, prompt_set_id,
				  prompt_set_version, captured_at, device_class, app_version, os_family, data_origin, created_by_subject)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""")
			.params(s.sessionId(), s.participantRef(), s.consentEventId(), s.languageTag(), s.promptSet().promptSetId(),
					s.promptSet().promptSetVersion(), Timestamp.from(s.capturedAtUtc()), s.device().deviceClass().name(),
					s.device().appVersion(), s.device().osFamily().name(), s.dataOrigin().name(), subject)
			.update();
	}

	Optional<SessionRow> findSession(String sessionId) {
		return this.jdbc.sql("""
				SELECT session_id, participant_ref, consent_event_id, language_tag, data_origin, device_class,
				  captured_at, created_at, cancelled_at
				FROM voice.session WHERE session_id = ?
				""")
			.param(sessionId)
			.query((rs, n) -> new SessionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
					rs.getString(5), rs.getString(6), instant(rs, 7), instant(rs, 8), instant(rs, 9)))
			.optional();
	}

	List<String> sessionsForConsentEvent(String consentEventId) {
		return this.jdbc.sql("SELECT session_id FROM voice.session WHERE consent_event_id = ?")
			.param(consentEventId)
			.query(String.class)
			.list();
	}

	int cancelSessions(String consentEventId) {
		return this.jdbc.sql("UPDATE voice.session SET cancelled_at = now() WHERE consent_event_id = ? AND cancelled_at IS NULL")
			.param(consentEventId)
			.update();
	}

	// --- recordings ----------------------------------------------------------------

	boolean recordingExists(String recordingId) {
		return this.jdbc.sql("SELECT count(*) FROM voice.recording WHERE recording_id = ?").param(recordingId).query(Integer.class).single() > 0;
	}

	void insertRecording(String sessionId, VoiceApi.RecordingCreate r) {
		VoiceApi.DevicePrescreen p = r.devicePrescreen();
		this.jdbc.sql("""
				INSERT INTO voice.recording (recording_id, session_id, prompt_id, format, sampling_rate_hz, channels,
				  duration_seconds, size_bytes, sha256, recorded_at, prescreen_peak_dbfs, prescreen_clipping, prescreen_low_level)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""")
			.params(r.recordingId(), sessionId, r.promptId(), r.format().name(), r.samplingRateHz(), r.channels(),
					r.durationSeconds(), r.sizeBytes(), r.sha256(), Timestamp.from(r.recordedAtUtc()),
					p == null ? null : p.peakDbfs(), p == null ? null : p.clippingDetected(),
					p == null ? null : p.lowLevelDetected())
			.update();
	}

	private static final String RECORDING_COLUMNS = """
			recording_id, session_id, prompt_id, format, sampling_rate_hz, channels, duration_seconds, size_bytes,
			sha256, recorded_at, upload_status, object_key, stored_at
			""";

	Optional<RecordingRow> findRecording(String recordingId) {
		return this.jdbc.sql("SELECT " + RECORDING_COLUMNS + " FROM voice.recording WHERE recording_id = ?")
			.param(recordingId)
			.query(VoiceSessionRepository::recording)
			.optional();
	}

	/** Locks the recording row so concurrent uploads of the same recording are serialised. */
	Optional<RecordingRow> lockRecording(String recordingId) {
		return this.jdbc.sql("SELECT " + RECORDING_COLUMNS + " FROM voice.recording WHERE recording_id = ? FOR UPDATE")
			.param(recordingId)
			.query(VoiceSessionRepository::recording)
			.optional();
	}

	List<RecordingRow> recordings(String sessionId) {
		return this.jdbc.sql("SELECT " + RECORDING_COLUMNS + " FROM voice.recording WHERE session_id = ? ORDER BY created_at, recording_id")
			.param(sessionId)
			.query(VoiceSessionRepository::recording)
			.list();
	}

	void markStored(String recordingId, String objectKey) {
		this.jdbc.sql("""
				UPDATE voice.recording SET upload_status = 'STORED', object_key = ?, stored_at = now()
				WHERE recording_id = ? AND upload_status = 'AWAITING_CONTENT'
				""")
			.params(objectKey, recordingId)
			.update();
	}

	/** On withdrawal: stored audio is queued for deletion; un-uploaded recordings can never be uploaded. */
	int[] markForDeletion(String consentEventId) {
		int pending = this.jdbc.sql("""
				UPDATE voice.recording r SET delete_pending = true
				FROM voice.session s
				WHERE r.session_id = s.session_id AND s.consent_event_id = ? AND r.upload_status = 'STORED' AND NOT r.delete_pending
				""")
			.param(consentEventId)
			.update();
		int closed = this.jdbc.sql("""
				UPDATE voice.recording r SET upload_status = 'DELETED', deleted_at = now()
				FROM voice.session s
				WHERE r.session_id = s.session_id AND s.consent_event_id = ? AND r.upload_status = 'AWAITING_CONTENT'
				""")
			.param(consentEventId)
			.update();
		return new int[] { pending, closed };
	}

	record PendingDeletion(String recordingId, String objectKey) {
	}

	List<PendingDeletion> pendingDeletions(int limit) {
		return this.jdbc.sql("SELECT recording_id, object_key FROM voice.recording WHERE delete_pending ORDER BY recording_id LIMIT ?")
			.param(limit)
			.query(PendingDeletion.class)
			.list();
	}

	void markDeleted(String recordingId) {
		this.jdbc.sql("""
				UPDATE voice.recording SET upload_status = 'DELETED', delete_pending = false, deleted_at = now(), object_key = NULL
				WHERE recording_id = ? AND delete_pending
				""")
			.param(recordingId)
			.update();
	}

	private static RecordingRow recording(ResultSet rs, int n) throws SQLException {
		return new RecordingRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
				rs.getInt(6), rs.getBigDecimal(7), rs.getLong(8), rs.getString(9), instant(rs, 10), rs.getString(11),
				rs.getString(12), instant(rs, 13));
	}

	private static Instant instant(ResultSet rs, int column) throws SQLException {
		Timestamp t = rs.getTimestamp(column);
		return t == null ? null : t.toInstant();
	}

}
