package org.mentalwellness.api.integration.internal;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mentalwellness.api.IntegrationTest;
import org.mentalwellness.api.support.ApiDriver;
import org.mentalwellness.api.support.ContractProblemSchema;
import org.mentalwellness.api.support.ContractSamples;
import org.mentalwellness.api.support.Fixtures;
import org.mentalwellness.api.support.Ids;
import org.mentalwellness.api.support.TestTokens;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Partner referrals (ADR-015) against a fake partner on 127.0.0.1: the real outbox, dispatcher,
 * signing and inbound event path. Only SIMULATED partners are involved.
 */
@IntegrationTest
class ReferralIntegrationTests {

	static final String SECRET = "test-only-partner-signing-secret-0123456789";

	static final FakePartner PARTNER = FakePartner.start();

	@DynamicPropertySource
	static void partners(DynamicPropertyRegistry registry) {
		registry.add("wellness.integration.dispatch.enabled", () -> "true");
		registry.add("wellness.integration.dispatch.interval", () -> "PT1H");
		// Placeholders, not list items: overriding one list item would replace the whole partner list.
		registry.add("WELLNESS_PARTNER_SIM_ENABLED", () -> "true");
		registry.add("WELLNESS_PARTNER_SIM_ESANJEEVANI_ENABLED", () -> "false");
		registry.add("WELLNESS_PARTNER_SIM_URL", PARTNER::base);
		registry.add("WELLNESS_PARTNER_SIM_SIGNING_SECRET", () -> SECRET);
	}

	@AfterAll
	static void stopPartner() {
		PARTNER.server.stop(0);
	}

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired JdbcClient jdbc;
	@Autowired ReferralDispatcher dispatcher;

	final TestTokens.Builder asha = TestTokens.token().roles("ASHA_WORKER").scope(Fixtures.USER_SCOPES);
	final TestTokens.Builder clinician = TestTokens.token().roles("CLINICIAN").scope(Fixtures.CLINICIAN_SCOPES);
	final TestTokens.Builder partner = TestTokens.token().roles("PARTNER_SYSTEM").scope("partner:events")
		.authorizedParty("partner-simulator");

	ApiDriver driver;

	@BeforeEach
	void setUp() {
		this.driver = new ApiDriver(this.mvc, this.json);
		PARTNER.received.clear();
		PARTNER.status.set(202);
	}

	record Flagged(String participant, String consentEvent, String caseId, String reviewId) {
	}

	/** An urgent visit, reviewed by a clinician with {@code decision}. */
	Flagged flagged(boolean sharing, String decision, String within) throws Exception {
		String participant = Ids.of("ppt");
		String event = Ids.of("ce");
		Instant consentedAt = Instant.now().minusSeconds(3600);
		expect(201, this.driver.postJson("/v1/consent-events", this.asha,
				Fixtures.ashaConsentWithSharing(event, participant, consentedAt, sharing)));
		String caseId = Ids.of("case");
		expect(201, this.driver.postJson("/v1/cases", this.asha, Fixtures.caseCreate(caseId, participant, event, consentedAt)));
		expect(201, this.driver.postJson("/v1/cases/" + caseId + "/questionnaire-responses", this.asha,
				Fixtures.questionnaire(Ids.of("qr"), Instant.now().minusSeconds(300), "OFTEN")));
		String reviewId = Ids.of("rvw");
		expect(201, this.driver.postJson("/v1/review-queue/" + caseId + "/reviews", this.clinician,
				Fixtures.review(reviewId, decision, within, Instant.now())));
		return new Flagged(participant, event, caseId, reviewId);
	}

