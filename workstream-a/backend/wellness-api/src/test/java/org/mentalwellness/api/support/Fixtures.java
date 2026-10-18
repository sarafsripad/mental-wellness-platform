package org.mentalwellness.api.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Synthetic request bodies matching wellness-api.v1.yaml. */
public final class Fixtures {

	public static final String USER_SCOPES = "consent:read consent:write voice:read voice:write processing:read processing:write";

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

}
