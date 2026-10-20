package org.mentalwellness.api.consent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mentalwellness.api.IntegrationTest;
import org.mentalwellness.api.shared.web.DomainException;
import org.mentalwellness.api.shared.web.ProblemCode;
import org.mentalwellness.api.support.ContractProblemSchema;
import org.mentalwellness.api.support.ContractSamples;
import org.mentalwellness.api.support.Ids;
import org.mentalwellness.api.support.TestTokens;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@IntegrationTest
class ConsentApiIntegrationTests {

	static final String USER_SCOPES = "consent:read consent:write voice:read voice:write processing:read processing:write";

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired JdbcClient jdbc;
	@Autowired ConsentGate gate;

	final TestTokens.Builder asha = TestTokens.token().roles("ASHA_WORKER").scope(USER_SCOPES);
	final TestTokens.Builder otherAsha = TestTokens.token().roles("ASHA_WORKER").scope(USER_SCOPES);
	final TestTokens.Builder admin = TestTokens.token().roles("ADMIN").scope(USER_SCOPES);
	final TestTokens.Builder clinician = TestTokens.token().roles("CLINICIAN").scope(USER_SCOPES);

	// --- request builders ------------------------------------------------------------

	/** The scopes screening-consent 0.1.0 offers (local seed). EXTERNAL_REFERRAL_SHARING is on asha-wellness-consent 0.2.0. */
	static final List<ConsentScope> FORM_SCOPES = List.of(ConsentScope.SCREENING_PARTICIPATION, ConsentScope.AUDIO_RECORDING,
			ConsentScope.AUDIO_RETENTION, ConsentScope.RESEARCH_FEATURE_REUSE, ConsentScope.MODEL_TRAINING,
			ConsentScope.QUALITY_REVIEW);

	static Map<String, Object> consentRequest(String eventId, String participant, Set<ConsentScope> granted) {
		List<Map<String, Object>> decisions = new ArrayList<>();
		for (ConsentScope s : FORM_SCOPES) {
			decisions.add(Map.of("scope", s.name(), "granted", granted.contains(s)));
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("consent_event_id", eventId);
		body.put("participant_ref", participant);
		body.put("consent_form", Map.of("form_id", "screening-consent", "form_version", "0.1.0", "language_tag", "kn-IN"));
		body.put("scope_decisions", decisions);
		body.put("capture", new LinkedHashMap<>(Map.of("captured_at_utc", Instant.now().minusSeconds(3600).toString(),
				"method", "SIGNED_ON_DEVICE", "read_aloud", true, "comprehension_confirmed", true, "witness_present", false)));
		body.put("safeguards", new LinkedHashMap<>(Map.of("adult_confirmed", true, "assisted_consent", false)));
		return body;
	}

	static Set<ConsentScope> all() {
		return Set.copyOf(FORM_SCOPES);
	}

	MvcResult create(TestTokens.Builder who, Map<String, Object> body, UUID key) throws Exception {
		String content = this.json.writeValueAsString(body);
		MvcResult r = this.mvc.perform(post("/v1/consent-events").header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())
			.header("Idempotency-Key", key.toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(content)).andReturn();
		ContractSamples.record("createConsentEvent", content, r);
		return r;
	}

	MvcResult withdraw(TestTokens.Builder who, String eventId, Map<String, Object> body, UUID key, String reason)
			throws Exception {
		String content = this.json.writeValueAsString(body);
		var req = post("/v1/consent-events/{id}/withdrawals", eventId).header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())
			.header("Idempotency-Key", key.toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(content);
		if (reason != null) {
			req.header("X-Access-Reason", reason);
		}
		MvcResult r = this.mvc.perform(req).andReturn();
		ContractSamples.record("withdrawConsent", content, r);
		return r;
	}

	MvcResult getEvent(TestTokens.Builder who, String eventId, String reason) throws Exception {
		var req = get("/v1/consent-events/{id}", eventId).header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build());
		if (reason != null) {
			req.header("X-Access-Reason", reason);
		}
		MvcResult r = this.mvc.perform(req).andReturn();
		ContractSamples.record("getConsentEvent", null, r);
		return r;
	}

