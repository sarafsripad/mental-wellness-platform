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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Clinician review of flagged cases (API 1.2, ADR-014). */
@IntegrationTest
class ReviewIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired JdbcClient jdbc;

	final TestTokens.Builder asha = TestTokens.token().roles("ASHA_WORKER").scope(Fixtures.USER_SCOPES);
	final TestTokens.Builder clinician = TestTokens.token().roles("CLINICIAN").scope(Fixtures.CLINICIAN_SCOPES);

	ApiDriver driver;

	@BeforeEach
	void setUp() {
		this.driver = new ApiDriver(this.mvc, this.json);
	}

	record Visit(String participant, String consentEvent, String caseId) {
	}

	/** A completed visit through the real API. */
	Visit visit(String safety, boolean followUp) throws Exception {
		String participant = Ids.of("ppt");
		String event = Ids.of("ce");
		Instant consentedAt = Instant.now().minusSeconds(3600);
		expect(201, this.driver.postJson("/v1/consent-events", this.asha,
				Fixtures.ashaConsent(event, participant, "kn-IN", consentedAt)));
		String caseId = Ids.of("case");
		expect(201, this.driver.postJson("/v1/cases", this.asha, Fixtures.caseCreate(caseId, participant, event, consentedAt)));
		expect(201, this.driver.postJson("/v1/cases/" + caseId + "/observations", this.asha,
				Fixtures.observations(Ids.of("obs"), Instant.now().minusSeconds(600), Fixtures.typicalObservations())));
		expect(201, this.driver.postJson("/v1/cases/" + caseId + "/questionnaire-responses", this.asha,
				Fixtures.questionnaire(Ids.of("qr"), Instant.now().minusSeconds(300), safety)));
		expect(201, this.driver.postJson("/v1/cases/" + caseId + "/completion", this.asha,
				Fixtures.completion(Instant.now().minusSeconds(60), followUp)));
		return new Visit(participant, event, caseId);
	}

	@Test
	void queueHoldsFlaggedCasesOnlyWithUrgentFirst() throws Exception {
		Visit routine = visit("NO", true);
		Visit urgent = visit("OFTEN", false);
		Visit nothing = visit("NO", false);

		List<Map<String, Object>> items = items(expect(200, queue(this.clinician, null)));
		List<String> ids = items.stream().map(this::caseId).toList();
		assertThat(ids).contains(routine.caseId(), urgent.caseId()).doesNotContain(nothing.caseId());
		assertThat(ids.indexOf(urgent.caseId())).isLessThan(ids.indexOf(routine.caseId()));
		assertThat(item(items, urgent.caseId())).containsEntry("priority", "URGENT");
		assertThat(item(items, routine.caseId())).containsEntry("priority", "ROUTINE");
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'REVIEW_QUEUE_READ'")
			.query(Integer.class).single()).isPositive();
	}

	@Test
	void detailShowsCodedAnswersMarkedUnvalidatedAndNoIdentity() throws Exception {
		Visit v = visit("SOMETIMES", true);
		expect(200, this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.put("/v1/participants/{p}/identity", v.participant())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + this.asha.build())
			.contentType(MediaType.APPLICATION_JSON)
			.content(this.json.writeValueAsString(Fixtures.identity(v.consentEvent(), "Synthetic Omega-3318", "Testgrama-61"))))
			.andReturn());

		MvcResult r = expect(200, detail(this.clinician, v.caseId()));
		Map<String, Object> body = this.driver.body(r);
		@SuppressWarnings("unchecked")
		Map<String, Object> questionnaire = (Map<String, Object>) body.get("questionnaire");
		assertThat(questionnaire).containsEntry("instrument_id", "illustrative-wellness").containsEntry("validated", false);
		assertThat(r.getResponse().getContentAsString()).contains("SELF_HARM_THOUGHTS").contains("SOMETIMES")
			.contains("HYPERTENSION").doesNotContain("Omega-3318").doesNotContain("Testgrama");
		assertThat(this.jdbc.sql("SELECT action FROM audit.audit_event WHERE resource_id = ?").param(v.caseId())
			.query(String.class).list()).contains("REVIEW_CASE_READ");
	}

	@Test
	void decisionIsRecordedAppendOnlyAndReachesTheAshaWorker() throws Exception {
		Visit v = visit("OFTEN", true);
		String first = Ids.of("rvw");
		Map<String, Object> recorded = this.driver.body(expect(201, review(this.clinician, v.caseId(),
				Fixtures.review(first, "REFER", "WITHIN_7_DAYS", Instant.now()), UUID.randomUUID())));
		assertThat(recorded).containsEntry("decision", "REFER").containsEntry("follow_up_within", "WITHIN_7_DAYS");
		assertThat((String) recorded.get("reviewed_by_ref")).matches("^clin_[a-f0-9]{32}$");

		expect(201, review(this.clinician, v.caseId(),
				Fixtures.review(Ids.of("rvw"), "URGENT_REFER", "WITHIN_24_HOURS", Instant.now()), UUID.randomUUID()));

		@SuppressWarnings("unchecked")
		Map<String, Object> state = (Map<String, Object>) this.driver.body(expect(200, this.mvc.perform(get("/v1/cases/{id}", v.caseId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + this.asha.build())).andReturn())).get("review");
		assertThat(state).containsEntry("status", "REVIEWED").containsEntry("decision", "URGENT_REFER")
			.containsEntry("follow_up_within", "WITHIN_24_HOURS");

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> history = (List<Map<String, Object>>) this.driver.body(expect(200, detail(this.clinician, v.caseId())))
			.get("reviews");
		assertThat(history).extracting(h -> h.get("decision")).containsExactly("REFER", "URGENT_REFER");

		assertThat(items(expect(200, queue(this.clinician, "AWAITING_REVIEW"))).stream().map(this::caseId))
			.doesNotContain(v.caseId());
		assertThat(items(expect(200, queue(this.clinician, "REVIEWED"))).stream().map(this::caseId)).contains(v.caseId());

		assertThatThrownBy(() -> this.jdbc.sql("UPDATE cases.case_review SET decision = 'MONITOR' WHERE review_id = ?")
			.param(first).update()).rootCause().hasMessageContaining("append-only");
		assertThat(this.jdbc.sql("SELECT action FROM audit.audit_event WHERE resource_id = ?").param(first)
			.query(String.class).list()).containsExactly("CLINICIAN_REVIEW_RECORDED");
	}

	@Test
	void timeframeMustFitTheDecision() throws Exception {
		Visit v = visit("OFTEN", true);
		for (String[] bad : new String[][] { { "URGENT_REFER", "WITHIN_30_DAYS" }, { "NO_FURTHER_ACTION", "WITHIN_7_DAYS" },
				{ "MONITOR", "NONE" }, { "REFER", "NONE" } }) {
			ContractProblemSchema.assertConforms(this.driver.body(review(this.clinician, v.caseId(),
					Fixtures.review(Ids.of("rvw"), bad[0], bad[1], Instant.now()), UUID.randomUUID())), "VALIDATION_FAILED", 400);
		}
		Map<String, Object> notes = Fixtures.review(Ids.of("rvw"), "MONITOR", "WITHIN_30_DAYS", Instant.now());
		notes.put("notes", "free text is excluded by policy");
		ContractProblemSchema.assertConforms(this.driver.body(review(this.clinician, v.caseId(), notes, UUID.randomUUID())),
				"VALIDATION_FAILED", 400);
	}

	@Test
	void retriesAreIdempotent() throws Exception {
		Visit v = visit("NO", true);
		Map<String, Object> body = Fixtures.review(Ids.of("rvw"), "MONITOR", "WITHIN_30_DAYS", Instant.now());
		UUID key = UUID.randomUUID();
		Map<String, Object> first = this.driver.body(expect(201, review(this.clinician, v.caseId(), body, key)));
		assertThat(this.driver.body(expect(200, review(this.clinician, v.caseId(), body, key)))).isEqualTo(first);
		ContractProblemSchema.assertConforms(this.driver.body(review(this.clinician, v.caseId(), body, UUID.randomUUID())),
				"RESOURCE_CONFLICT", 409);
	}

	@Test
	void unflaggedAndWithdrawnCasesAreNotReviewable() throws Exception {
		Visit nothing = visit("NO", false);
		assertThat(detail(this.clinician, nothing.caseId()).getResponse().getStatus()).isEqualTo(404);
		assertThat(review(this.clinician, nothing.caseId(), Fixtures.review(Ids.of("rvw"), "MONITOR", "WITHIN_7_DAYS",
				Instant.now()), UUID.randomUUID()).getResponse().getStatus()).isEqualTo(404);

		Visit withdrawn = visit("OFTEN", true);
		expect(202, this.driver.withdraw(this.asha, withdrawn.consentEvent(), List.of("SCREENING_PARTICIPATION")));
		assertThat(items(expect(200, queue(this.clinician, "ALL"))).stream().map(this::caseId)).doesNotContain(withdrawn.caseId());
		assertThat(detail(this.clinician, withdrawn.caseId()).getResponse().getStatus()).isEqualTo(404);
	}

	@Test
	void onlyCliniciansReachTheQueueAndCliniciansCannotActAsWorkers() throws Exception {
		Visit v = visit("OFTEN", true);
		assertThat(queue(this.asha, null).getResponse().getStatus()).isEqualTo(403);
		TestTokens.Builder clinicianWithoutScope = TestTokens.token().roles("CLINICIAN").scope("consent:read");
		assertThat(queue(clinicianWithoutScope, null).getResponse().getStatus()).isEqualTo(403);
		TestTokens.Builder admin = TestTokens.token().roles("ADMIN").scope(Fixtures.CLINICIAN_SCOPES);
		assertThat(queue(admin, null).getResponse().getStatus()).isEqualTo(403);
		TestTokens.Builder clinicianWithCaseScopes = TestTokens.token().roles("CLINICIAN")
			.scope(Fixtures.CLINICIAN_SCOPES + " cases:read cases:write");
		assertThat(this.mvc.perform(get("/v1/cases/{id}", v.caseId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + clinicianWithCaseScopes.build())).andReturn().getResponse().getStatus())
			.isEqualTo(403);
	}

	// --- helpers -----------------------------------------------------------------------

	MvcResult queue(TestTokens.Builder who, String status) throws Exception {
		var req = get("/v1/review-queue").header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build()).param("limit", "200");
		if (status != null) {
			req.param("status", status);
		}
		MvcResult r = this.mvc.perform(req).andReturn();
		ContractSamples.record("listReviewQueue", null, r);
		return r;
	}

	MvcResult detail(TestTokens.Builder who, String caseId) throws Exception {
		MvcResult r = this.mvc.perform(get("/v1/review-queue/{id}", caseId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())).andReturn();
		ContractSamples.record("getReviewCase", null, r);
		return r;
	}

	MvcResult review(TestTokens.Builder who, String caseId, Map<String, Object> body, UUID key) throws Exception {
		String content = this.json.writeValueAsString(body);
		MvcResult r = this.mvc.perform(post("/v1/review-queue/{id}/reviews", caseId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())
			.header("Idempotency-Key", key.toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(content)).andReturn();
		ContractSamples.record("submitClinicianReview", content, r);
		return r;
	}

	List<Map<String, Object>> items(MvcResult r) throws Exception {
		Map<String, List<Map<String, Object>>> q = this.json.readValue(r.getResponse().getContentAsString(),
				new TypeReference<Map<String, List<Map<String, Object>>>>() {
				});
		return q.get("items");
	}

	@SuppressWarnings("unchecked")
	String caseId(Map<String, Object> item) {
		return (String) ((Map<String, Object>) item.get("case")).get("case_id");
	}

	Map<String, Object> item(List<Map<String, Object>> items, String caseId) {
		return items.stream().filter(i -> caseId.equals(caseId(i))).findFirst().orElseThrow();
	}

	static MvcResult expect(int status, MvcResult r) throws Exception {
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
		return r;
	}

}