	@Test
	void aReferralTravelsPseudonymouslyAndStatusComesBack() throws Exception {
		Flagged f = flagged(true, "URGENT_REFER", "WITHIN_24_HOURS");
		MvcResult partners = this.mvc.perform(get("/v1/integration/partners")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + this.clinician.build())).andReturn();
		ContractSamples.record("listReferralPartners", null, partners);
		assertThat(this.driver.body(expect(200, partners)).toString()).contains("sim-tele-manas").contains("SIMULATED")
			.doesNotContain("sim-esanjeevani").doesNotContain("sim-state-hmis");

		String referralId = Ids.of("ref");
		Map<String, Object> queued = this.driver.body(expect(201, refer(f, referralId, "sim-tele-manas", UUID.randomUUID())));
		assertThat(queued).containsEntry("status", "QUEUED").containsEntry("partner_mode", "SIMULATED");
		String handoff = (String) queued.get("handoff_code");
		assertThat(handoff).matches("^[A-Z0-9]{8}$");

		this.dispatcher.dispatchDue();
		FakePartner.Request sent = PARTNER.only(referralId);
		assertThat(sent.path()).isEqualTo("/tele-manas/referrals");
		assertThat(sent.headers().get("X-wellness-signature")).isEqualTo("v1=" + PartnerClient.sign(SECRET,
				sent.headers().get("X-wellness-message-id") + "." + sent.headers().get("X-wellness-timestamp") + "." + sent.body()));
		assertThat(schemaErrors(sent.body())).isZero();
		assertThat(sent.body()).contains(handoff).contains("\"urgent\"").contains("te-IN").contains("URGENT_REVIEW_FLAG")
			.doesNotContain(f.participant()).doesNotContain(f.caseId()).doesNotContain(f.consentEvent())
			.doesNotContain(f.reviewId()).doesNotContain("SELF_HARM").doesNotContain("OFTEN");
		assertThat(referralStatus(referralId)).isEqualTo("SENT");

		expect(201, partnerEvent(this.partner, "sim-tele-manas", event("EVT-1", referralId, "RECEIVED", Instant.now().minusSeconds(60))));
		Map<String, Object> contacted = event("EVT-2", referralId, "CONTACTED", Instant.now().minusSeconds(30));
		contacted.put("partner_reference", "SIM-TM-0042");
		expect(201, partnerEvent(this.partner, "sim-tele-manas", contacted));
		assertThat(this.driver.body(expect(200, partnerEvent(this.partner, "sim-tele-manas", contacted))))
			.containsEntry("status", "CONTACTED").containsEntry("applied", false);
		Map<String, Object> changed = event("EVT-2", referralId, "DECLINED", Instant.now().minusSeconds(30));
		ContractProblemSchema.assertConforms(this.driver.body(partnerEvent(this.partner, "sim-tele-manas", changed)),
				"IDEMPOTENCY_CONFLICT", 409);
		assertThat(this.driver.body(expect(201, partnerEvent(this.partner, "sim-tele-manas",
				event("EVT-0", referralId, "CONTACT_ATTEMPTED", Instant.now().minusSeconds(120)))))).containsEntry("applied", false);

