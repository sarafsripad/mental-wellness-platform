package org.mentalwellness.api.ashaapp;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings the ASHA app reads at start-up (ADR-017). Nothing about any person or worker.
 *
 * <p>{@code microphone_enabled} is off by default: the app then records a generated synthetic voice,
 * so no real voice is ever labelled SYNTHETIC. Turning it on needs privacy-lead approval.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty("wellness.asha-app.enabled")
class AshaAppSettings {

	private final boolean microphoneEnabled;

	AshaAppSettings(@Value("${wellness.asha-app.microphone-enabled:false}") boolean microphoneEnabled) {
		this.microphoneEnabled = microphoneEnabled;
	}

	@GetMapping(AshaAppConfig.PATH + "settings.json")
	ResponseEntity<Map<String, Object>> settings() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("microphone_enabled", this.microphoneEnabled));
	}

}
