package org.mentalwellness.api.encounters;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mentalwellness.api.IntegrationTest;
import org.mentalwellness.api.support.ApiDriver;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Clinical notes switched on (ADR-016): encrypted, facility-clinical only, never echoed or logged. */
@IntegrationTest
@TestPropertySource(properties = { "wellness.clinical-notes.enabled=true",
		"wellness.clinical-notes.key=00000000000000000000000000000000000000000000000000000000000000ab" })
@ExtendWith(OutputCaptureExtension.class)
class ClinicalNotesTests {

	static final String NOTE = "Synthetic note Sigma-4471: sleeping poorly, plan follow-up";

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired JdbcClient jdbc;

	final TestTokens.Builder admin = TestTokens.token().roles("ADMIN").scope("careteam:admin");
	final TestTokens.Builder doctor = TestTokens.token().roles("CLINICIAN").scope(Fixtures.STAFF_SCOPES);
	final TestTokens.Builder counsellor = TestTokens.token().roles("COUNSELLOR").scope(Fixtures.STAFF_SCOPES);
	final TestTokens.Builder nurse = TestTokens.token().roles("HEALTH_STAFF").scope(Fixtures.STAFF_SCOPES);
	final TestTokens.Builder otherSiteDoctor = TestTokens.token().roles("CLINICIAN").scope(Fixtures.STAFF_SCOPES);

	@Test
	void notesAreCiphertextAndOnlyClinicalStaffAtTheFacilitySeeThem(CapturedOutput output) throws Exception {
		ApiDriver driver = new ApiDriver(this.mvc, this.json);
		String facility = "phc-" + UUID.randomUUID().toString().substring(0, 8);
		String second = "tele-" + UUID.randomUUID().toString().substring(0, 8);
		adminPut("/v1/admin/facilities/" + facility, Map.of("kind", "PHC", "facility_label", "Notes PHC", "active", true));
		adminPut("/v1/admin/facilities/" + second, Map.of("kind", "TELE_CENTRE", "facility_label", "Notes tele", "active", true));
		adminPut("/v1/admin/facilities/" + facility + "/memberships/" + this.doctor.subject(), Map.of("care_role", "CLINICIAN", "active", true));
		adminPut("/v1/admin/facilities/" + facility + "/memberships/" + this.counsellor.subject(), Map.of("care_role", "COUNSELLOR", "active", true));
		adminPut("/v1/admin/facilities/" + facility + "/memberships/" + this.nurse.subject(), Map.of("care_role", "HEALTH_STAFF", "active", true));
		adminPut("/v1/admin/facilities/" + second + "/memberships/" + this.otherSiteDoctor.subject(), Map.of("care_role", "CLINICIAN", "active", true));

		String ppt = Ids.of("ppt");
		String ce = Ids.of("ce");
		assertThat(driver.postJson("/v1/consent-events", this.doctor, Fixtures.clinicConsent(ce, ppt, facility, Instant.now().minusSeconds(600)))
			.getResponse().getStatus()).isEqualTo(201);
		// The second facility joins the same person's care team (tele follow-up).
		assertThat(driver.postJson("/v1/consent-events", this.otherSiteDoctor, Fixtures.clinicConsent(Ids.of("ce"), ppt, second,
				Instant.now().minusSeconds(500))).getResponse().getStatus()).as("not yet on the care team").isEqualTo(403);
		assertThat(this.mvc.perform(put("/v1/participants/{p}/care-team/{f}", ppt, second)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + this.admin.build()).header("X-Access-Reason", "SUPPORT_TICKET")
			.contentType(MediaType.APPLICATION_JSON)
			.content(this.json.writeValueAsString(Map.of("reason_code", "TRANSFER", "consent_event_id", ce)))).andReturn()
			.getResponse().getStatus()).isEqualTo(200);

		String enc = Ids.of("enc");
		assertThat(post(this.doctor, "/v1/encounters", Fixtures.encounter(enc, ppt, ce, facility, "IN_PERSON", Instant.now().minusSeconds(60)))
			.getResponse().getStatus()).isEqualTo(201);
		String noteId = Ids.of("note");
		MvcResult stored = post(this.doctor, "/v1/encounters/" + enc + "/notes", Map.of("note_id", noteId, "text", NOTE));
		assertThat(stored.getResponse().getStatus()).isEqualTo(201);
		assertThat(stored.getResponse().getContentAsString()).doesNotContain("Sigma");
		assertThat(post(this.nurse, "/v1/encounters/" + enc + "/notes", Map.of("note_id", Ids.of("note"), "text", "x"))
			.getResponse().getStatus()).isEqualTo(403);

		byte[] sealed = this.jdbc.sql("SELECT note_enc FROM encounters.clinical_note WHERE note_id = ?").param(noteId).query(byte[].class).single();
		assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("Sigma");

		assertThat(read(this.doctor, enc)).contains(NOTE);
		assertThat(read(this.counsellor, enc)).contains(NOTE);
		assertThat(read(this.nurse, enc)).doesNotContain("Sigma");
		assertThat(read(this.otherSiteDoctor, enc)).as("care team, but not the encounter's facility").doesNotContain("Sigma");

		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_event a WHERE a::text LIKE '%Sigma%'").query(Integer.class).single()).isZero();
		assertThat(this.jdbc.sql("SELECT action FROM audit.audit_event WHERE resource_id = ?").param(enc).query(String.class).list())
			.contains("CLINICAL_NOTE_READ");
		assertThat(output.getAll()).doesNotContain("Sigma-4471");
		assertThat(List.of(new org.mentalwellness.api.shared.security.FieldCipher(
				"00000000000000000000000000000000000000000000000000000000000000ab").decrypt("note:v1:" + noteId + ":" + enc, sealed)))
			.containsExactly(NOTE);
	}

	void adminPut(String path, Map<String, Object> body) throws Exception {
		MvcResult r = this.mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + this.admin.build()).header("X-Access-Reason", "AUDIT")
			.contentType(MediaType.APPLICATION_JSON).content(this.json.writeValueAsString(body))).andReturn();
		assertThat(r.getResponse().getStatus()).as(path).isEqualTo(200);
	}

	MvcResult post(TestTokens.Builder who, String path, Map<String, Object> body) throws Exception {
		return this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build()).header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON).content(this.json.writeValueAsString(body))).andReturn();
	}

	String read(TestTokens.Builder who, String enc) throws Exception {
		MvcResult r = this.mvc.perform(get("/v1/encounters/{e}", enc).header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())).andReturn();
		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

}
