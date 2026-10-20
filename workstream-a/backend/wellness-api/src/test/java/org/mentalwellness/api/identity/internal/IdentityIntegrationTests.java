package org.mentalwellness.api.identity.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mentalwellness.api.IntegrationTest;
import org.mentalwellness.api.support.ApiDriver;
import org.mentalwellness.api.support.ContractProblemSchema;
import org.mentalwellness.api.support.ContractSamples;
import org.mentalwellness.api.support.Fixtures;
import org.mentalwellness.api.support.Ids;
import org.mentalwellness.api.support.TestTokens;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** identity-api.v1.yaml: write-only, consent first, ciphertext only, never echoed (ADR-010, ADR-013). */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class IdentityIntegrationTests {

	static final String NAME = "Synthetic Person Zeta-7731";
	static final String VILLAGE = "Testgrama-4410";

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired JdbcClient appDb;
	@Autowired IdentityStore store;

	final TestTokens.Builder asha = TestTokens.token().roles("ASHA_WORKER").scope(Fixtures.USER_SCOPES);
	final TestTokens.Builder otherAsha = TestTokens.token().roles("ASHA_WORKER").scope(Fixtures.USER_SCOPES);

	ApiDriver driver;
	String participant;
	String consentEvent;

	@BeforeEach
	void consentedParticipant() throws Exception {
		this.driver = new ApiDriver(this.mvc, this.json);
		this.participant = Ids.of("ppt");
		this.consentEvent = Ids.of("ce");
		assertThat(this.driver.postJson("/v1/consent-events", this.asha,
				Fixtures.ashaConsent(this.consentEvent, this.participant, "hi-IN", Instant.now().minusSeconds(600)))
			.getResponse().getStatus()).isEqualTo(201);
	}

	@Test
	void storesCiphertextOnlyAndReturnsNothing(CapturedOutput output) throws Exception {
		MvcResult r = putIdentity(this.asha, this.participant, Fixtures.identity(this.consentEvent, NAME, VILLAGE));
		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		assertThat(r.getResponse().getContentAsString()).matches("\\{\"patient_card_code\":\"[A-Z2-9]{8}\"\\}")
			.doesNotContain("Zeta").doesNotContain("Testgrama");

		record Row(byte[] displayNameEnc, byte[] villageNameEnc, String dataOrigin) {
		}
		Row row = this.store.jdbc()
			.sql("SELECT display_name_enc, village_name_enc, data_origin FROM identity.person WHERE participant_ref = ?")
			.param(this.participant)
			.query(Row.class)
			.single();
		assertThat(new String(row.displayNameEnc(), StandardCharsets.ISO_8859_1)).doesNotContain("Zeta");
		assertThat(new String(row.villageNameEnc(), StandardCharsets.ISO_8859_1)).doesNotContain("Testgrama");
		assertThat(this.store.cipher().decrypt(this.participant, "display_name", row.displayNameEnc())).isEqualTo(NAME);
		assertThat(this.store.cipher().decrypt(this.participant, "village_name", row.villageNameEnc())).isEqualTo(VILLAGE);
		assertThat(row.dataOrigin()).isEqualTo("SYNTHETIC");

		// Bound to its row and column: moving ciphertext elsewhere does not decrypt.
		assertThatThrownBy(() -> this.store.cipher().decrypt(Ids.of("ppt"), "display_name", row.displayNameEnc()))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> this.store.cipher().decrypt(this.participant, "village_name", row.displayNameEnc()))
			.isInstanceOf(IllegalStateException.class);

		assertThat(this.appDb.sql("SELECT action FROM audit.audit_event WHERE resource_id = ?")
			.param(this.participant).query(String.class).list()).contains("IDENTITY_RECORD_STORED");
		assertThat(this.appDb.sql("SELECT count(*) FROM audit.audit_event a WHERE a::text LIKE '%Zeta%' OR a::text LIKE '%Testgrama%'")
			.query(Integer.class).single()).isZero();
		assertThat(output.getAll()).doesNotContain("Zeta-7731").doesNotContain("Testgrama");
	}

	@Test
	void appDatabaseNeverHoldsTheValues() throws Exception {
		assertThat(putIdentity(this.asha, this.participant, Fixtures.identity(this.consentEvent, NAME, VILLAGE))
			.getResponse().getStatus()).isEqualTo(200);
		List<String> hits = this.appDb.sql("""
				SELECT c.table_schema || '.' || c.table_name FROM information_schema.columns c
				WHERE c.table_schema IN ('consent', 'voice', 'processing', 'research_export', 'audit', 'cases', 'idempotency')
				GROUP BY 1
				""").query(String.class).list();
		for (String table : hits) {
			int found = this.appDb.sql("SELECT count(*) FROM " + table + " t WHERE t::text LIKE '%Zeta-7731%' OR t::text LIKE '%Testgrama%'")
				.query(Integer.class)
				.single();
			assertThat(found).as(table).isZero();
		}
	}

	@Test
	void replacingIsIdempotent() throws Exception {
		Map<String, Object> body = Fixtures.identity(this.consentEvent, NAME, VILLAGE);
		assertThat(putIdentity(this.asha, this.participant, body).getResponse().getStatus()).isEqualTo(200);
		assertThat(putIdentity(this.asha, this.participant, body).getResponse().getStatus()).isEqualTo(200);
		assertThat(this.store.jdbc().sql("SELECT count(*) FROM identity.person WHERE participant_ref = ?")
			.param(this.participant).query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void consentAndAssignmentComeFirst() throws Exception {
		Map<String, Object> body = Fixtures.identity(this.consentEvent, NAME, VILLAGE);
		assertThat(putIdentity(this.otherAsha, this.participant, body).getResponse().getStatus()).isEqualTo(403);

		ContractProblemSchema.assertConforms(this.driver.body(putIdentity(this.asha, this.participant,
				Fixtures.identity(Ids.of("ce"), NAME, VILLAGE))), "CONSENT_PARTICIPANT_MISMATCH", 422);

		assertThat(this.driver.withdraw(this.asha, this.consentEvent, List.of("SCREENING_PARTICIPATION"))
			.getResponse().getStatus()).isEqualTo(202);
		ContractProblemSchema.assertConforms(this.driver.body(putIdentity(this.asha, this.participant, body)),
				"CONSENT_NOT_ACTIVE", 422);
		assertThat(this.store.jdbc().sql("SELECT count(*) FROM identity.person WHERE participant_ref = ?")
			.param(this.participant).query(Integer.class).single()).isZero();
		assertThat(this.appDb.sql("SELECT action FROM audit.audit_event WHERE resource_id = ?")
			.param(this.participant).query(String.class).list()).contains("IDENTITY_RECORD_REJECTED");
	}

	@Test
	void onlySyntheticDataIsAccepted() throws Exception {
		Map<String, Object> body = Fixtures.identity(this.consentEvent, NAME, VILLAGE);
		body.put("data_origin", "PARTICIPANT");
		ContractProblemSchema.assertConforms(this.driver.body(putIdentity(this.asha, this.participant, body)),
				"DATA_ORIGIN_NOT_PERMITTED", 422);
	}

	@Test
	void validationErrorsNeverEchoTheValues() throws Exception {
		String tooLong = "Zeta-" + "x".repeat(120);
		MvcResult r = putIdentity(this.asha, this.participant, Fixtures.identity(this.consentEvent, tooLong, "   "));
		ContractProblemSchema.assertConforms(this.driver.body(r), "VALIDATION_FAILED", 400);
		assertThat(r.getResponse().getContentAsString()).doesNotContain("Zeta").doesNotContain("xxxx");
	}

	@Test
	void thereIsNoWayToReadIdentityBack() throws Exception {
		assertThat(putIdentity(this.asha, this.participant, Fixtures.identity(this.consentEvent, NAME, VILLAGE))
			.getResponse().getStatus()).isEqualTo(200);
		MvcResult r = this.mvc.perform(get("/v1/participants/{p}/identity", this.participant)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + this.asha.build())).andReturn();
		assertThat(r.getResponse().getStatus()).isEqualTo(405);
		assertThat(r.getResponse().getContentAsString()).doesNotContain("Zeta");
		assertThat(new IdentityApi.IdentityRecordPut(this.consentEvent, IdentityApi.DataOrigin.SYNTHETIC, NAME, VILLAGE)
			.toString()).doesNotContain("Zeta").doesNotContain("Testgrama");
	}

	MvcResult putIdentity(TestTokens.Builder who, String participantRef, Map<String, Object> body) throws Exception {
		String content = this.json.writeValueAsString(body);
		MvcResult r = this.mvc.perform(put("/v1/participants/{p}/identity", participantRef)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())
			.contentType(MediaType.APPLICATION_JSON)
			.content(content)).andReturn();
		ContractSamples.record("identity-api.v1.yaml", "putParticipantIdentity", content, r);
		return r;
	}

}