	MvcResult status(TestTokens.Builder who, String participant) throws Exception {
		MvcResult r = this.mvc.perform(get("/v1/participants/{p}/consent-status", participant)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())).andReturn();
		ContractSamples.record("getConsentStatus", null, r);
		return r;
	}

	static Map<String, Object> withdrawal(List<String> scopes) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("withdrawal_id", Ids.of("cw"));
		if (scopes != null) {
			body.put("scopes", scopes);
		}
		body.put("withdrawn_at_utc", Instant.now().minusSeconds(60).toString());
		body.put("channel", "IN_PERSON_ASHA");
		body.put("reason_code", "NOT_STATED");
		return body;
	}

	@SuppressWarnings("unchecked")
	static List<Object> list(Object value) {
		return (List<Object>) value;
	}

	Map<String, Object> body(MvcResult r) throws Exception {
		return this.json.readValue(r.getResponse().getContentAsString(), new TypeReference<Map<String, Object>>() {
		});
	}

	Map<String, Object> problem(MvcResult r, String code, int status) throws Exception {
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
		Map<String, Object> b = body(r);
		ContractProblemSchema.assertConforms(b, code, status);
		return b;
	}

	String newEvent(TestTokens.Builder who, String participant, Set<ConsentScope> granted) throws Exception {
		String id = Ids.of("ce");
		MvcResult r = create(who, consentRequest(id, participant, granted), UUID.randomUUID());
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
		return id;
	}

	int count(String sql, Object... params) {
		return this.jdbc.sql(sql).params(params).query(Integer.class).single();
	}

	// =================================================================================

	@Nested
	class Create {

		@Test
		void recordsConsentAndReturnsTheEvent() throws Exception {
			String id = Ids.of("ce");
			String participant = Ids.of("ppt");
			MvcResult r = create(asha, consentRequest(id, participant, Set.of(ConsentScope.SCREENING_PARTICIPATION,
					ConsentScope.AUDIO_RECORDING, ConsentScope.RESEARCH_FEATURE_REUSE)), UUID.randomUUID());
			assertThat(r.getResponse().getStatus()).isEqualTo(201);
			assertThat(r.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/v1/consent-events/" + id);
			Map<String, Object> event = body(r);
			assertThat(event).containsEntry("consent_event_id", id)
				.containsEntry("participant_ref", participant)
				.containsEntry("status", "ACTIVE");
			assertThat(list(event.get("effective_scopes")))
				.containsExactlyInAnyOrder("SCREENING_PARTICIPATION", "AUDIO_RECORDING", "RESEARCH_FEATURE_REUSE");
			assertThat((String) event.get("captured_by_worker_ref")).matches("^wkr_[a-f0-9]{32}$")
				.doesNotContain(asha.subject());
			assertThat(count("SELECT count(*) FROM audit.audit_event WHERE action = 'CONSENT_EVENT_CREATED' AND resource_id = ?", id))
				.isEqualTo(1);
		}

		@Test
		void replayWithSameKeyAndBodyReturnsTheOriginalOnce() throws Exception {
			UUID key = UUID.randomUUID();
			Map<String, Object> request = consentRequest(Ids.of("ce"), Ids.of("ppt"), all());
			MvcResult first = create(asha, request, key);
			MvcResult second = create(asha, request, key);
			assertThat(first.getResponse().getStatus()).isEqualTo(201);
			assertThat(second.getResponse().getStatus()).isEqualTo(200);
			assertThat(body(second)).isEqualTo(body(first));
			assertThat(count("SELECT count(*) FROM consent.consent_event WHERE consent_event_id = ?", request.get("consent_event_id")))
				.isEqualTo(1);
		}

		@Test
		void sameKeyWithDifferentBodyIsAConflict() throws Exception {
			UUID key = UUID.randomUUID();
			create(asha, consentRequest(Ids.of("ce"), Ids.of("ppt"), all()), key);
			problem(create(asha, consentRequest(Ids.of("ce"), Ids.of("ppt"), all()), key), "IDEMPOTENCY_CONFLICT", 409);
		}

		@Test
		void keysAreScopedToTheCaller() throws Exception {
			UUID key = UUID.randomUUID();
			assertThat(create(asha, consentRequest(Ids.of("ce"), Ids.of("ppt"), all()), key).getResponse().getStatus()).isEqualTo(201);
			assertThat(create(otherAsha, consentRequest(Ids.of("ce"), Ids.of("ppt"), all()), key).getResponse().getStatus())
				.as("another worker reusing the key gets their own operation, never the first caller's result")
				.isEqualTo(201);
		}

		@Test
		void reusedConsentEventIdWithNewKeyIsAConflict() throws Exception {
			Map<String, Object> request = consentRequest(Ids.of("ce"), Ids.of("ppt"), all());
			create(asha, request, UUID.randomUUID());
			problem(create(asha, request, UUID.randomUUID()), "RESOURCE_CONFLICT", 409);
		}

		@Test
		void concurrentDuplicatesProduceOneEvent() throws Exception {
			UUID key = UUID.randomUUID();
			Map<String, Object> request = consentRequest(Ids.of("ce"), Ids.of("ppt"), all());
			ExecutorService pool = Executors.newFixedThreadPool(4);
			try {
				List<Callable<Integer>> calls = new ArrayList<>();
				for (int i = 0; i < 4; i++) {
					calls.add(() -> create(asha, request, key).getResponse().getStatus());
				}
				List<Integer> statuses = new ArrayList<>();
				for (Future<Integer> f : pool.invokeAll(calls)) {
					statuses.add(f.get());
				}
				assertThat(statuses).containsOnlyOnce(201).allMatch(s -> s == 201 || s == 200);
			}
			finally {
				pool.shutdownNow();
			}
			assertThat(count("SELECT count(*) FROM consent.consent_event WHERE consent_event_id = ?", request.get("consent_event_id")))
				.isEqualTo(1);
		}

		@Test
		void incompleteDecisionsAreRejectedAndTheRejectionIsAudited() throws Exception {
			Map<String, Object> request = consentRequest(Ids.of("ce"), Ids.of("ppt"), all());
			((List<?>) request.get("scope_decisions")).remove(0);
			Map<String, Object> p = problem(create(asha, request, UUID.randomUUID()), "CONSENT_SCOPE_INCOMPLETE", 422);
			assertThat(count("SELECT count(*) FROM audit.audit_event WHERE action = 'CONSENT_EVENT_REJECTED' AND correlation_id = ?",
					UUID.fromString((String) p.get("correlation_id")))).isEqualTo(1);
			assertThat(count("SELECT count(*) FROM consent.consent_event WHERE consent_event_id = ?", request.get("consent_event_id")))
				.isZero();
		}

		@Test
		void grantingWithoutDependencyIsRejected() throws Exception {
			problem(create(asha, consentRequest(Ids.of("ce"), Ids.of("ppt"), Set.of(ConsentScope.SCREENING_PARTICIPATION,
					ConsentScope.AUDIO_RECORDING, ConsentScope.MODEL_TRAINING)), UUID.randomUUID()), "CONSENT_SCOPE_INCOMPLETE", 422);
		}

		@Test
		void minorsAreRejected() throws Exception {
			Map<String, Object> request = consentRequest(Ids.of("ce"), Ids.of("ppt"), all());
			((Map<String, Object>) request.get("safeguards")).put("adult_confirmed", false);
			problem(create(asha, request, UUID.randomUUID()), "ADULT_CONFIRMATION_REQUIRED", 422);
		}

		@Test
		void unconfirmedComprehensionIsRejected() throws Exception {
			Map<String, Object> request = consentRequest(Ids.of("ce"), Ids.of("ppt"), all());
			((Map<String, Object>) request.get("capture")).put("comprehension_confirmed", false);
			problem(create(asha, request, UUID.randomUUID()), "VALIDATION_FAILED", 400);
		}

		@Test
		void unknownFormVersionIsRejected() throws Exception {
			Map<String, Object> request = consentRequest(Ids.of("ce"), Ids.of("ppt"), all());
			request.put("consent_form", Map.of("form_id", "screening-consent", "form_version", "9.9.9", "language_tag", "kn-IN"));
			problem(create(asha, request, UUID.randomUUID()), "VALIDATION_FAILED", 400);
		}

		@Test
		void futureCaptureTimeIsRejected() throws Exception {
			Map<String, Object> request = consentRequest(Ids.of("ce"), Ids.of("ppt"), all());
			((Map<String, Object>) request.get("capture")).put("captured_at_utc", Instant.now().plusSeconds(3600).toString());
			problem(create(asha, request, UUID.randomUUID()), "VALIDATION_FAILED", 400);
		}

		@Test
		void unknownFieldsAreRejectedSoIdentifiersCannotSlipIn() throws Exception {
			Map<String, Object> request = consentRequest(Ids.of("ce"), Ids.of("ppt"), all());
			request.put("participant_name", "Synthetic Person");
			MvcResult r = create(asha, request, UUID.randomUUID());
			problem(r, "VALIDATION_FAILED", 400);
			assertThat(r.getResponse().getContentAsString()).doesNotContain("Synthetic Person");
		}

		@Test
		void malformedIdsAndMissingKeyAreRejected() throws Exception {
			problem(create(asha, consentRequest("ce_123", Ids.of("ppt"), all()), UUID.randomUUID()), "VALIDATION_FAILED", 400);
			MvcResult noKey = mvc.perform(post("/v1/consent-events").header(HttpHeaders.AUTHORIZATION, "Bearer " + asha.build())
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(consentRequest(Ids.of("ce"), Ids.of("ppt"), all())))).andReturn();
			problem(noKey, "VALIDATION_FAILED", 400);
		}

		@Test
		void staffNeedAFacilityAndOthersCannotRecordConsent() throws Exception {
			// Facility staff record consent only at a facility (ADR-016); see ClinicIntegrationTests.
			problem(create(clinician, consentRequest(Ids.of("ce"), Ids.of("ppt"), all()), UUID.randomUUID()), "VALIDATION_FAILED", 400);
			problem(create(admin, consentRequest(Ids.of("ce"), Ids.of("ppt"), all()), UUID.randomUUID()), "FORBIDDEN", 403);
			var readOnly = TestTokens.token().roles("ASHA_WORKER").scope("consent:read");
			problem(create(readOnly, consentRequest(Ids.of("ce"), Ids.of("ppt"), all()), UUID.randomUUID()), "FORBIDDEN", 403);
		}

		@Test
		void anotherWorkersParticipantCannotBeTakenOver() throws Exception {
			String participant = Ids.of("ppt");
			newEvent(asha, participant, all());
			problem(create(otherAsha, consentRequest(Ids.of("ce"), participant, all()), UUID.randomUUID()), "FORBIDDEN", 403);
		}

	}

	@Nested
	class Read {

		@Test
		void assignedWorkerCanReadAndTheReadIsAudited() throws Exception {
			String id = newEvent(asha, Ids.of("ppt"), all());
			MvcResult r = getEvent(asha, id, null);
			assertThat(r.getResponse().getStatus()).isEqualTo(200);
			assertThat(body(r)).containsEntry("consent_event_id", id);
			assertThat(count("SELECT count(*) FROM audit.audit_event WHERE action = 'CONSENT_EVENT_READ' AND resource_id = ?", id))
				.isEqualTo(1);
		}

		@Test
		void otherWorkersAndCliniciansSeeNotFound() throws Exception {
			String id = newEvent(asha, Ids.of("ppt"), all());
			problem(getEvent(otherAsha, id, null), "NOT_FOUND", 404);
			problem(getEvent(clinician, id, null), "NOT_FOUND", 404);
			problem(getEvent(asha, Ids.of("ce"), null), "NOT_FOUND", 404);
		}

		@Test
		void adminNeedsAReasonCodeWhichIsAudited() throws Exception {
			String id = newEvent(asha, Ids.of("ppt"), all());
			problem(getEvent(admin, id, null), "VALIDATION_FAILED", 400);
			problem(getEvent(admin, id, "BECAUSE"), "VALIDATION_FAILED", 400);
			assertThat(getEvent(admin, id, "AUDIT").getResponse().getStatus()).isEqualTo(200);
			assertThat(count("""
					SELECT count(*) FROM audit.audit_event
					WHERE action = 'CONSENT_EVENT_READ' AND resource_id = ? AND access_reason = 'AUDIT' AND actor_roles = 'ADMIN'
					""", id)).isEqualTo(1);
		}

		@Test
		void statusReflectsTheLatestEvent() throws Exception {
			String participant = Ids.of("ppt");
			String id = newEvent(asha, participant, Set.of(ConsentScope.SCREENING_PARTICIPATION, ConsentScope.AUDIO_RECORDING));
			MvcResult r = status(asha, participant);
			assertThat(r.getResponse().getStatus()).isEqualTo(200);
			assertThat(body(r)).containsEntry("status", "ACTIVE").containsEntry("active_consent_event_id", id);
			problem(status(otherAsha, participant), "NOT_FOUND", 404);
			problem(status(asha, Ids.of("ppt")), "NOT_FOUND", 404);
		}

	}

	@Nested
	class Withdraw {

		@Test
		void withdrawingResearchReuseCascadesToModelTraining() throws Exception {
			String id = newEvent(asha, Ids.of("ppt"), all());
			MvcResult r = withdraw(asha, id, withdrawal(List.of("RESEARCH_FEATURE_REUSE")), UUID.randomUUID(), null);
			assertThat(r.getResponse().getStatus()).isEqualTo(202);
			Map<String, Object> w = body(r);
			assertThat(list(w.get("withdrawn_scopes"))).containsExactlyInAnyOrder("RESEARCH_FEATURE_REUSE", "MODEL_TRAINING");
			assertThat(w).containsEntry("resulting_status", "PARTIALLY_WITHDRAWN");
			assertThat(list(w.get("downstream_actions")))
				.containsExactly(Map.of("action", "EXCLUDE_FROM_RESEARCH_EXPORT", "status", "NOT_APPLICABLE"));
			assertThat(list(body(getEvent(asha, id, null)).get("effective_scopes")))
				.doesNotContain("RESEARCH_FEATURE_REUSE", "MODEL_TRAINING")
				.contains("AUDIO_RECORDING");
		}

		@Test
		void withdrawingAudioRecordingCascadesToEverythingThatDependsOnIt() throws Exception {
			String id = newEvent(asha, Ids.of("ppt"), all());
			Map<String, Object> w = body(withdraw(asha, id, withdrawal(List.of("AUDIO_RECORDING")), UUID.randomUUID(), null));
			assertThat(list(w.get("withdrawn_scopes"))).containsExactlyInAnyOrder("AUDIO_RECORDING", "AUDIO_RETENTION",
					"RESEARCH_FEATURE_REUSE", "MODEL_TRAINING", "QUALITY_REVIEW");
			assertThat(w).containsEntry("resulting_status", "PARTIALLY_WITHDRAWN");
		}

		@Test
		void withdrawingEverythingAndRepeatingIsANoOp() throws Exception {
			String id = newEvent(asha, Ids.of("ppt"), all());
			MvcResult all = withdraw(asha, id, withdrawal(null), UUID.randomUUID(), null);
			assertThat(all.getResponse().getStatus()).isEqualTo(202);
			assertThat(body(all)).containsEntry("resulting_status", "WITHDRAWN");
			MvcResult again = withdraw(asha, id, withdrawal(List.of("AUDIO_RECORDING")), UUID.randomUUID(), null);
			assertThat(again.getResponse().getStatus()).isEqualTo(200);
			assertThat(list(body(again).get("withdrawn_scopes"))).isEmpty();
			assertThat(body(again)).containsEntry("resulting_status", "WITHDRAWN");
		}

		@Test
		void withdrawalReplayReturnsTheOriginal() throws Exception {
			String id = newEvent(asha, Ids.of("ppt"), all());
			UUID key = UUID.randomUUID();
			Map<String, Object> request = withdrawal(List.of("QUALITY_REVIEW"));
			MvcResult first = withdraw(asha, id, request, key, null);
			MvcResult second = withdraw(asha, id, request, key, null);
			assertThat(first.getResponse().getStatus()).isEqualTo(202);
			assertThat(second.getResponse().getStatus()).isEqualTo(200);
			assertThat(body(second)).isEqualTo(body(first));
		}

		@Test
		void othersCannotWithdrawButAdminCanWithAReason() throws Exception {
			String id = newEvent(asha, Ids.of("ppt"), all());
			problem(withdraw(otherAsha, id, withdrawal(null), UUID.randomUUID(), null), "NOT_FOUND", 404);
			problem(withdraw(clinician, id, withdrawal(null), UUID.randomUUID(), null), "NOT_FOUND", 404);
			problem(withdraw(admin, id, withdrawal(null), UUID.randomUUID(), null), "VALIDATION_FAILED", 400);
			assertThat(withdraw(admin, id, withdrawal(null), UUID.randomUUID(), "DATA_SUBJECT_REQUEST").getResponse().getStatus())
				.isEqualTo(202);
		}

		@Test
		void consentHistoryIsAppendOnly() throws Exception {
			String id = newEvent(asha, Ids.of("ppt"), all());
			assertThatThrownBy(() -> jdbc.sql("UPDATE consent.scope_decision SET granted = true WHERE consent_event_id = ?").param(id).update())
				.isInstanceOf(DataAccessException.class);
			assertThatThrownBy(() -> jdbc.sql("DELETE FROM consent.consent_event WHERE consent_event_id = ?").param(id).update())
				.isInstanceOf(DataAccessException.class);
		}

	}

	@Nested
	class Gate {

		@Test
		void passesWhenScopesAreInEffect() throws Exception {
			String participant = Ids.of("ppt");
			String id = newEvent(asha, participant, all());
			ConsentSnapshot snapshot = gate.require(id, participant, Set.of(ConsentScope.AUDIO_RECORDING), Instant.now());
			assertThat(snapshot.scopesVerified()).contains(ConsentScope.AUDIO_RECORDING);
		}

		@Test
		void rejectsAnotherParticipantsEvent() throws Exception {
			String id = newEvent(asha, Ids.of("ppt"), all());
			assertCode(() -> gate.require(id, Ids.of("ppt"), Set.of(ConsentScope.AUDIO_RECORDING), Instant.now()),
					ProblemCode.CONSENT_PARTICIPANT_MISMATCH);
		}

		@Test
		void rejectsScopesNotGrantedOrWithdrawn() throws Exception {
			String participant = Ids.of("ppt");
			String id = newEvent(asha, participant, Set.of(ConsentScope.SCREENING_PARTICIPATION));
			assertCode(() -> gate.require(id, participant, Set.of(ConsentScope.AUDIO_RECORDING), Instant.now()),
					ProblemCode.CONSENT_NOT_ACTIVE);
		}

		@Test
		void rejectsDataCapturedBeforeConsent() throws Exception {
			String participant = Ids.of("ppt");
			String id = newEvent(asha, participant, all());
			assertCode(() -> gate.require(id, participant, Set.of(ConsentScope.AUDIO_RECORDING), Instant.now().minusSeconds(86400)),
					ProblemCode.CONSENT_NOT_ACTIVE);
		}

		@Test
		void aNewerEventNeverRevivesAWithdrawnOne() throws Exception {
			String participant = Ids.of("ppt");
			String first = newEvent(asha, participant, all());
			withdraw(asha, first, withdrawal(null), UUID.randomUUID(), null);
			String second = newEvent(asha, participant, all());
			assertCode(() -> gate.require(first, participant, Set.of(ConsentScope.AUDIO_RECORDING), Instant.now()),
					ProblemCode.CONSENT_NOT_ACTIVE);
			assertThat(gate.require(second, participant, Set.of(ConsentScope.AUDIO_RECORDING), Instant.now())).isNotNull();
		}

		private void assertCode(Runnable call, ProblemCode code) {
			assertThatThrownBy(call::run).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo(code));
		}

	}

}
