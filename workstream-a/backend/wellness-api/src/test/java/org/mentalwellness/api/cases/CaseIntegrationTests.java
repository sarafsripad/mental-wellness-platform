package org.mentalwellness.api.cases;

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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** ASHA assessment cases (API 1.1, ADR-013), through the real security chain and database. */
@IntegrationTest
class CaseIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired JdbcClient jdbc;

	final TestTokens.Builder asha = TestTokens.token().roles("ASHA_WORKER").scope(Fixtures.USER_SCOPES);
	final TestTokens.Builder otherAsha = TestTokens.token().roles("ASHA_WORKER").scope(Fixtures.USER_SCOPES);
	final TestTokens.Builder admin = TestTokens.token().roles("ADMIN").scope(Fixtures.USER_SCOPES);

	final Instant consentedAt = Instant.now().minusSeconds(3600);
	ApiDriver driver;
	String participant;
	String consentEvent;

	@BeforeEach
	void consentedParticipant() throws Exception {
		this.driver = new ApiDriver(this.mvc, this.json);
		this.participant = Ids.of("ppt");
		this.consentEvent = Ids.of("ce");
		MvcResult r = this.driver.postJson("/v1/consent-events", this.asha,
				Fixtures.ashaConsent(this.consentEvent, this.participant, "te-IN", this.consentedAt));
		ContractSamples.record("createConsentEvent", null, r);
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
	}

	// --- the full visit --------------------------------------------------------------

	@Test
	void fullVisitStoresCodedAnswersAndCompletes() throws Exception {
		String caseId = Ids.of("case");
		Map<String, Object> opened = body(expect(201, createCase(this.asha, Fixtures.caseCreate(caseId, this.participant,
				this.consentEvent, this.consentedAt.plusSeconds(60)), UUID.randomUUID())));
		assertThat(opened).containsEntry("status", "OPEN")
			.containsEntry("language_tag", "te-IN")
			.containsEntry("urgent_review_flag", false)
			.containsEntry("follow_up_requested", null);
		assertThat((String) opened.get("opened_by_worker_ref")).matches("^wkr_[a-f0-9]{32}$");

		String setId = Ids.of("obs");
		Map<String, Object> afterObs = body(expect(201, observations(this.asha, caseId,
				Fixtures.observations(setId, Instant.now().minusSeconds(30), Fixtures.typicalObservations()), UUID.randomUUID())));
		assertThat(afterObs).containsEntry("observations_submitted", true);

		String responseId = Ids.of("qr");
		Map<String, Object> afterQr = body(expect(201, questionnaire(this.asha, caseId,
				Fixtures.questionnaire(responseId, Instant.now().minusSeconds(20), "NO"), UUID.randomUUID())));
		assertThat(afterQr).containsEntry("questionnaire_submitted", true).containsEntry("urgent_review_flag", false);

		Map<String, Object> done = body(expect(201, complete(this.asha, caseId,
				Fixtures.completion(Instant.now().minusSeconds(10), true), UUID.randomUUID())));
		assertThat(done).containsEntry("status", "COMPLETED").containsEntry("follow_up_requested", true);

		Map<String, Object> read = body(expect(200, getCase(this.asha, caseId, null)));
		assertThat(read).isEqualTo(done);

		assertThat(this.jdbc.sql("SELECT code || '=' || value_code FROM cases.observation WHERE observation_set_id = ?")
			.param(setId).query(String.class).list())
			.containsExactlyInAnyOrder("HEALTH_CONDITION=HYPERTENSION", "HEALTH_CONDITION=DIABETES",
					"TOBACCO_USE=NOT_REPORTED", "ALCOHOL_USE=DECLINED", "SLEEP_QUALITY=SOME_DIFFICULTY");
		assertThat(this.jdbc.sql("SELECT item_id || '=' || answer_code FROM cases.questionnaire_answer WHERE response_id = ?")
			.param(responseId).query(String.class).list())
			.containsExactlyInAnyOrder("ANXIETY=SEVERAL_DAYS", "LOW_MOOD=NOT_AT_ALL", "INTEREST=AS_USUAL", "SELF_HARM_THOUGHTS=NO");
		assertThat(auditActions(caseId)).contains("CASE_CREATED", "CASE_COMPLETED", "CASE_READ")
			.doesNotContain("URGENT_REVIEW_FLAG_RAISED");
	}

	@Test
	void selfHarmAnswerRaisesUrgentReviewFlagForHumanFollowUp() throws Exception {
		for (String answer : List.of("SOMETIMES", "OFTEN")) {
			String caseId = openCase();
			Map<String, Object> c = body(expect(201, questionnaire(this.asha, caseId,
					Fixtures.questionnaire(Ids.of("qr"), Instant.now().minusSeconds(20), answer), UUID.randomUUID())));
			assertThat(c).as(answer).containsEntry("urgent_review_flag", true);
			assertThat(auditActions(caseId)).contains("URGENT_REVIEW_FLAG_RAISED");
		}
		String declined = openCase();
		assertThat(body(expect(201, questionnaire(this.asha, declined,
				Fixtures.questionnaire(Ids.of("qr"), Instant.now().minusSeconds(20), "DECLINED"), UUID.randomUUID()))))
			.containsEntry("urgent_review_flag", false);
	}

	@Test
	void followUpListShowsOnlyTheCallersCasesNeedingFollowUp() throws Exception {
		String urgent = openCase();
		expect(201, questionnaire(this.asha, urgent,
				Fixtures.questionnaire(Ids.of("qr"), Instant.now().minusSeconds(20), "OFTEN"), UUID.randomUUID()));
		String quiet = openCase();

		List<String> all = caseIds(expect(200, list(this.asha, null)));
		List<String> followUps = caseIds(expect(200, list(this.asha, "true")));
		assertThat(all).contains(urgent, quiet);
		assertThat(followUps).contains(urgent).doesNotContain(quiet);
		assertThat(caseIds(expect(200, list(this.otherAsha, null)))).doesNotContain(urgent, quiet);
	}

	// --- refusals ----------------------------------------------------------------------

	@Test
	void minorsAreRefusedUntilAChildPathwayExists() throws Exception {
		Map<String, Object> request = Fixtures.caseCreate(Ids.of("case"), this.participant, this.consentEvent, Instant.now());
		request.put("age_band", "AGE_UNDER_18");
		MvcResult r = createCase(this.asha, request, UUID.randomUUID());
		ContractProblemSchema.assertConforms(body(r), "ADULT_CONFIRMATION_REQUIRED", 422);
		assertThat(auditActions((String) request.get("case_id"))).containsExactly("CASE_REJECTED");
	}

	@Test
	void onlySyntheticDataIsAccepted() throws Exception {
		Map<String, Object> request = Fixtures.caseCreate(Ids.of("case"), this.participant, this.consentEvent, Instant.now());
		request.put("data_origin", "PARTICIPANT");
		ContractProblemSchema.assertConforms(body(createCase(this.asha, request, UUID.randomUUID())),
				"DATA_ORIGIN_NOT_PERMITTED", 422);
	}

	@Test
	void caseNeedsTheParticipantsOwnConsentEvent() throws Exception {
		String otherParticipant = Ids.of("ppt");
		String otherEvent = Ids.of("ce");
		expect(201, this.driver.postJson("/v1/consent-events", this.asha,
				Fixtures.ashaConsent(otherEvent, otherParticipant, "en-IN", this.consentedAt)));
		ContractProblemSchema.assertConforms(body(createCase(this.asha,
				Fixtures.caseCreate(Ids.of("case"), this.participant, otherEvent, Instant.now()), UUID.randomUUID())),
				"CONSENT_PARTICIPANT_MISMATCH", 422);
		ContractProblemSchema.assertConforms(body(createCase(this.asha,
				Fixtures.caseCreate(Ids.of("case"), this.participant, this.consentEvent, this.consentedAt.minusSeconds(60)),
				UUID.randomUUID())), "CONSENT_NOT_ACTIVE", 422);
	}

	@Test
	void withdrawalStopsFurtherSubmissions() throws Exception {
		String caseId = openCase();
		expect(202, this.driver.withdraw(this.asha, this.consentEvent, List.of("SCREENING_PARTICIPATION")));
		ContractProblemSchema.assertConforms(body(questionnaire(this.asha, caseId,
				Fixtures.questionnaire(Ids.of("qr"), Instant.now(), "NO"), UUID.randomUUID())), "CONSENT_NOT_ACTIVE", 422);
		assertThat(this.jdbc.sql("SELECT count(*) FROM cases.questionnaire_response WHERE case_id = ?")
			.param(caseId).query(Integer.class).single()).isZero();
	}

	@Test
	void otherWorkersCannotSeeOrChangeTheCase() throws Exception {
		String caseId = openCase();
		assertThat(getCase(this.otherAsha, caseId, null).getResponse().getStatus()).isEqualTo(404);
		assertThat(observations(this.otherAsha, caseId, Fixtures.observations(Ids.of("obs"), Instant.now(),
				Fixtures.typicalObservations()), UUID.randomUUID()).getResponse().getStatus()).isEqualTo(404);
		assertThat(createCase(this.otherAsha, Fixtures.caseCreate(Ids.of("case"), this.participant, this.consentEvent,
				Instant.now()), UUID.randomUUID()).getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void adminReadNeedsAReasonCode() throws Exception {
		String caseId = openCase();
		ContractProblemSchema.assertConforms(body(getCase(this.admin, caseId, null)), "VALIDATION_FAILED", 400);
		expect(200, getCase(this.admin, caseId, "SUPPORT_TICKET"));
	}

	@Test
	void unknownCodesAndInconsistentAnswersAreRejected() throws Exception {
		String caseId = openCase();
		List<List<Map<String, Object>>> badObservations = List.of(
				List.of(Map.of("code", "TOBACCO_USE", "value_code", "SOMETIMES")),
				List.of(Map.of("code", "HEALTH_CONDITION", "value_code", "NONE_REPORTED"),
						Map.of("code", "HEALTH_CONDITION", "value_code", "DIABETES")),
				List.of(Map.of("code", "SLEEP_QUALITY", "value_code", "GOOD"),
						Map.of("code", "SLEEP_QUALITY", "value_code", "VERY_POOR")));
		for (List<Map<String, Object>> bad : badObservations) {
			MvcResult r = observations(this.asha, caseId, Fixtures.observations(Ids.of("obs"), Instant.now(), bad),
					UUID.randomUUID());
			ContractProblemSchema.assertConforms(body(r), "VALIDATION_FAILED", 400);
		}
		Map<String, Object> noSafety = Fixtures.questionnaire(Ids.of("qr"), Instant.now(), "NO");
		noSafety.put("items", List.of(Map.of("item_id", "ANXIETY", "answer_code", "SEVERAL_DAYS")));
		ContractProblemSchema.assertConforms(body(questionnaire(this.asha, caseId, noSafety, UUID.randomUUID())),
				"VALIDATION_FAILED", 400);
		Map<String, Object> otherInstrument = Fixtures.questionnaire(Ids.of("qr"), Instant.now(), "NO");
		otherInstrument.put("instrument", Map.of("instrument_id", "phq-9", "instrument_version", "1.0.0"));
		ContractProblemSchema.assertConforms(body(questionnaire(this.asha, caseId, otherInstrument, UUID.randomUUID())),
				"VALIDATION_FAILED", 400);
	}

	@Test
	void freeTextIsNotAccepted() throws Exception {
		String caseId = openCase();
		Map<String, Object> withNotes = Fixtures.observations(Ids.of("obs"), Instant.now(), Fixtures.typicalObservations());
		withNotes.put("notes", "free text is excluded by policy");
		ContractProblemSchema.assertConforms(body(observations(this.asha, caseId, withNotes, UUID.randomUUID())),
				"VALIDATION_FAILED", 400);
	}

	@Test
	void retriesAreIdempotentAndKeysCannotBeReused() throws Exception {
		String caseId = Ids.of("case");
		Map<String, Object> request = Fixtures.caseCreate(caseId, this.participant, this.consentEvent, Instant.now());
		UUID key = UUID.randomUUID();
		Map<String, Object> first = body(expect(201, createCase(this.asha, request, key)));
		assertThat(body(expect(200, createCase(this.asha, request, key)))).isEqualTo(first);
		request.put("gender", "FEMALE");
		ContractProblemSchema.assertConforms(body(createCase(this.asha, request, key)), "IDEMPOTENCY_CONFLICT", 409);

		Map<String, Object> qr = Fixtures.questionnaire(Ids.of("qr"), Instant.now(), "NO");
		UUID qrKey = UUID.randomUUID();
		expect(201, questionnaire(this.asha, caseId, qr, qrKey));
		expect(200, questionnaire(this.asha, caseId, qr, qrKey));
		ContractProblemSchema.assertConforms(body(questionnaire(this.asha, caseId,
				Fixtures.questionnaire(Ids.of("qr"), Instant.now(), "NO"), UUID.randomUUID())), "RESOURCE_CONFLICT", 409);
	}

	@Test
	void aCaseClosesOnlyAfterTheSafetyQuestionAndOnlyOnce() throws Exception {
		String caseId = openCase();
		ContractProblemSchema.assertConforms(body(complete(this.asha, caseId, Fixtures.completion(Instant.now(), false),
				UUID.randomUUID())), "RESOURCE_CONFLICT", 409);
		expect(201, questionnaire(this.asha, caseId, Fixtures.questionnaire(Ids.of("qr"), Instant.now(), "NO"),
				UUID.randomUUID()));
		expect(201, complete(this.asha, caseId, Fixtures.completion(Instant.now(), false), UUID.randomUUID()));
		ContractProblemSchema.assertConforms(body(complete(this.asha, caseId, Fixtures.completion(Instant.now(), true),
				UUID.randomUUID())), "RESOURCE_CONFLICT", 409);
		ContractProblemSchema.assertConforms(body(observations(this.asha, caseId, Fixtures.observations(Ids.of("obs"),
				Instant.now(), Fixtures.typicalObservations()), UUID.randomUUID())), "RESOURCE_CONFLICT", 409);
	}

	// --- languages and data minimisation ---------------------------------------------

	@Test
	void consentAcceptsTheTwelveLanguagesButVoiceStaysOnTheFeatureSchemaSet() throws Exception {
		for (String tag : List.of("en-IN", "te-IN", "hi-IN", "ta-IN", "bn-IN", "mr-IN", "kn-IN", "ml-IN", "gu-IN", "pa-IN",
				"ur-IN", "or-IN")) {
			expect(201, this.driver.postJson("/v1/consent-events", this.asha,
					Fixtures.ashaConsent(Ids.of("ce"), Ids.of("ppt"), tag, this.consentedAt)));
		}
		Map<String, Object> session = Fixtures.session(Ids.of("sess"), this.participant, this.consentEvent, Instant.now(),
				"SYNTHETIC");
		session.put("language_tag", "te-IN");
		ContractProblemSchema.assertConforms(body(this.driver.postJson("/v1/voice-sessions", this.asha, session)),
				"VALIDATION_FAILED", 400);
	}

	@Test
	void theCasesSchemaHasNoPlaceForIdentifiersOrFreeText() {
		List<String> columns = this.jdbc.sql("""
				SELECT table_name || '.' || column_name FROM information_schema.columns WHERE table_schema = 'cases'
				""").query(String.class).list();
		assertThat(columns).isNotEmpty()
			.noneMatch(c -> c.matches(".*\\.(.*name.*|.*village.*|.*phone.*|.*note.*|.*address.*|.*score.*|.*text.*)"));
	}

	// --- helpers -----------------------------------------------------------------------

	String openCase() throws Exception {
		String caseId = Ids.of("case");
		expect(201, createCase(this.asha, Fixtures.caseCreate(caseId, this.participant, this.consentEvent, Instant.now()),
				UUID.randomUUID()));
		return caseId;
	}

	MvcResult createCase(TestTokens.Builder who, Map<String, Object> body, UUID key) throws Exception {
		return send("createCase", "/v1/cases", who, body, key);
	}

	MvcResult observations(TestTokens.Builder who, String caseId, Map<String, Object> body, UUID key) throws Exception {
		return send("submitObservations", "/v1/cases/" + caseId + "/observations", who, body, key);
	}

	MvcResult questionnaire(TestTokens.Builder who, String caseId, Map<String, Object> body, UUID key) throws Exception {
		return send("submitQuestionnaireResponse", "/v1/cases/" + caseId + "/questionnaire-responses", who, body, key);
	}

	MvcResult complete(TestTokens.Builder who, String caseId, Map<String, Object> body, UUID key) throws Exception {
		return send("completeCase", "/v1/cases/" + caseId + "/completion", who, body, key);
	}

	MvcResult send(String operationId, String path, TestTokens.Builder who, Map<String, Object> body, UUID key)
			throws Exception {
		String content = this.json.writeValueAsString(body);
		MvcResult r = this.mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())
			.header("Idempotency-Key", key.toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(content)).andReturn();
		ContractSamples.record(operationId, content, r);
		return r;
	}

	MvcResult getCase(TestTokens.Builder who, String caseId, String reason) throws Exception {
		var req = get("/v1/cases/{id}", caseId).header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build());
		if (reason != null) {
			req.header("X-Access-Reason", reason);
		}
		MvcResult r = this.mvc.perform(req).andReturn();
		ContractSamples.record("getCase", null, r);
		return r;
	}

	MvcResult list(TestTokens.Builder who, String followUp) throws Exception {
		var req = get("/v1/cases").header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build());
		if (followUp != null) {
			req.param("follow_up", followUp);
		}
		MvcResult r = this.mvc.perform(req).andReturn();
		ContractSamples.record("listMyCases", null, r);
		return r;
	}

	List<String> caseIds(MvcResult r) throws Exception {
		Map<String, List<Map<String, Object>>> list = this.json.readValue(r.getResponse().getContentAsString(),
				new TypeReference<Map<String, List<Map<String, Object>>>>() {
				});
		return list.get("cases").stream().map(c -> (String) c.get("case_id")).toList();
	}

	List<String> auditActions(String resourceId) {
		return this.jdbc.sql("SELECT action FROM audit.audit_event WHERE resource_id = ? ORDER BY id")
			.param(resourceId)
			.query(String.class)
			.list();
	}

	Map<String, Object> body(MvcResult r) throws Exception {
		return this.driver.body(r);
	}

	static MvcResult expect(int status, MvcResult r) throws Exception {
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
		return r;
	}

}
