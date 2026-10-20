package org.mentalwellness.api.identity.internal;

import java.util.HexFormat;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection to identity_db and the field-encryption key. No defaults: run-local.sh reads them
 * from deploy/local/.env. The identity database has its own role, which cannot reach app_db.
 *
 * @param url      JDBC URL of identity_db ({@code WELLNESS_IDENTITY_DB_URL})
 * @param username identity_db role ({@code WELLNESS_IDENTITY_DB_USER})
 * @param password its password ({@code WELLNESS_IDENTITY_DB_PASSWORD})
 * @param key      AES-256 key, 64 hex characters ({@code WELLNESS_IDENTITY_KEY})
 * @param indexKey HMAC key for the blind search index, 64 hex characters ({@code WELLNESS_IDENTITY_INDEX_KEY})
 */
@ConfigurationProperties("wellness.identity")
record IdentityProperties(String url, String username, String password, String key, String indexKey) {

	IdentityProperties {
		require(url, "url (WELLNESS_IDENTITY_DB_URL)");
		require(username, "username (WELLNESS_IDENTITY_DB_USER)");
		require(password, "password (WELLNESS_IDENTITY_DB_PASSWORD)");
		require(key, "key (WELLNESS_IDENTITY_KEY)");
		if (!key.matches("^[0-9a-fA-F]{64}$")) {
			throw new IllegalArgumentException("wellness.identity.key must be 64 hex characters (256 bits)");
		}
		require(indexKey, "index-key (WELLNESS_IDENTITY_INDEX_KEY)");
		if (!indexKey.matches("^[0-9a-fA-F]{64}$") || indexKey.equalsIgnoreCase(key)) {
			throw new IllegalArgumentException("wellness.identity.index-key must be 64 hex characters and differ from the key");
		}
	}

	byte[] indexKeyBytes() {
		return HexFormat.of().parseHex(this.indexKey);
	}

	byte[] keyBytes() {
		return HexFormat.of().parseHex(this.key);
	}

	private static void require(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("wellness.identity." + name + " must be set");
		}
	}

}
