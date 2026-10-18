package org.mentalwellness.api.voicesession;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mentalwellness.api.IntegrationTest;
import org.mentalwellness.api.support.ContractProblemSchema;
import org.mentalwellness.api.support.ContractSamples;
import org.mentalwellness.api.support.Fixtures;
import org.mentalwellness.api.support.Ids;
import org.mentalwellness.api.support.SyntheticAudio;
import org.mentalwellness.api.support.TestTokens;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class VoiceSessionIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired JdbcClient jdbc;
	@Autowired RecordingAudio recordingAudio;
	@Autowired org.mentalwellness.api.voicesession.internal.AudioStoreTestAccess storage;

	final TestTokens.Builder asha = TestTokens.token().roles("ASHA_WORKER").scope(Fixtures.USER_SCOPES);
	final TestTokens.Builder otherAsha = TestTokens.token().roles("ASHA_WORKER").scope(Fixtures.USER_SCOPES);
	final TestTokens.Builder admin = TestTokens.token().roles("ADMIN").scope(Fixtures.USER_SCOPES);

	final Instant consentedAt = Instant.now().minusSeconds(7200);
	String participant;
	String consentEvent;

	@BeforeEach
	void consentedParticipant() throws Exception {
		this.participant = Ids.of("ppt");
		this.consentEvent = recordConsent(this.asha, this.participant, Set.copyOf(Fixtures.ALL_SCOPES));
	}

	// --- helpers -------------------------------------------------------------------

	String recordConsent(TestTokens.Builder who, String participantRef, Set<String> granted) throws Exception {
		String id = Ids.of("ce");
		MvcResult r = mvc.perform(post("/v1/consent-events").header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(Fixtures.consent(id, participantRef, granted, this.consentedAt)))).andReturn();
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
		return id;
	}

	MvcResult createSession(TestTokens.Builder who, Map<String, Object> body, UUID key) throws Exception {
		String content = json.writeValueAsString(body);
		MvcResult r = mvc.perform(post("/v1/voice-sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())
			.header("Idempotency-Key", key.toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(content)).andReturn();
		ContractSamples.record("createVoiceSession", content, r);
		return r;
	}

	MvcResult getSession(TestTokens.Builder who, String sessionId, String reason) throws Exception {
		var req = get("/v1/voice-sessions/{id}", sessionId).header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build());
		if (reason != null) {
			req.header("X-Access-Reason", reason);
		}
		MvcResult r = mvc.perform(req).andReturn();
		ContractSamples.record("getVoiceSession", null, r);
		return r;
	}

	MvcResult register(TestTokens.Builder who, String sessionId, Map<String, Object> body) throws Exception {
		String content = json.writeValueAsString(body);
		MvcResult r = mvc.perform(post("/v1/voice-sessions/{id}/recordings", sessionId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(content)).andReturn();
		ContractSamples.record("submitRecordingMetadata", content, r);
		return r;
	}

	MvcResult upload(TestTokens.Builder who, String sessionId, String recordingId, byte[] audio, String sha256,
			String contentType) throws Exception {
		MvcResult r = mvc.perform(put("/v1/voice-sessions/{s}/recordings/{r}/content", sessionId, recordingId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())
			.header("X-Content-SHA256", sha256)
			.contentType(contentType)
			.content(audio)).andReturn();
		ContractSamples.record("uploadRecordingContent", null, r);
		return r;
	}

	MvcResult withdraw(String eventId, List<String> scopes) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("withdrawal_id", Ids.of("cw"));
		body.put("scopes", scopes);
		body.put("withdrawn_at_utc", Instant.now().minusSeconds(5).toString());
		body.put("channel", "IN_PERSON_ASHA");
		return mvc.perform(post("/v1/consent-events/{id}/withdrawals", eventId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + asha.build())
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(body))).andReturn();
	}

	String newSession() throws Exception {
		String id = Ids.of("sess");
		MvcResult r = createSession(asha, Fixtures.session(id, participant, consentEvent, Instant.now().minusSeconds(60), "SYNTHETIC"),
				UUID.randomUUID());
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
		return id;
	}

	record Uploaded(String sessionId, String recordingId, byte[] audio, String sha256) {
	}

	Uploaded storedRecording() throws Exception {
		String sessionId = newSession();
		String recordingId = Ids.of("rec");
		byte[] audio = SyntheticAudio.wav(5);
		String sha = sha256(audio);
		assertThat(register(asha, sessionId, Fixtures.recording(recordingId, audio, sha, Instant.now().minusSeconds(30)))
			.getResponse().getStatus()).isEqualTo(201);
		MvcResult r = upload(asha, sessionId, recordingId, audio, sha, "audio/wav");
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
		return new Uploaded(sessionId, recordingId, audio, sha);
	}

	Map<String, Object> body(MvcResult r) throws Exception {
		return json.readValue(r.getResponse().getContentAsString(), new TypeReference<Map<String, Object>>() {
		});
	}

	void problem(MvcResult r, String code, int status) throws Exception {
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
		ContractProblemSchema.assertConforms(body(r), code, status);
	}

	static String sha256(byte[] data) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
	}

	int count(String sql, Object... params) {
		return jdbc.sql(sql).params(params).query(Integer.class).single();
	}

	// --- sessions ------------------------------------------------------------------

	@Test
	void createsASessionForAConsentedAssignedParticipant() throws Exception {
		String id = Ids.of("sess");
		UUID key = UUID.randomUUID();
		Map<String, Object> request = Fixtures.session(id, participant, consentEvent, Instant.now().minusSeconds(60), "SYNTHETIC");
		MvcResult r = createSession(asha, request, key);
		assertThat(r.getResponse().getStatus()).isEqualTo(201);
		assertThat(body(r)).containsEntry("status", "CREATED").containsEntry("session_id", id);
		assertThat(createSession(asha, request, key).getResponse().getStatus()).as("replay").isEqualTo(200);
	}

	@Test
	void sessionNeedsAudioRecordingConsentOnThatEvent() throws Exception {
		String p = Ids.of("ppt");
		String screeningOnly = recordConsent(asha, p, Set.of("SCREENING_PARTICIPATION"));
		String id = Ids.of("sess");
		MvcResult r = createSession(asha, Fixtures.session(id, p, screeningOnly, Instant.now().minusSeconds(60), "SYNTHETIC"),
				UUID.randomUUID());
		problem(r, "CONSENT_NOT_ACTIVE", 422);
		assertThat(count("SELECT count(*) FROM audit.audit_event WHERE action = 'VOICE_SESSION_REJECTED_CONSENT' AND resource_id = ?", id))
			.isEqualTo(1);
	}

	@Test
	void sessionRejectsAnotherParticipantsConsentEvent() throws Exception {
		String otherParticipant = Ids.of("ppt");
		recordConsent(asha, otherParticipant, Set.copyOf(Fixtures.ALL_SCOPES));
		problem(createSession(asha, Fixtures.session(Ids.of("sess"), otherParticipant, consentEvent,
				Instant.now().minusSeconds(60), "SYNTHETIC"), UUID.randomUUID()), "CONSENT_PARTICIPANT_MISMATCH", 422);
	}

	@Test
	void sessionCapturedBeforeConsentIsRejected() throws Exception {
		problem(createSession(asha, Fixtures.session(Ids.of("sess"), participant, consentEvent,
				consentedAt.minusSeconds(600), "SYNTHETIC"), UUID.randomUUID()), "CONSENT_NOT_ACTIVE", 422);
	}

	@Test
	void participantDataIsRefusedInThisEnvironment() throws Exception {
		problem(createSession(asha, Fixtures.session(Ids.of("sess"), participant, consentEvent,
				Instant.now().minusSeconds(60), "PARTICIPANT"), UUID.randomUUID()), "DATA_ORIGIN_NOT_PERMITTED", 422);
	}

	@Test
	void onlyTheAssignedWorkerCanCreateOrSeeSessions() throws Exception {
		problem(createSession(otherAsha, Fixtures.session(Ids.of("sess"), participant, consentEvent,
				Instant.now().minusSeconds(60), "SYNTHETIC"), UUID.randomUUID()), "FORBIDDEN", 403);
		String id = newSession();
		problem(getSession(otherAsha, id, null), "NOT_FOUND", 404);
		problem(getSession(admin, id, null), "VALIDATION_FAILED", 400);
		assertThat(getSession(admin, id, "SUPPORT_TICKET").getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void unknownFieldsAreRejected() throws Exception {
		Map<String, Object> request = Fixtures.session(Ids.of("sess"), participant, consentEvent, Instant.now().minusSeconds(60), "SYNTHETIC");
		request.put("device_id", "android-1234");
		problem(createSession(asha, request, UUID.randomUUID()), "VALIDATION_FAILED", 400);
	}

	// --- recordings and upload -----------------------------------------------------

	@Test
	void uploadStoresEncryptedAudioAndIsIdempotent() throws Exception {
		Uploaded u = storedRecording();
		Map<String, Object> session = body(getSession(asha, u.sessionId(), null));
		assertThat(session).containsEntry("status", "UPLOADED");
		assertThat(storage.exists("audio/" + u.recordingId())).isTrue();
		assertThat(storage.encryption("audio/" + u.recordingId())).isEqualTo("AES256");
		assertThat(recordingAudio.read(u.recordingId())).isEqualTo(u.audio());
		assertThat(recordingAudio.findStored(u.recordingId())).get()
			.satisfies(s -> assertThat(s.consentEventId()).isEqualTo(consentEvent));
		MvcResult again = upload(asha, u.sessionId(), u.recordingId(), u.audio(), u.sha256(), "audio/wav");
		assertThat(again.getResponse().getStatus()).as("identical re-upload").isEqualTo(200);
	}

	@Test
	void audioBytesNeverReachTheLogs(CapturedOutput output) throws Exception {
		storedRecording();
		List<String> leaking = output.getAll().lines().filter(l -> l.contains(SyntheticAudio.MARKER))
			.map(l -> l.substring(0, Math.min(l.length(), 400))).toList();
		assertThat(leaking).as("log lines containing audio bytes").isEmpty();
	}

	@Test
	void checksumOrSizeMismatchIsRejected() throws Exception {
		String sessionId = newSession();
		String recordingId = Ids.of("rec");
		byte[] audio = SyntheticAudio.wav(5);
		String sha = sha256(audio);
		register(asha, sessionId, Fixtures.recording(recordingId, audio, sha, Instant.now().minusSeconds(30)));
		byte[] tampered = audio.clone();
		tampered[500] ^= 1;
		problem(upload(asha, sessionId, recordingId, tampered, sha, "audio/wav"), "CHECKSUM_MISMATCH", 422);
		problem(upload(asha, sessionId, recordingId, audio, sha256(tampered), "audio/wav"), "CHECKSUM_MISMATCH", 422);
		byte[] longer = java.util.Arrays.copyOf(audio, audio.length + 10);
		problem(upload(asha, sessionId, recordingId, longer, sha256(longer), "audio/wav"), "CHECKSUM_MISMATCH", 422);
		assertThat(storage.exists("audio/" + recordingId)).isFalse();
	}

	@Test
	void wrongFormatIsRejected() throws Exception {
		String sessionId = newSession();
		String recordingId = Ids.of("rec");
		byte[] notWav = "this is not audio but has the right length".getBytes();
		register(asha, sessionId, Fixtures.recording(recordingId, notWav, sha256(notWav), Instant.now().minusSeconds(30)));
		problem(upload(asha, sessionId, recordingId, notWav, sha256(notWav), "audio/wav"), "UNSUPPORTED_MEDIA_TYPE", 415);
		problem(upload(asha, sessionId, recordingId, notWav, sha256(notWav), "text/plain"), "UNSUPPORTED_MEDIA_TYPE", 415);
	}

	@Test
	void oversizedUploadIsRefused() throws Exception {
		String sessionId = newSession();
		String recordingId = Ids.of("rec");
		byte[] audio = SyntheticAudio.wav(5);
		register(asha, sessionId, Fixtures.recording(recordingId, audio, sha256(audio), Instant.now().minusSeconds(30)));
		byte[] huge = new byte[26_214_401];
		problem(upload(asha, sessionId, recordingId, huge, sha256(audio), "audio/wav"), "PAYLOAD_TOO_LARGE", 413);
	}

	@Test
	void anotherWorkerCannotRegisterOrUpload() throws Exception {
		Uploaded u = storedRecording();
		byte[] audio = SyntheticAudio.wav(5);
		problem(register(otherAsha, u.sessionId(), Fixtures.recording(Ids.of("rec"), audio, sha256(audio), Instant.now())),
				"NOT_FOUND", 404);
		problem(upload(otherAsha, u.sessionId(), u.recordingId(), u.audio(), u.sha256(), "audio/wav"), "NOT_FOUND", 404);
	}

	@Test
	void unsupportedSamplingRateIsRejected() throws Exception {
		String sessionId = newSession();
		byte[] audio = SyntheticAudio.wav(5);
		Map<String, Object> request = Fixtures.recording(Ids.of("rec"), audio, sha256(audio), Instant.now());
		request.put("sampling_rate_hz", 8000);
		problem(register(asha, sessionId, request), "VALIDATION_FAILED", 400);
	}

	// --- withdrawal ----------------------------------------------------------------

	@Test
	void withdrawingAudioRecordingCancelsSessionsAndDeletesAudio() throws Exception {
		Uploaded u = storedRecording();
		String pendingSession = newSession();
		String pendingRecording = Ids.of("rec");
		byte[] audio = SyntheticAudio.wav(5);
		register(asha, pendingSession, Fixtures.recording(pendingRecording, audio, sha256(audio), Instant.now().minusSeconds(10)));

		MvcResult w = withdraw(consentEvent, List.of("AUDIO_RECORDING"));
		assertThat(w.getResponse().getStatus()).isEqualTo(202);
		assertThat(body(w).get("downstream_actions").toString()).contains("action=DELETE_RETAINED_AUDIO, status=SCHEDULED");

		assertThat(storage.exists("audio/" + u.recordingId())).as("deleted after commit").isFalse();
		assertThat(count("SELECT count(*) FROM voice.recording WHERE recording_id = ? AND upload_status = 'DELETED'", u.recordingId()))
			.isEqualTo(1);
		assertThat(body(getSession(asha, u.sessionId(), null))).containsEntry("status", "CANCELLED");
		assertThat(recordingAudio.findStored(u.recordingId())).isEmpty();
		problem(upload(asha, pendingSession, pendingRecording, audio, sha256(audio), "audio/wav"), "CONSENT_NOT_ACTIVE", 422);
		problem(register(asha, u.sessionId(), Fixtures.recording(Ids.of("rec"), audio, sha256(audio), Instant.now())),
				"CONSENT_NOT_ACTIVE", 422);
	}

	@Test
	void uploadRechecksConsentEvenIfTheSessionWasNotCancelled() throws Exception {
		// A withdrawal recorded without the voice-session handler running (e.g. by another
		// instance or a future channel): the session is still open, but consent is gone.
		String sessionId = newSession();
		String recordingId = Ids.of("rec");
		byte[] audio = SyntheticAudio.wav(5);
		register(asha, sessionId, Fixtures.recording(recordingId, audio, sha256(audio), Instant.now().minusSeconds(10)));
		String withdrawalId = Ids.of("cw");
		jdbc.sql("""
				INSERT INTO consent.withdrawal (withdrawal_id, consent_event_id, withdrawn_at, channel, recorded_by_subject)
				VALUES (?, ?, now(), 'WRITTEN_REQUEST', 'test-subject')
				""").params(withdrawalId, consentEvent).update();
		jdbc.sql("INSERT INTO consent.withdrawn_scope VALUES (?, 'AUDIO_RECORDING')").param(withdrawalId).update();

		problem(upload(asha, sessionId, recordingId, audio, sha256(audio), "audio/wav"), "CONSENT_NOT_ACTIVE", 422);
		assertThat(storage.exists("audio/" + recordingId)).isFalse();
	}

	@Test
	void withdrawingResearchReuseLeavesAudioInPlace() throws Exception {
		Uploaded u = storedRecording();
		MvcResult w = withdraw(consentEvent, List.of("RESEARCH_FEATURE_REUSE"));
		assertThat(w.getResponse().getStatus()).isEqualTo(202);
		assertThat(body(w).get("downstream_actions").toString()).doesNotContain("DELETE_RETAINED_AUDIO");
		assertThat(storage.exists("audio/" + u.recordingId())).isTrue();
	}

	@Test
	void withdrawingOnlyRetentionIsNotApplicableInV1() throws Exception {
		Uploaded u = storedRecording();
		MvcResult w = withdraw(consentEvent, List.of("AUDIO_RETENTION"));
		assertThat(body(w).get("downstream_actions").toString()).contains("action=DELETE_RETAINED_AUDIO, status=NOT_APPLICABLE");
		assertThat(storage.exists("audio/" + u.recordingId())).isTrue();
	}

}