		MvcResult seen = this.mvc.perform(get("/v1/cases/{id}/referrals", f.caseId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + this.asha.build())).andReturn();
		ContractSamples.record("listCaseReferrals", null, seen);
		assertThat(this.driver.body(expect(200, seen)).toString()).contains("CONTACTED").contains(handoff);
		assertThat(this.jdbc.sql("SELECT action FROM audit.audit_event WHERE resource_id = ? ORDER BY id").param(referralId)
			.query(String.class).list()).contains("REFERRAL_CREATED", "REFERRAL_DELIVERED", "REFERRAL_STATUS_RECEIVED");
	}

	@Test
	void sharingConsentIsRequired() throws Exception {
		Flagged f = flagged(false, "REFER", "WITHIN_7_DAYS");
		String referralId = Ids.of("ref");
		ContractProblemSchema.assertConforms(this.driver.body(refer(f, referralId, "sim-tele-manas", UUID.randomUUID())),
				"CONSENT_NOT_ACTIVE", 422);
		assertThat(this.jdbc.sql("SELECT action FROM audit.audit_event WHERE resource_id = ?").param(referralId)
			.query(String.class).list()).containsExactly("REFERRAL_REJECTED");
		assertThat(this.jdbc.sql("SELECT count(*) FROM integration.outbound_message WHERE referral_id = ?").param(referralId)
			.query(Integer.class).single()).isZero();
	}

	@Test
	void onlyTheLatestReferralDecisionCanBeSentAndOnlyOnce() throws Exception {
		Flagged monitor = flagged(true, "MONITOR", "WITHIN_7_DAYS");
		ContractProblemSchema.assertConforms(this.driver.body(refer(monitor, Ids.of("ref"), "sim-tele-manas",
				UUID.randomUUID())), "RESOURCE_CONFLICT", 409);

		Flagged f = flagged(true, "REFER", "WITHIN_7_DAYS");
		Flagged stale = new Flagged(f.participant(), f.consentEvent(), f.caseId(), Ids.of("rvw"));
		ContractProblemSchema.assertConforms(this.driver.body(refer(stale, Ids.of("ref"), "sim-tele-manas",
				UUID.randomUUID())), "RESOURCE_CONFLICT", 409);

		UUID key = UUID.randomUUID();
		String referralId = Ids.of("ref");
		Map<String, Object> first = this.driver.body(expect(201, refer(f, referralId, "sim-tele-manas", key)));
		assertThat(this.driver.body(expect(200, refer(f, referralId, "sim-tele-manas", key)))).isEqualTo(first);
		ContractProblemSchema.assertConforms(this.driver.body(refer(f, Ids.of("ref"), "sim-tele-manas", UUID.randomUUID())),
				"RESOURCE_CONFLICT", 409);
	}

	@Test
	void disabledUnknownAndReportingPartnersAreRefused() throws Exception {
		Flagged f = flagged(true, "REFER", "WITHIN_30_DAYS");
		ContractProblemSchema.assertConforms(this.driver.body(refer(f, Ids.of("ref"), "sim-esanjeevani", UUID.randomUUID())),
				"PARTNER_NOT_ENABLED", 422);
		ContractProblemSchema.assertConforms(this.driver.body(refer(f, Ids.of("ref"), "sim-state-hmis", UUID.randomUUID())),
				"PARTNER_NOT_ENABLED", 422);
		ContractProblemSchema.assertConforms(this.driver.body(refer(f, Ids.of("ref"), "real-tele-manas", UUID.randomUUID())),
				"VALIDATION_FAILED", 400);
		assertThat(refer(f, Ids.of("ref"), "sim-tele-manas", UUID.randomUUID(), this.asha).getResponse().getStatus())
			.isEqualTo(403);
	}

	@Test
	void partnersOnlyReachTheirOwnReferrals() throws Exception {
		Flagged f = flagged(true, "REFER", "WITHIN_7_DAYS");
		String referralId = Ids.of("ref");
		expect(201, refer(f, referralId, "sim-tele-manas", UUID.randomUUID()));
		Map<String, Object> e = event("EVT-X", referralId, "RECEIVED", Instant.now());
		TestTokens.Builder otherClient = TestTokens.token().roles("PARTNER_SYSTEM").scope("partner:events")
			.authorizedParty("someone-else");
		assertThat(partnerEvent(otherClient, "sim-tele-manas", e).getResponse().getStatus()).isEqualTo(403);
		assertThat(partnerEvent(this.partner, "sim-esanjeevani", e).getResponse().getStatus()).isEqualTo(403);
		assertThat(partnerEvent(this.asha, "sim-tele-manas", e).getResponse().getStatus()).isEqualTo(403);
		assertThat(partnerEvent(this.partner, "sim-tele-manas", event("EVT-Y", Ids.of("ref"), "RECEIVED", Instant.now()))
			.getResponse().getStatus()).isEqualTo(404);
		Map<String, Object> notes = event("EVT-Z", referralId, "RECEIVED", Instant.now());
		notes.put("notes", "free text is not accepted");
		assertThat(partnerEvent(this.partner, "sim-tele-manas", notes).getResponse().getStatus()).isEqualTo(400);
	}

	@Test
	void withdrawalRevokesBeforeAndAfterDelivery() throws Exception {
		Flagged early = flagged(true, "REFER", "WITHIN_7_DAYS");
		String undelivered = Ids.of("ref");
		expect(201, refer(early, undelivered, "sim-tele-manas", UUID.randomUUID()));
		expect(202, this.driver.withdraw(this.asha, early.consentEvent(), List.of("EXTERNAL_REFERRAL_SHARING")));
		assertThat(referralStatus(undelivered)).isEqualTo("REVOKED");
		this.dispatcher.dispatchDue();
		assertThat(PARTNER.forReferral(undelivered)).as("cancelled before it left").isEmpty();

		Flagged late = flagged(true, "URGENT_REFER", "WITHIN_24_HOURS");
		String delivered = Ids.of("ref");
		expect(201, refer(late, delivered, "sim-tele-manas", UUID.randomUUID()));
		this.dispatcher.dispatchDue();
		Map<String, Object> withdrawal = this.driver.body(expect(202, this.driver.withdraw(this.asha, late.consentEvent(),
				List.of("SCREENING_PARTICIPATION"))));
		assertThat(withdrawal.toString()).contains("REVOKE_EXTERNAL_REFERRAL").contains("SCHEDULED");
		this.dispatcher.dispatchDue();
		List<FakePartner.Request> sentForDelivered = PARTNER.forReferral(delivered);
		assertThat(sentForDelivered).hasSize(2);
		assertThat(sentForDelivered.get(1).body()).contains("\"revoked\"");
		assertThat(schemaErrors(sentForDelivered.get(1).body())).isZero();
		ContractProblemSchema.assertConforms(this.driver.body(partnerEvent(this.partner, "sim-tele-manas",
				event("EVT-R", delivered, "CONTACTED", Instant.now()))), "RESOURCE_CONFLICT", 409);
	}

	@Test
	void withdrawalDuringAnInFlightSendStillRevokesAtThePartner() throws Exception {
		Flagged f = flagged(true, "URGENT_REFER", "WITHIN_24_HOURS");
		String referralId = Ids.of("ref");
		expect(201, refer(f, referralId, "sim-tele-manas", UUID.randomUUID()));
		// The dispatcher has leased the message and the HTTP call is under way (or the app crashed mid-send).
		this.jdbc.sql("""
				UPDATE integration.outbound_message SET status = 'SENDING', attempts = 1, lease_expires_at = now() + interval '1 hour'
				WHERE referral_id = ?
				""").param(referralId).update();
		Map<String, Object> withdrawal = this.driver.body(expect(202, this.driver.withdraw(this.asha, f.consentEvent(),
				List.of("EXTERNAL_REFERRAL_SHARING"))));
		assertThat(withdrawal.toString()).contains("REVOKE_EXTERNAL_REFERRAL").contains("SCHEDULED");
		assertThat(referralStatus(referralId)).isEqualTo("REVOKED");
		assertThat(this.jdbc.sql("SELECT kind || ':' || status FROM integration.outbound_message WHERE referral_id = ? ORDER BY created_at")
			.param(referralId).query(String.class).list()).containsExactly("REFERRAL:SENDING", "REVOCATION:PENDING");
		this.dispatcher.dispatchDue();
		assertThat(PARTNER.forReferral(referralId)).hasSize(1).first()
			.satisfies(r -> assertThat(r.body()).contains("\"revoked\""));
	}

	@Test
	void consentIsRecheckedWhenTheReferralIsSent() throws Exception {
		Flagged f = flagged(true, "REFER", "WITHIN_7_DAYS");
		String referralId = Ids.of("ref");
		expect(201, refer(f, referralId, "sim-tele-manas", UUID.randomUUID()));
		expect(202, this.driver.withdraw(this.asha, f.consentEvent(), List.of("EXTERNAL_REFERRAL_SHARING")));
		// Simulate the race: the referral committed after the withdrawal's revocation pass ran.
		this.jdbc.sql("UPDATE integration.outbound_message SET status = 'PENDING', next_attempt_at = now() WHERE referral_id = ?")
			.param(referralId).update();
		this.jdbc.sql("UPDATE integration.referral SET status = 'QUEUED' WHERE referral_id = ?").param(referralId).update();
		this.dispatcher.dispatchDue();
		assertThat(PARTNER.forReferral(referralId)).as("never sent without consent").isEmpty();
		assertThat(this.jdbc.sql("SELECT status || ':' || last_error FROM integration.outbound_message WHERE referral_id = ?")
			.param(referralId).query(String.class).single()).isEqualTo("CANCELLED:CONSENT_WITHDRAWN");
		assertThat(referralStatus(referralId)).isEqualTo("REVOKED");
	}

	@Test
	void deliveryRetriesTransientFailuresAndStopsOnRejection() throws Exception {
		Flagged f = flagged(true, "REFER", "WITHIN_7_DAYS");
		String retried = Ids.of("ref");
		expect(201, refer(f, retried, "sim-tele-manas", UUID.randomUUID()));
		PARTNER.status.set(503);
		this.dispatcher.dispatchDue();
		assertThat(this.jdbc.sql("SELECT status || ':' || attempts || ':' || last_error FROM integration.outbound_message WHERE referral_id = ?")
			.param(retried).query(String.class).single()).isEqualTo("PENDING:1:HTTP_503");
		assertThat(referralStatus(retried)).isEqualTo("QUEUED");
		this.dispatcher.dispatchDue();
		assertThat(this.jdbc.sql("SELECT attempts FROM integration.outbound_message WHERE referral_id = ?").param(retried)
			.query(Integer.class).single()).as("backoff: not due yet, so not attempted again").isEqualTo(1);

		Flagged g = flagged(true, "REFER", "WITHIN_7_DAYS");
		String rejected = Ids.of("ref");
		expect(201, refer(g, rejected, "sim-tele-manas", UUID.randomUUID()));
		PARTNER.status.set(400);
		this.dispatcher.dispatchDue();
		assertThat(referralStatus(rejected)).isEqualTo("DELIVERY_FAILED");
	}

	@Test
	void realPartnersCannotStartAndSimulatedOnesStayLocal() {
		var real = new PartnerProperties.Partner("real-tele-manas", "Tele-MANAS", PartnerProperties.Kind.TELE_MANAS,
				PartnerProperties.Mode.REAL, false, null, null, null, null, null);
		assertThatThrownBy(() -> new PartnerRegistry(new PartnerProperties(List.of(real), null), Set.of("SYNTHETIC")))
			.isInstanceOf(IllegalStateException.class);
		var signedButUnapprovedEnvironment = new PartnerProperties.Partner("real-tele-manas", "Tele-MANAS",
				PartnerProperties.Kind.TELE_MANAS, PartnerProperties.Mode.REAL, false, null, null, null, "DSA-1", "Named Person");
		assertThatThrownBy(() -> new PartnerRegistry(new PartnerProperties(List.of(signedButUnapprovedEnvironment), null),
				Set.of("SYNTHETIC", "AUTHORIZED_TEST_AUDIO"))).isInstanceOf(IllegalStateException.class);
		var remote = new PartnerProperties.Partner("sim-tele-manas", "x", PartnerProperties.Kind.TELE_MANAS,
				PartnerProperties.Mode.SIMULATED, true, URI.create("https://partner.example.org/api"), "partner-simulator",
				SECRET, null, null);
		assertThatThrownBy(() -> new PartnerRegistry(new PartnerProperties(List.of(remote), null), Set.of("SYNTHETIC")))
			.isInstanceOf(IllegalStateException.class);
	}

	// --- helpers -----------------------------------------------------------------------

	MvcResult refer(Flagged f, String referralId, String partnerId, UUID key) throws Exception {
		return refer(f, referralId, partnerId, key, this.clinician);
	}

	MvcResult refer(Flagged f, String referralId, String partnerId, UUID key, TestTokens.Builder who) throws Exception {
		String content = this.json.writeValueAsString(Map.of("referral_id", referralId, "partner_id", partnerId,
				"review_id", f.reviewId()));
		MvcResult r = this.mvc.perform(post("/v1/review-queue/{id}/referrals", f.caseId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())
			.header("Idempotency-Key", key.toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(content)).andReturn();
		ContractSamples.record("createReferral", content, r);
		return r;
	}

	MvcResult partnerEvent(TestTokens.Builder who, String partnerId, Map<String, Object> body) throws Exception {
		String content = this.json.writeValueAsString(body);
		MvcResult r = this.mvc.perform(post("/internal/v1/partners/{p}/referral-events", partnerId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())
			.contentType(MediaType.APPLICATION_JSON)
			.content(content)).andReturn();
		ContractSamples.record("integration-internal.v1.yaml", "postReferralEvent", content, r);
		return r;
	}

	static Map<String, Object> event(String eventId, String referralId, String status, Instant at) {
		Map<String, Object> e = new java.util.LinkedHashMap<>();
		e.put("event_id", eventId);
		e.put("referral_id", referralId);
		e.put("status", status);
		e.put("occurred_at_utc", at.toString());
		return e;
	}

	String referralStatus(String referralId) {
		return this.jdbc.sql("SELECT status FROM integration.referral WHERE referral_id = ?").param(referralId)
			.query(String.class).single();
	}

	int schemaErrors(String body) throws IOException {
		Path schemaFile = Path.of(System.getProperty("wellness.contracts.dir"), "integration", "referral-bundle.schema.json");
		try (InputStream in = Files.newInputStream(schemaFile)) {
			JsonNode node = this.json.readTree(body);
			return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in).validate(node).size();
		}
	}

	static MvcResult expect(int status, MvcResult r) throws Exception {
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
		return r;
	}

	/** A partner on 127.0.0.1 that records what it receives and answers with a settable status. */
	static final class FakePartner {

		record Request(String path, Map<String, String> headers, String body) {
		}

		final HttpServer server;

		final List<Request> received = new CopyOnWriteArrayList<>();

		final AtomicInteger status = new AtomicInteger(202);

		private FakePartner(HttpServer server) {
			this.server = server;
		}

		static FakePartner start() {
			try {
				HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
				FakePartner partner = new FakePartner(server);
				server.createContext("/", exchange -> {
					String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
					Map<String, String> headers = new java.util.HashMap<>();
					exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, v.get(0)));
					partner.received.add(new Request(exchange.getRequestURI().getPath(), headers, body));
					byte[] reply = "{\"partner_reference\":\"SIM-TM-0001\"}".getBytes(StandardCharsets.UTF_8);
					exchange.getResponseHeaders().add("Content-Type", "application/json");
					exchange.sendResponseHeaders(partner.status.get(), reply.length);
					exchange.getResponseBody().write(reply);
					exchange.close();
				});
				server.start();
				return partner;
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
		}

		List<Request> forReferral(String referralId) {
			return this.received.stream().filter(r -> r.body().contains(referralId)).toList();
		}

		Request only(String referralId) {
			List<Request> matching = forReferral(referralId);
			assertThat(matching).hasSize(1);
			return matching.get(0);
		}

		String base() {
			return "http://127.0.0.1:" + this.server.getAddress().getPort();
		}

	}

}
