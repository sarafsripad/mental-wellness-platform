package org.mentalwellness.api.encounters;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mentalwellness.api.IntegrationTest;
import org.mentalwellness.api.support.ApiDriver;
import org.mentalwellness.api.support.ContractProblemSchema;
import org.mentalwellness.api.support.ContractSamples;
import org.mentalwellness.api.support.Fixtures;
import org.mentalwellness.api.support.Ids;
import org.mentalwellness.api.support.TestTokens;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Clinic phases 2-3 (ADR-016): registration, audited identity lookup, encounters, ASHA follow-up tasks. */
@IntegrationTest
class ClinicIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired JdbcClient jdbc;

	final TestTokens.Builder admin = TestTokens.token().roles("ADMIN").scope("careteam:admin");
	final TestTokens.Builder asha = TestTokens.token().roles("ASHA_WORKER").scope(Fixtures.USER_SCOPES + " careteam:read careteam:write");
	final TestTokens.Builder otherAsha = TestTokens.token().roles("ASHA_WORKER").scope(Fixtures.USER_SCOPES);
	final TestTokens.Builder doctor = TestTokens.token().roles("CLINICIAN").scope(Fixtures.STAFF_SCOPES);
	final TestTokens.Builder counsellor = TestTokens.token().roles("COUNSELLOR").scope(Fixtures.STAFF_SCOPES);
	final TestTokens.Builder nurse = TestTokens.token().roles("HEALTH_STAFF").scope(Fixtures.STAFF_SCOPES);
	final TestTokens.Builder outsider = TestTokens.token().roles("CLINICIAN").scope(Fixtures.STAFF_SCOPES);

	ApiDriver driver;
	String facility;
	String elsewhere;

	@BeforeEach
	void facilities() throws Exception {
		this.driver = new ApiDriver(this.mvc, this.json);
		this.facility = "phc-" + UUID.randomUUID().toString().substring(0, 8);
		this.elsewhere = "chc-" + UUID.randomUUID().toString().substring(0, 8);
		for (String f : List.of(this.facility, this.elsewhere)) {
			expect(200, admin(put("/v1/admin/facilities/{f}", f), Map.of("kind", "PHC", "facility_label", f + " (synthetic)", "active", true)));
		}
		member(this.facility, this.doctor, "CLINICIAN");
		member(this.facility, this.counsellor, "COUNSELLOR");
		member(this.facility, this.nurse, "HEALTH_STAFF");
		member(this.elsewhere, this.outsider, "CLINICIAN");
	}

	record Patient(String participant, String consentEvent) {
	}

	/** A walk-in registered at the clinic: consent by the doctor, then identity. */
	Patient walkIn(String name, String village, String phone) throws Exception {
		String ppt = Ids.of("ppt");
		String ce = Ids.of("ce");
		expect(201, this.driver.postJson("/v1/consent-events", this.doctor,
				Fixtures.clinicConsent(ce, ppt, this.facility, Instant.now().minusSeconds(900))));
		Map<String, Object> identity = Fixtures.identity(ce, name, village);
		if (phone != null) {
			identity.put("contact_phone", phone);
		}
		expect(200, putIdentity(this.doctor, ppt, identity));
		return new Patient(ppt, ce);
	}

	// --- phase 2: registration and identity --------------------------------------------

	@Test
	void aWalkInIsRegisteredWithoutBecomingAnyonesAshaParticipant() throws Exception {
		Patient p = walkIn("Synthetic Walkin Eta-1", "Testgrama-21", "9876500001");
		assertThat(this.jdbc.sql("SELECT count(*) FROM consent.participant_assignment WHERE participant_ref = ? AND worker_subject IS NOT NULL")
			.param(p.participant()).query(Integer.class).single()).isZero();
		Map<String, Object> team = this.driver.body(expect(200, this.mvc.perform(get("/v1/participants/{p}/care-team", p.participant())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + this.doctor.build())).andReturn()));
		assertThat(team.toString()).contains(this.facility).contains("CLINIC_REGISTRATION");
		assertThat(team).containsEntry("asha_worker_ref", null);

		// A doctor elsewhere can't record consent for this person.
		assertThat(this.driver.postJson("/v1/consent-events", this.outsider,
				Fixtures.clinicConsent(Ids.of("ce"), p.participant(), this.elsewhere, Instant.now())).getResponse().getStatus()).isEqualTo(403);
		// An ASHA worker can't use the clinic path.
		Map<String, Object> viaAsha = Fixtures.clinicConsent(Ids.of("ce"), Ids.of("ppt"), this.facility, Instant.now());
		assertThat(this.driver.postJson("/v1/consent-events", this.asha, viaAsha).getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void lookupIsCareTeamOnlyReasonCodedAuditedAndNeverForAshaWorkers() throws Exception {
		Patient p = walkIn("Synthetic Lookup Theta-2", "Testgrama-22", "9876500002");
		Map<String, Object> details = this.driver.body(expect(200, lookup(this.doctor, p.participant(), "DIRECT_CARE")));
		assertThat(details).containsEntry("display_name", "Synthetic Lookup Theta-2").containsEntry("village_name", "Testgrama-22")
			.containsEntry("contact_phone", "9876500002");
		assertThat((String) details.get("patient_card_code")).matches("^[A-Z2-9]{8}$");
		expect(200, lookup(this.nurse, p.participant(), "FOLLOW_UP_CONTACT"));
		assertThat(lookup(this.outsider, p.participant(), "DIRECT_CARE").getResponse().getStatus()).isEqualTo(404);
		assertThat(lookup(this.asha, p.participant(), "DIRECT_CARE").getResponse().getStatus()).isEqualTo(403);
		assertThat(this.mvc.perform(post("/v1/participants/{p}/identity/lookup", p.participant())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + this.doctor.build()).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andReturn().getResponse().getStatus()).as("reason required").isEqualTo(400);
		assertThat(this.jdbc.sql("SELECT string_agg(action, ',' ORDER BY id) FROM audit.audit_event WHERE resource_id = ?")
			.param(p.participant()).query(String.class).single())
			.contains("IDENTITY_LOOKUP_DIRECT_CARE").contains("IDENTITY_LOOKUP_FOLLOW_UP_CONTACT").doesNotContain("Theta");
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_event a WHERE a::text LIKE '%Theta-2%' OR a::text LIKE '%9876500002%'")
			.query(Integer.class).single()).isZero();
	}

	@Test
	void searchFindsByCardOrNormalisedNameButOnlyOnTheCallersCareTeams() throws Exception {
		Patient p = walkIn("Synthetic  Search  Iota", "Testgrama-23", null);
		String card = (String) this.driver.body(expect(200, lookup(this.doctor, p.participant(), "DIRECT_CARE"))).get("patient_card_code");
		assertThat(search(this.doctor, Map.of("patient_card_code", card))).containsExactly(p.participant());
		assertThat(search(this.doctor, Map.of("display_name", "synthetic search iota", "village_name", " TESTGRAMA-23 ")))
			.containsExactly(p.participant());
		assertThat(search(this.outsider, Map.of("patient_card_code", card))).isEmpty();
		assertThat(this.mvc.perform(post("/v1/identity/search").header(HttpHeaders.AUTHORIZATION, "Bearer " + this.doctor.build())
			.contentType(MediaType.APPLICATION_JSON).content("{\"patient_card_code\":\"" + card + "\",\"display_name\":\"x\",\"village_name\":\"y\"}"))
			.andReturn().getResponse().getStatus()).isEqualTo(400);
		String stored = this.jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'IDENTITY_SEARCH_NAME' AND actor_ref = ?")
			.param(this.doctor.subject()).query(String.class).single();
		assertThat(stored).isEqualTo("1");
	}

	@Test
	void lookupsAreRateLimited() throws Exception {
		Patient p = walkIn("Synthetic Rate Kappa", "Testgrama-24", null);
		TestTokens.Builder busy = TestTokens.token().roles("CLINICIAN").scope(Fixtures.STAFF_SCOPES);
		member(this.facility, busy, "CLINICIAN");
		for (int i = 0; i < 30; i++) {
			expect(200, lookup(busy, p.participant(), "DIRECT_CARE"));
		}
		ContractProblemSchema.assertConforms(this.driver.body(lookup(busy, p.participant(), "DIRECT_CARE")), "RATE_LIMITED", 429);
	}

	@Test
	void lookupStopsWhenTheConsentBehindTheRecordIsWithdrawn() throws Exception {
		Patient p = walkIn("Synthetic Withdrawn Lambda", "Testgrama-25", null);
		expect(202, this.driver.withdraw(this.doctor, p.consentEvent(), List.of("CLINICAL_CARE")));
		ContractProblemSchema.assertConforms(this.driver.body(lookup(this.doctor, p.participant(), "DIRECT_CARE")),
				"CONSENT_NOT_ACTIVE", 422);
	}

	// --- phase 3: encounters -----------------------------------------------------------

	@Test
	void instrumentsAreScoredByTheirPublishedRuleAndPhq9Item9IsUrgent() throws Exception {
		Patient p = walkIn("Synthetic Scored Mu", "Testgrama-26", null);
		String enc = openEncounter(this.doctor, p, "IN_PERSON");
		Map<String, Object> result = this.driver.body(expect(201, send(this.doctor, "/v1/encounters/" + enc + "/instrument-responses",
				"submitInstrumentResponse", Fixtures.phq9(Ids.of("ir"), "MORE_THAN_HALF_THE_DAYS", "SEVERAL_DAYS"))));
		assertThat(result).containsEntry("total_score", 17).containsEntry("severity_band", "MODERATELY_SEVERE")
			.containsEntry("complete", true).containsEntry("urgent_review_flag", true).containsEntry("translation_validated", false);

		String second = openEncounter(this.counsellor, p, "COUNSELLING_SESSION");
		Map<String, Object> partial = this.driver.body(expect(201, send(this.counsellor, "/v1/encounters/" + second
				+ "/instrument-responses", "submitInstrumentResponse", Fixtures.phq9(Ids.of("ir"), "NOT_AT_ALL", "DECLINED"))));
		assertThat(partial).containsEntry("complete", false).containsEntry("total_score", null).containsEntry("urgent_review_flag", false);

		Map<String, Object> wrong = Fixtures.phq9(Ids.of("ir"), "NOT_AT_ALL", "NOT_AT_ALL");
		wrong.put("instrument", Map.of("instrument_id", "phq-9", "instrument_version", "9.9.9"));
		ContractProblemSchema.assertConforms(this.driver.body(send(this.doctor, "/v1/encounters/" + second + "/instrument-responses",
				"submitInstrumentResponse", wrong)), "VALIDATION_FAILED", 400);
		assertThat(send(this.nurse, "/v1/encounters/" + enc + "/instrument-responses", "submitInstrumentResponse",
				Fixtures.phq9(Ids.of("ir"), "NOT_AT_ALL", "NOT_AT_ALL")).getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void eachRoleSeesOnlyWhatItMay() throws Exception {
		Patient p = walkIn("Synthetic Roles Nu", "Testgrama-27", null);
		String enc = openEncounter(this.doctor, p, "IN_PERSON");
		expect(201, send(this.doctor, "/v1/encounters/" + enc + "/instrument-responses", "submitInstrumentResponse",
				Fixtures.phq9(Ids.of("ir"), "SEVERAL_DAYS", "NOT_AT_ALL")));
		expect(201, send(this.doctor, "/v1/encounters/" + enc + "/diagnoses", "recordDiagnosis",
				Map.of("diagnosis_id", Ids.of("dx"), "code_system", "ICD_10", "code", "F32.1", "certainty", "PROVISIONAL")));

		Map<String, Object> asDoctor = this.driver.body(expect(200, getEncounter(this.doctor, enc)));
		assertThat((List<?>) asDoctor.get("instruments")).hasSize(1);
		assertThat((List<?>) asDoctor.get("clinician_diagnoses")).hasSize(1);
		Map<String, Object> asNurse = this.driver.body(expect(200, getEncounter(this.nurse, enc)));
		assertThat((List<?>) asNurse.get("instruments")).isEmpty();
		assertThat((List<?>) asNurse.get("clinician_diagnoses")).isEmpty();
		assertThat(getEncounter(this.outsider, enc).getResponse().getStatus()).isEqualTo(404);
		assertThat(getEncounter(this.asha, enc).getResponse().getStatus()).isEqualTo(403);

		String timelineDoctor = this.mvc.perform(get("/v1/participants/{p}/timeline", p.participant())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + this.doctor.build())).andReturn().getResponse().getContentAsString();
		String timelineNurse = this.mvc.perform(get("/v1/participants/{p}/timeline", p.participant())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + this.nurse.build())).andReturn().getResponse().getContentAsString();
		assertThat(timelineDoctor).contains("PHQ-9=MILD");
		assertThat(timelineNurse).contains("IN_PERSON").doesNotContain("PHQ-9");
	}

	@Test
	void diagnosesAreClinicianOnlyMentalHealthCodesAndAppendOnly() throws Exception {
		Patient p = walkIn("Synthetic Dx Xi", "Testgrama-28", null);
		String enc = openEncounter(this.doctor, p, "TELE_PHONE");
		String dx = Ids.of("dx");
		Map<String, Object> d = this.driver.body(expect(201, send(this.doctor, "/v1/encounters/" + enc + "/diagnoses", "recordDiagnosis",
				Map.of("diagnosis_id", dx, "code_system", "ICD_11", "code", "6A70", "certainty", "PROVISIONAL"))));
		assertThat((String) d.get("recorded_by_ref")).matches("^clin_[a-f0-9]{32}$");
		ContractProblemSchema.assertConforms(this.driver.body(send(this.doctor, "/v1/encounters/" + enc + "/diagnoses", "recordDiagnosis",
				Map.of("diagnosis_id", Ids.of("dx"), "code_system", "ICD_10", "code", "J45", "certainty", "CONFIRMED"))),
				"VALIDATION_FAILED", 400);
		assertThat(send(this.counsellor, "/v1/encounters/" + enc + "/diagnoses", "recordDiagnosis",
				Map.of("diagnosis_id", Ids.of("dx"), "code_system", "ICD_10", "code", "F41.1", "certainty", "PROVISIONAL"))
			.getResponse().getStatus()).isEqualTo(403);
		assertThatThrownBy(() -> this.jdbc.sql("UPDATE encounters.diagnosis SET certainty = 'CONFIRMED' WHERE diagnosis_id = ?")
			.param(dx).update()).rootCause().hasMessageContaining("append-only");
	}

	@Test
	void notesAreOffUntilAPolicyExists() throws Exception {
		Patient p = walkIn("Synthetic Notes Omicron", "Testgrama-29", null);
		String enc = openEncounter(this.doctor, p, "IN_PERSON");
		ContractProblemSchema.assertConforms(this.driver.body(send(this.doctor, "/v1/encounters/" + enc + "/notes", "addClinicalNote",
				Map.of("note_id", Ids.of("note"), "text", "synthetic note"))), "CLINICAL_NOTES_DISABLED", 422);
		assertThat(this.driver.body(expect(200, getEncounter(this.doctor, enc)))).containsEntry("clinical_notes_enabled", false);
	}

	@Test
	void healthStaffOpenInPersonEncountersOnly() throws Exception {
		Patient p = walkIn("Synthetic Staff Pi", "Testgrama-30", null);
		expect(201, send(this.nurse, "/v1/encounters", "createEncounter",
				Fixtures.encounter(Ids.of("enc"), p.participant(), p.consentEvent(), this.facility, "IN_PERSON", Instant.now())));
		assertThat(send(this.nurse, "/v1/encounters", "createEncounter",
				Fixtures.encounter(Ids.of("enc"), p.participant(), p.consentEvent(), this.facility, "TELE_PHONE", Instant.now()))
			.getResponse().getStatus()).isEqualTo(403);
		assertThat(send(this.outsider, "/v1/encounters", "createEncounter",
				Fixtures.encounter(Ids.of("enc"), p.participant(), p.consentEvent(), this.elsewhere, "IN_PERSON", Instant.now()))
			.getResponse().getStatus()).as("facility not on the care team").isEqualTo(403);
	}

	@Test
	void theCarePlanCreatesAshaTasksThatTheWorkerCompletes() throws Exception {
		// The shared record: an ASHA participant, referred to the facility, then seen there.
		String ppt = Ids.of("ppt");
		String ashaConsent = Ids.of("ce");
		expect(201, this.driver.postJson("/v1/consent-events", this.asha, Fixtures.ashaConsent(ashaConsent, ppt, "hi-IN",
				Instant.now().minusSeconds(3600))));
		expect(200, this.mvc.perform(put("/v1/participants/{p}/care-team/{f}", ppt, this.facility)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + this.asha.build()).contentType(MediaType.APPLICATION_JSON)
			.content(this.json.writeValueAsString(Map.of("reason_code", "ASHA_REFERRAL", "consent_event_id", ashaConsent)))).andReturn());
		String clinicConsent = Ids.of("ce");
		expect(201, this.driver.postJson("/v1/consent-events", this.doctor,
				Fixtures.clinicConsent(clinicConsent, ppt, this.facility, Instant.now().minusSeconds(900))));
		assertThat(this.jdbc.sql("SELECT worker_subject FROM consent.participant_assignment WHERE participant_ref = ?")
			.param(ppt).query(String.class).single()).as("still the ASHA worker's participant").isEqualTo(this.asha.subject());
		Patient p = new Patient(ppt, clinicConsent);
		String enc = openEncounter(this.doctor, p, "IN_PERSON");
		String task = Ids.of("task");
		Map<String, Object> plan = Map.of("plan_id", Ids.of("plan"), "next_contact_at_utc", Instant.now().plusSeconds(86400 * 7).toString(),
				"next_contact_mode", "HOME_VISIT", "referral_target", "COUNSELLING",
				"tasks", List.of(Map.of("task_id", task, "assignee", "ASHA_WORKER", "task_type", "HOME_VISIT",
						"due_by_utc", Instant.now().plusSeconds(86400 * 3).toString())));
		expect(201, send(this.doctor, "/v1/encounters/" + enc + "/care-plan", "recordCarePlan", plan));

		String tasks = this.mvc.perform(get("/v1/care-tasks").header(HttpHeaders.AUTHORIZATION, "Bearer " + this.asha.build()))
			.andReturn().getResponse().getContentAsString();
		assertThat(tasks).contains(task).contains("HOME_VISIT").doesNotContain("PHQ").doesNotContain("F32");
		assertThat(this.mvc.perform(get("/v1/care-tasks").header(HttpHeaders.AUTHORIZATION, "Bearer " + this.otherAsha.build()))
			.andReturn().getResponse().getContentAsString()).doesNotContain(task);
		Map<String, Object> done = Map.of("completed_at_utc", Instant.now().toString(), "outcome", "DONE");
		assertThat(send(this.otherAsha, "/v1/care-tasks/" + task + "/completion", "completeCareTask", done).getResponse().getStatus())
			.isEqualTo(404);
		expect(201, send(this.asha, "/v1/care-tasks/" + task + "/completion", "completeCareTask", done));
		ContractProblemSchema.assertConforms(this.driver.body(send(this.asha, "/v1/care-tasks/" + task + "/completion",
				"completeCareTask", Map.of("completed_at_utc", Instant.now().toString(), "outcome", "NOT_REACHABLE"))),
				"RESOURCE_CONFLICT", 409);
	}

	@Test
	void walkInsCannotGetAshaTasksAndWithdrawalStopsNewEncounters() throws Exception {
		Patient p = walkIn("Synthetic NoAsha Rho", "Testgrama-31", null);
		String enc = openEncounter(this.doctor, p, "IN_PERSON");
		ContractProblemSchema.assertConforms(this.driver.body(send(this.doctor, "/v1/encounters/" + enc + "/care-plan", "recordCarePlan",
				Map.of("plan_id", Ids.of("plan"), "referral_target", "NONE", "tasks", List.of(Map.of("task_id", Ids.of("task"),
						"assignee", "ASHA_WORKER", "task_type", "CHECK_IN_CALL", "due_by_utc", Instant.now().plusSeconds(3600).toString()))))),
				"VALIDATION_FAILED", 400);
		expect(201, send(this.doctor, "/v1/encounters/" + enc + "/close", "closeEncounter", Map.of("ended_at_utc", Instant.now().toString())));
		ContractProblemSchema.assertConforms(this.driver.body(send(this.doctor, "/v1/encounters/" + enc + "/instrument-responses",
				"submitInstrumentResponse", Fixtures.phq9(Ids.of("ir"), "NOT_AT_ALL", "NOT_AT_ALL"))), "RESOURCE_CONFLICT", 409);

		expect(202, this.driver.withdraw(this.doctor, p.consentEvent(), List.of("CLINICAL_CARE")));
		ContractProblemSchema.assertConforms(this.driver.body(send(this.doctor, "/v1/encounters", "createEncounter",
				Fixtures.encounter(Ids.of("enc"), p.participant(), p.consentEvent(), this.facility, "IN_PERSON", Instant.now()))),
				"CONSENT_NOT_ACTIVE", 422);
	}

	@Test
	void encountersNeverReachResearchExports() {
		assertThat(this.jdbc.sql("""
				SELECT count(*) FROM information_schema.columns
				WHERE table_schema = 'research_export' AND (column_name LIKE '%encounter%' OR column_name LIKE '%diagnos%' OR column_name LIKE '%note%')
				""").query(Integer.class).single()).isZero();
	}

	// --- security review fixes (2026-10-20) ---------------------------------------------

	@Test
	void clinicalDetailFollowsTheMembershipRoleNotTheTokenAlone() throws Exception {
		Patient p = walkIn("Synthetic Multirole Sigma", "Testgrama-32", null);
		String enc = openEncounter(this.doctor, p, "IN_PERSON");
		expect(201, send(this.doctor, "/v1/encounters/" + enc + "/instrument-responses", "submitInstrumentResponse",
				Fixtures.phq9(Ids.of("ir"), "SEVERAL_DAYS", "NOT_AT_ALL")));
		expect(201, send(this.doctor, "/v1/encounters/" + enc + "/diagnoses", "recordDiagnosis",
				Map.of("diagnosis_id", Ids.of("dx"), "code_system", "ICD_10", "code", "F41.1", "certainty", "PROVISIONAL")));
		// Clinician in the token, but only a HEALTH_STAFF membership here (W1).
		TestTokens.Builder twoRoles = TestTokens.token().roles("CLINICIAN", "HEALTH_STAFF").scope(Fixtures.STAFF_SCOPES);
		member(this.facility, twoRoles, "HEALTH_STAFF");
		Map<String, Object> seen = this.driver.body(expect(200, getEncounter(twoRoles, enc)));
		assertThat((List<?>) seen.get("instruments")).isEmpty();
		assertThat((List<?>) seen.get("clinician_diagnoses")).isEmpty();
		// Counsellors see scores but not diagnoses until the clinical lead decides (W2).
		Map<String, Object> counsellorView = this.driver.body(expect(200, getEncounter(this.counsellor, enc)));
		assertThat((List<?>) counsellorView.get("instruments")).hasSize(1);
		assertThat((List<?>) counsellorView.get("clinician_diagnoses")).isEmpty();
	}

	@Test
	void staffCannotAddAnotherOfTheirFacilitiesByRecordingConsent() throws Exception {
		Patient p = walkIn("Synthetic Spread Tau", "Testgrama-33", null);
		member(this.elsewhere, this.doctor, "CLINICIAN"); // the doctor also works elsewhere (W3)
		assertThat(this.driver.postJson("/v1/consent-events", this.doctor,
				Fixtures.clinicConsent(Ids.of("ce"), p.participant(), this.elsewhere, Instant.now())).getResponse().getStatus()).isEqualTo(403);
		assertThat(this.jdbc.sql("SELECT count(*) FROM careteam.participant_care_team WHERE participant_ref = ? AND facility_code = ?")
			.params(p.participant(), this.elsewhere).query(Integer.class).single()).isZero();
	}

	@Test
	void anAshaWorkerCannotClaimAClinicRegisteredPatient() throws Exception {
		Patient p = walkIn("Synthetic Claim Upsilon", "Testgrama-34", null);
		assertThat(this.driver.postJson("/v1/consent-events", this.asha,
				Fixtures.ashaConsent(Ids.of("ce"), p.participant(), "hi-IN", Instant.now())).getResponse().getStatus()).isEqualTo(403);
		assertThat(this.jdbc.sql("SELECT count(*) FROM consent.participant_assignment WHERE participant_ref = ? AND worker_subject IS NOT NULL")
			.param(p.participant()).query(Integer.class).single()).as("W4: no silent claim").isZero();
	}

	@Test
	void consentFormsAreTiedToWhoRecordsThem() throws Exception {
		Map<String, Object> ashaFormAtClinic = Fixtures.ashaConsent(Ids.of("ce"), Ids.of("ppt"), "hi-IN", Instant.now());
		ashaFormAtClinic.put("facility_code", this.facility);
		ContractProblemSchema.assertConforms(this.driver.body(this.driver.postJson("/v1/consent-events", this.doctor, ashaFormAtClinic)),
				"VALIDATION_FAILED", 400);
		Map<String, Object> clinicFormAtHome = Fixtures.clinicConsent(Ids.of("ce"), Ids.of("ppt"), this.facility, Instant.now());
		clinicFormAtHome.remove("facility_code");
		ContractProblemSchema.assertConforms(this.driver.body(this.driver.postJson("/v1/consent-events", this.asha, clinicFormAtHome)),
				"VALIDATION_FAILED", 400);
	}

	@Test
	void searchMatchesAndDeniedLookupsAreAudited() throws Exception {
		Patient p = walkIn("Synthetic Audit Phi", "Testgrama-35", null);
		search(this.doctor, Map.of("display_name", "Synthetic Audit Phi", "village_name", "Testgrama-35"));
		lookup(this.outsider, p.participant(), "DIRECT_CARE");
		assertThat(this.jdbc.sql("SELECT string_agg(action, ',') FROM audit.audit_event WHERE resource_id = ?").param(p.participant())
			.query(String.class).single()).contains("IDENTITY_SEARCH_MATCH").contains("IDENTITY_LOOKUP_DENIED");
	}

	// --- helpers -----------------------------------------------------------------------

	String openEncounter(TestTokens.Builder who, Patient p, String type) throws Exception {
		String id = Ids.of("enc");
		expect(201, send(who, "/v1/encounters", "createEncounter",
				Fixtures.encounter(id, p.participant(), p.consentEvent(), this.facility, type, Instant.now().minusSeconds(60))));
		return id;
	}

	void member(String facility, TestTokens.Builder who, String role) throws Exception {
		expect(200, admin(put("/v1/admin/facilities/{f}/memberships/{s}", facility, who.subject()), Map.of("care_role", role, "active", true)));
	}

	MvcResult admin(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, Map<String, Object> body)
			throws Exception {
		return this.mvc.perform(req.header(HttpHeaders.AUTHORIZATION, "Bearer " + this.admin.build()).header("X-Access-Reason", "AUDIT")
			.contentType(MediaType.APPLICATION_JSON).content(this.json.writeValueAsString(body))).andReturn();
	}

	MvcResult send(TestTokens.Builder who, String path, String operationId, Map<String, Object> body) throws Exception {
		String content = this.json.writeValueAsString(body);
		MvcResult r = this.mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())
			.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(content)).andReturn();
		ContractSamples.record(operationId, content, r);
		return r;
	}

	MvcResult putIdentity(TestTokens.Builder who, String ppt, Map<String, Object> body) throws Exception {
		String content = this.json.writeValueAsString(body);
		MvcResult r = this.mvc.perform(put("/v1/participants/{p}/identity", ppt).header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())
			.contentType(MediaType.APPLICATION_JSON).content(content)).andReturn();
		ContractSamples.record("identity-api.v1.yaml", "putParticipantIdentity", content, r);
		return r;
	}

	MvcResult lookup(TestTokens.Builder who, String ppt, String reason) throws Exception {
		String content = "{\"reason_code\":\"" + reason + "\"}";
		MvcResult r = this.mvc.perform(post("/v1/participants/{p}/identity/lookup", ppt).header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())
			.contentType(MediaType.APPLICATION_JSON).content(content)).andReturn();
		ContractSamples.record("identity-api.v1.yaml", "lookupParticipantIdentity", content, r);
		return r;
	}

	@SuppressWarnings("unchecked")
	List<String> search(TestTokens.Builder who, Map<String, Object> body) throws Exception {
		String content = this.json.writeValueAsString(body);
		MvcResult r = this.mvc.perform(post("/v1/identity/search").header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())
			.contentType(MediaType.APPLICATION_JSON).content(content)).andReturn();
		ContractSamples.record("identity-api.v1.yaml", "searchParticipants", content, r);
		return (List<String>) this.driver.body(expect(200, r)).get("participant_refs");
	}

	MvcResult getEncounter(TestTokens.Builder who, String enc) throws Exception {
		MvcResult r = this.mvc.perform(get("/v1/encounters/{e}", enc).header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())).andReturn();
		ContractSamples.record("getEncounter", null, r);
		return r;
	}

	static MvcResult expect(int status, MvcResult r) throws Exception {
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
		return r;
	}

}
