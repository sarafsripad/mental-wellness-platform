package org.mentalwellness.api.support;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Drives the public API through real HTTP calls to set up realistic state for later steps. */
public final class ApiDriver {

	private final MockMvc mvc;

	private final ObjectMapper json;

	public ApiDriver(MockMvc mvc, ObjectMapper json) {
		this.mvc = mvc;
		this.json = json;
	}

	public record Participant(String participantRef, String consentEventId, Instant consentedAt) {
	}

	public record Recording(Participant participant, String sessionId, String recordingId, byte[] audio, String sha256) {
	}

	public Participant consentedParticipant(TestTokens.Builder asha) throws Exception {
		Participant p = new Participant(Ids.of("ppt"), Ids.of("ce"), Instant.now().minusSeconds(7200));
		expect(201, postJson("/v1/consent-events", asha,
				Fixtures.consent(p.consentEventId(), p.participantRef(), Set.copyOf(Fixtures.ALL_SCOPES), p.consentedAt())));
		return p;
	}

	/** A recording registered but not uploaded. */
	public Recording registeredRecording(TestTokens.Builder asha, Participant p) throws Exception {
		String sessionId = Ids.of("sess");
		expect(201, postJson("/v1/voice-sessions", asha,
				Fixtures.session(sessionId, p.participantRef(), p.consentEventId(), Instant.now().minusSeconds(120), "SYNTHETIC")));
		String recordingId = Ids.of("rec");
		byte[] audio = SyntheticAudio.wav(5);
		String sha = sha256(audio);
		expect(201, postJson("/v1/voice-sessions/" + sessionId + "/recordings", asha,
				Fixtures.recording(recordingId, audio, sha, Instant.now().minusSeconds(60))));
		return new Recording(p, sessionId, recordingId, audio, sha);
	}

	/** A recording with its audio uploaded and stored. */
	public Recording storedRecording(TestTokens.Builder asha, Participant p) throws Exception {
		Recording r = registeredRecording(asha, p);
		expect(200, this.mvc.perform(put("/v1/voice-sessions/{s}/recordings/{r}/content", r.sessionId(), r.recordingId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + asha.build())
			.header("X-Content-SHA256", r.sha256())
			.contentType("audio/wav")
			.content(r.audio())).andReturn());
		return r;
	}

	public MvcResult withdraw(TestTokens.Builder asha, String consentEventId, List<String> scopes) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("withdrawal_id", Ids.of("cw"));
		body.put("scopes", scopes);
		body.put("withdrawn_at_utc", Instant.now().minusSeconds(5).toString());
		body.put("channel", "IN_PERSON_ASHA");
		return postJson("/v1/consent-events/" + consentEventId + "/withdrawals", asha, body);
	}

	public MvcResult postJson(String path, TestTokens.Builder who, Object body) throws Exception {
		return this.mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(this.json.writeValueAsString(body))).andReturn();
	}

	public Map<String, Object> body(MvcResult r) throws Exception {
		return this.json.readValue(r.getResponse().getContentAsString(), new TypeReference<Map<String, Object>>() {
		});
	}

	private static MvcResult expect(int status, MvcResult r) throws Exception {
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
		return r;
	}

	public static String sha256(byte[] data) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
	}

}
