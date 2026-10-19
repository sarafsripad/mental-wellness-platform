package org.mentalwellness.api.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Derives stable pseudonyms with a keyed hash (HMAC-SHA-256), so a pseudonym can't be reversed or
 * recomputed without the key. Used for the {@code wkr_} worker and {@code clin_} clinician references in API responses.
 */
@Component
@EnableConfigurationProperties(Pseudonyms.Properties.class)
public class Pseudonyms {

	private final SecretKeySpec key;

	public Pseudonyms(Properties properties) {
		this.key = new SecretKeySpec(properties.key().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	/** {@code wkr_} + 32 hex characters for a worker's token subject. */
	public String workerRef(String subject) {
		return "wkr_" + hmacHex("worker:" + subject).substring(0, 32);
	}

	/** {@code clin_} + 32 hex characters for a clinician's token subject. */
	public String clinicianRef(String subject) {
		return "clin_" + hmacHex("clinician:" + subject).substring(0, 32);
	}

	private String hmacHex(String value) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(this.key);
			return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
		}
	}

	/** @param key secret of at least 32 characters ({@code WELLNESS_PSEUDONYM_KEY}); no default */
	@Validated
	@ConfigurationProperties("wellness.pseudonyms")
	public record Properties(@Size(min = 32) String key) {

		public Properties {
			if (key == null || key.isBlank()) {
				throw new IllegalArgumentException("wellness.pseudonyms.key (WELLNESS_PSEUDONYM_KEY) must be set");
			}
		}

	}

}
