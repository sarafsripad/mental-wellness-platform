package org.mentalwellness.api.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Synthetic request bodies matching wellness-api.v1.yaml. */
public final class Fixtures {

	public static final String USER_SCOPES = "consent:read consent:write voice:read voice:write processing:read processing:write cases:read cases:write";

	public static final List<String> ALL_SCOPES = List.of("SCREENING_PARTICIPATION", "AUDIO_RECORDING", "AUDIO_RETENTION",
			"RESEARCH_FEATURE_REUSE", "MODEL_TRAINING", "QUALITY_REVIEW");

	private Fixtures() {
	}

	public static Map<String, Object> consent(String eventId, String participant, Set<String> granted, Instant capturedAt) {
		List<Map<String, Object>> decisions = new ArrayList<>();
		for (String s : ALL_SCOPES) {
			decisions.add(Map.of("scope", s, "granted", granted.contains(s)));
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("consent_event_id", eventId);
		body.put("participant_ref", participant);
		body.put("consent_form", Map.of("form_id", "screening-consent", "form_version", "0.1.0", "language_tag", "kn-IN"));
		body.put("scope_decisions", decisions);
		body.put("capture", Map.of("captured_at_utc", capturedAt.toString(), "method", "SIGNED_ON_DEVICE", "read_aloud", true,
				"comprehension_confirmed", true));
		body.put("safeguards", Map.of("adult_confirmed", true));
		return body;
	}

	public static Map<String, Object> session(String sessionId, String participant, String eventId, Instant capturedAt,
			String dataOrigin) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("session_id", sessionId);
		body.put("participant_ref", participant);
		body.put("consent_event_id", eventId);
		body.put("language_tag", "kn-IN");
		body.put("prompt_set", Map.of("prompt_set_id", "baseline-voice-tasks", "prompt_set_version", "0.1.0"));
		body.put("captured_at_utc", capturedAt.toString());
		body.put("device", Map.of("device_class", "ANDROID_PHONE", "app_version", "0.1.0", "os_family", "ANDROID"));
		body.put("data_origin", dataOrigin);
		return body;
	}

	public static Map<String, Object> recording(String recordingId, byte[] audio, String sha256, Instant recordedAt) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("recording_id", recordingId);
		body.put("prompt_id", "sustained-vowel-a");
		body.put("format", "WAV_PCM_S16LE");
		body.put("sampling_rate_hz", 16000);
		body.put("channels", 1);
		body.put("duration_seconds", 5.0);
		body.put("size_bytes", audio.length);
		body.put("sha256", sha256);
		body.put("recorded_at_utc", recordedAt.toString());
		body.put("device_prescreen", Map.of("peak_dbfs", -6.0, "clipping_detected", false, "low_level_detected", false));
		return body;
	}


	// --- ASHA assessment cases (API 1.1) --------------------------------------------

	/** Consent on the ASHA form: SCREENING_PARTICIPATION only. */
	public static Map<String, Object> ashaConsent(String eventId, String participant, String language, Instant capturedAt) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("consent_event_id", eventId);
		body.put("participant_ref", participant);
		body.put("consent_form", Map.of("form_id", "asha-wellness-consent", "form_version", "0.1.0", "language_tag", language));
		body.put("scope_decisions", List.of(Map.of("scope", "SCREENING_PARTICIPATION", "granted", true)));
		body.put("capture", Map.of("captured_at_utc", capturedAt.toString(), "method", "VERBAL_WITNESSED", "read_aloud", true,
				"comprehension_confirmed", true, "witness_present", true));
		body.put("safeguards", Map.of("adult_confirmed", true));
		return body;
	}

	public static Map<String, Object> caseCreate(String caseId, String participant, String eventId, Instant openedAt) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("case_id", caseId);
		body.put("participant_ref", participant);
		body.put("consent_event_id", eventId);
		body.put("language_tag", "te-IN");
		body.put("opened_at_utc", openedAt.toString());
		body.put("data_origin", "SYNTHETIC");
		body.put("age_band", "AGE_30_44");
		body.put("gender", "NOT_STATED");
		return body;
	}

	public static Map<String, Object> observations(String setId, Instant observedAt, List<Map<String, Object>> observations) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("observation_set_id", setId);
		body.put("catalogue", Map.of("catalogue_id", "asha-observations", "catalogue_version", "0.1.0"));
		body.put("observed_at_utc", observedAt.toString());
		body.put("observations", observations);
		return body;
	}

	public static List<Map<String, Object>> typicalObservations() {
		return List.of(Map.of("code", "HEALTH_CONDITION", "value_code", "HYPERTENSION"),
				Map.of("code", "HEALTH_CONDITION", "value_code", "DIABETES"),
				Map.of("code", "TOBACCO_USE", "value_code", "NOT_REPORTED"),
				Map.of("code", "ALCOHOL_USE", "value_code", "DECLINED"),
				Map.of("code", "SLEEP_QUALITY", "value_code", "SOME_DIFFICULTY"));
	}

	public static Map<String, Object> questionnaire(String responseId, Instant answeredAt, String safetyAnswer) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("response_id", responseId);
		body.put("instrument", Map.of("instrument_id", "illustrative-wellness", "instrument_version", "0.1.0"));
		body.put("language_tag", "te-IN");
		body.put("answered_at_utc", answeredAt.toString());
		body.put("items", List.of(Map.of("item_id", "ANXIETY", "answer_code", "SEVERAL_DAYS"),
				Map.of("item_id", "LOW_MOOD", "answer_code", "NOT_AT_ALL"),
				Map.of("item_id", "INTEREST", "answer_code", "AS_USUAL"),
				Map.of("item_id", "SELF_HARM_THOUGHTS", "answer_code", safetyAnswer)));
		return body;
	}

	public static Map<String, Object> completion(Instant completedAt, boolean followUp) {
		return Map.of("completed_at_utc", completedAt.toString(), "follow_up_requested", followUp);
	}

	/** Invented values only. */
	public static Map<String, Object> identity(String eventId, String displayName, String village) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("consent_event_id", eventId);
		body.put("data_origin", "SYNTHETIC");
		body.put("display_name", displayName);
		body.put("village_name", village);
		return body;
	}

}
