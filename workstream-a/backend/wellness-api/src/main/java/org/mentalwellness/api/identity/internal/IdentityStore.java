package org.mentalwellness.api.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The identity_db connection. Deliberately not a {@code DataSource} bean: no other module can
 * inject it, and the application's own DataSource, transactions and Flyway stay on app_db.
 * Runs identity_db's migrations (classpath:db/identity-migration) on startup.
 */
class IdentityStore implements DisposableBean {

	/** No look-alike characters (0/O, 1/I), so a card code can be read out or typed reliably. */
	private static final char[] CARD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

	private final HikariDataSource dataSource;

	private final JdbcClient jdbc;

	private final IdentityCipher cipher;

	private final SecretKeySpec indexKey;

	private final SecureRandom random = new SecureRandom();

	IdentityStore(IdentityProperties properties) {
		HikariConfig config = new HikariConfig();
		config.setPoolName("identity");
		config.setJdbcUrl(properties.url());
		config.setUsername(properties.username());
		config.setPassword(properties.password());
		config.setMaximumPoolSize(4);
		this.dataSource = new HikariDataSource(config);
		Flyway.configure()
			.dataSource(this.dataSource)
			.locations("classpath:db/identity-migration")
			.schemas("identity")
			.createSchemas(true)
			.load()
			.migrate();
		this.jdbc = JdbcClient.create(this.dataSource);
		this.cipher = new IdentityCipher(properties.keyBytes());
		this.indexKey = new SecretKeySpec(properties.indexKeyBytes(), "HmacSHA256");
	}

	record Person(String displayName, String villageName, String contactPhone, String patientCardCode,
			String consentEventId) {

		@Override
		public String toString() {
			return "Person[<redacted>]";
		}

	}

	/**
	 * Creates or replaces the record (upsert keyed by participant_ref), so retries are harmless. A
	 * phone left out keeps any phone already stored. The card code is assigned once and never
	 * changes. Returns the card code.
	 */
	String put(String participantRef, String consentEventId, String dataOrigin, String displayName, String villageName,
			String contactPhone, String subject) {
		byte[] phone = contactPhone == null ? null : this.cipher.encrypt(participantRef, "contact_phone", contactPhone);
		this.jdbc.sql("""
				INSERT INTO identity.person (participant_ref, consent_event_id, data_origin, display_name_enc,
				  village_name_enc, contact_phone_enc, key_version, stored_by_subject, patient_card_code, search_index)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (participant_ref) DO UPDATE SET consent_event_id = EXCLUDED.consent_event_id,
				  data_origin = EXCLUDED.data_origin, display_name_enc = EXCLUDED.display_name_enc,
				  village_name_enc = EXCLUDED.village_name_enc,
				  contact_phone_enc = COALESCE(EXCLUDED.contact_phone_enc, identity.person.contact_phone_enc),
				  key_version = EXCLUDED.key_version, stored_by_subject = EXCLUDED.stored_by_subject,
				  patient_card_code = COALESCE(identity.person.patient_card_code, EXCLUDED.patient_card_code),
				  search_index = EXCLUDED.search_index, updated_at = now()
				""")
			.params(participantRef, consentEventId, dataOrigin,
					this.cipher.encrypt(participantRef, "display_name", displayName),
					this.cipher.encrypt(participantRef, "village_name", villageName), phone, IdentityCipher.KEY_VERSION, subject,
					newCardCode(), searchIndex(displayName, villageName))
			.update();
		return this.jdbc.sql("SELECT patient_card_code FROM identity.person WHERE participant_ref = ?")
			.param(participantRef)
			.query(String.class)
			.single();
	}

	/** Decrypts one record; only the audited lookup calls this. */
	Optional<Person> read(String participantRef) {
		record Row(byte[] displayNameEnc, byte[] villageNameEnc, byte[] contactPhoneEnc, String patientCardCode,
				String consentEventId) {
		}
		return this.jdbc.sql("""
				SELECT display_name_enc, village_name_enc, contact_phone_enc, patient_card_code, consent_event_id
				FROM identity.person WHERE participant_ref = ?
				""")
			.param(participantRef)
			.query(Row.class)
			.optional()
			.map(r -> new Person(this.cipher.decrypt(participantRef, "display_name", r.displayNameEnc()),
					this.cipher.decrypt(participantRef, "village_name", r.villageNameEnc()),
					r.contactPhoneEnc() == null ? null : this.cipher.decrypt(participantRef, "contact_phone", r.contactPhoneEnc()),
					r.patientCardCode(), r.consentEventId()));
	}

	Optional<String> consentEventOf(String participantRef) {
		return this.jdbc.sql("SELECT consent_event_id FROM identity.person WHERE participant_ref = ?")
			.param(participantRef)
			.query(String.class)
			.optional();
	}

	List<String> byCardCode(String code) {
		return this.jdbc.sql("SELECT participant_ref FROM identity.person WHERE patient_card_code = ?")
			.param(code)
			.query(String.class)
			.list();
	}

	/** Exact match on the normalised name and village, through the blind index only. */
	List<String> byNameAndVillage(String displayName, String villageName) {
		return this.jdbc.sql("SELECT participant_ref FROM identity.person WHERE search_index = ? ORDER BY updated_at DESC LIMIT 20")
			.param(searchIndex(displayName, villageName))
			.query(String.class)
			.list();
	}

	/** HMAC over the NFKC, lower-cased, whitespace-collapsed name and village. */
	String searchIndex(String displayName, String villageName) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(this.indexKey);
			return HexFormat.of().formatHex(mac.doFinal((normalise(displayName) + "|" + normalise(villageName))
				.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
		}
	}

	static String normalise(String s) {
		return Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
	}

	private String newCardCode() {
		byte[] bytes = new byte[8];
		this.random.nextBytes(bytes);
		StringBuilder code = new StringBuilder(8);
		for (byte b : bytes) {
			code.append(CARD_ALPHABET[(b & 0xff) % CARD_ALPHABET.length]);
		}
		return code.toString();
	}

	JdbcClient jdbc() {
		return this.jdbc;
	}

	IdentityCipher cipher() {
		return this.cipher;
	}

	@Override
	public void destroy() {
		this.dataSource.close();
	}

}
