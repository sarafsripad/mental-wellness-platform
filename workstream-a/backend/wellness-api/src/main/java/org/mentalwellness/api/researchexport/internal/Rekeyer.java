package org.mentalwellness.api.researchexport.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Re-keys application IDs for export (ADR-010): {@code prefix_} + the first 128 bits of
 * HMAC-SHA-256(key, prefix:id). Stable across exports with the same key, so Workstream B can link
 * a record over time, but irreversible and unlinkable to application IDs without the key.
 */
final class Rekeyer {

	static final String METHOD = "HMAC-SHA256-TRUNC128-V1";

	private final SecretKeySpec key;

	private final String keyId;

	Rekeyer(String key) {
		if (key == null || key.length() < 32) {
			throw new IllegalStateException("wellness.export.key (WELLNESS_EXPORT_KEY) must be at least 32 characters");
		}
		this.key = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		try {
			this.keyId = "key_" + HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(("key-id:" + key).getBytes(StandardCharsets.UTF_8)))
				.substring(0, 16);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	String keyId() {
		return this.keyId;
	}

	/** Re-keys an ID of the form {@code prefix_hex}, keeping the prefix so records still validate. */
	String rekey(String id) {
		String prefix = id.substring(0, id.indexOf('_'));
		return prefix + "_" + hmac(prefix + ":" + id);
	}

	/** Participant-level split group (never the participant reference itself). */
	String group(String participantRef) {
		return "grp_" + hmac("grp:" + participantRef);
	}

	private String hmac(String value) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(this.key);
			return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
