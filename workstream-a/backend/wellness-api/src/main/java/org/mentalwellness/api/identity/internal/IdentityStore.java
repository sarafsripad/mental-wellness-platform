package org.mentalwellness.api.identity.internal;

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

	private final HikariDataSource dataSource;

	private final JdbcClient jdbc;

	private final IdentityCipher cipher;

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
	}

	/** Creates or replaces the record (upsert keyed by participant_ref), so retries are harmless. */
	void put(String participantRef, String consentEventId, String dataOrigin, String displayName, String villageName,
			String subject) {
		this.jdbc.sql("""
				INSERT INTO identity.person (participant_ref, consent_event_id, data_origin, display_name_enc,
				  village_name_enc, key_version, stored_by_subject)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (participant_ref) DO UPDATE SET consent_event_id = EXCLUDED.consent_event_id,
				  data_origin = EXCLUDED.data_origin, display_name_enc = EXCLUDED.display_name_enc,
				  village_name_enc = EXCLUDED.village_name_enc, key_version = EXCLUDED.key_version,
				  stored_by_subject = EXCLUDED.stored_by_subject, updated_at = now()
				""")
			.params(participantRef, consentEventId, dataOrigin,
					this.cipher.encrypt(participantRef, "display_name", displayName),
					this.cipher.encrypt(participantRef, "village_name", villageName), IdentityCipher.KEY_VERSION, subject)
			.update();
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
