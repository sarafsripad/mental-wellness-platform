package org.mentalwellness.api.careteam;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mentalwellness.api.IntegrationTest;
import org.mentalwellness.api.shared.security.Actor;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Care teams (ADR-016): who reaches a participant, through which facility, in which role. */
@IntegrationTest
class CareTeamIntegrationTests {

	static final String STAFF_SCOPES = "careteam:read review:read";

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired JdbcClient jdbc;
	@Autowired CareTeams careTeams;

	final TestTokens.Builder asha = TestTokens.token().roles("ASHA_WORKER").scope(Fixtures.USER_SCOPES + " careteam:read careteam:write");
	final TestTokens.Builder otherAsha = TestTokens.token().roles("ASHA_WORKER").scope(Fixtures.USER_SCOPES + " careteam:read careteam:write");
	final TestTokens.Builder admin = TestTokens.token().roles("ADMIN").scope("careteam:admin");
	final TestTokens.Builder doctor = TestTokens.token().roles("CLINICIAN").scope(STAFF_SCOPES);
	final TestTokens.Builder otherDoctor = TestTokens.token().roles("CLINICIAN").scope(STAFF_SCOPES);
	final TestTokens.Builder nurse = TestTokens.token().roles("HEALTH_STAFF").scope(STAFF_SCOPES);

	ApiDriver driver;
	String facility;
	String otherFacility;
	String participant;
	String consentEvent;

	@BeforeEach
	void setUp() throws Exception {
		this.driver = new ApiDriver(this.mvc, this.json);
		this.facility = "phc-" + UUID.randomUUID().toString().substring(0, 8);
		this.otherFacility = "chc-" + UUID.randomUUID().toString().substring(0, 8);
		expect(200, putFacility(this.facility, "PHC", true, "AUDIT"));
		expect(200, putFacility(this.otherFacility, "CHC", true, "AUDIT"));
		expect(200, putMembership(this.facility, this.doctor.subject(), "CLINICIAN", true));
		expect(200, putMembership(this.facility, this.nurse.subject(), "HEALTH_STAFF", true));
		expect(200, putMembership(this.otherFacility, this.otherDoctor.subject(), "CLINICIAN", true));
		this.participant = Ids.of("ppt");
		this.consentEvent = Ids.of("ce");
		expect(201, this.driver.postJson("/v1/consent-events", this.asha,
				Fixtures.ashaConsent(this.consentEvent, this.participant, "kn-IN", Instant.now().minusSeconds(600))));
	}

	@Test
	void theAshaWorkerRefersAndOnlyThatFacilitysStaffGetAccess() throws Exception {
		assertThat(readCareTeam(this.doctor).getResponse().getStatus()).as("not on the care team yet").isEqualTo(404);
		Map<String, Object> team = this.driver.body(expect(200, addFacility(this.asha, this.facility, "ASHA_REFERRAL", null)));
		assertThat(team.toString()).contains(this.facility).contains("ASHA_REFERRAL");
		assertThat((String) team.get("asha_worker_ref")).matches("^wkr_[a-f0-9]{32}$");

		expect(200, readCareTeam(this.doctor));
		expect(200, readCareTeam(this.nurse));
		expect(200, readCareTeam(this.asha));
		assertThat(readCareTeam(this.otherDoctor).getResponse().getStatus()).as("other facility").isEqualTo(404);
		assertThat(readCareTeam(this.otherAsha).getResponse().getStatus()).isEqualTo(404);
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'CARE_TEAM_READ' AND resource_id = ?")
			.param(this.participant).query(Integer.class).single()).isGreaterThanOrEqualTo(3);
	}

	@Test
	void accessNeedsTheRoleInTheTokenAnActiveMembershipAndAnActiveFacility() throws Exception {
		expect(200, addFacility(this.asha, this.facility, "ASHA_REFERRAL", null));
		Actor doctorActor = new Actor(this.doctor.subject(), Set.of("CLINICIAN"));
		assertThat(this.careTeams.staffAccess(doctorActor, this.participant, Set.of(CareRole.CLINICIAN))).isTrue();
		assertThat(this.careTeams.staffAccess(new Actor(this.doctor.subject(), Set.of("HEALTH_STAFF")), this.participant,
				Set.of(CareRole.CLINICIAN, CareRole.HEALTH_STAFF))).as("membership role must match a token role").isFalse();
		assertThat(this.careTeams.staffAccess(new Actor(this.nurse.subject(), Set.of("HEALTH_STAFF")), this.participant,
				Set.of(CareRole.CLINICIAN))).as("nurse is not a clinician").isFalse();

		expect(200, putMembership(this.facility, this.doctor.subject(), "CLINICIAN", false));
		assertThat(this.careTeams.staffAccess(doctorActor, this.participant, Set.of(CareRole.CLINICIAN))).isFalse();
		expect(200, putMembership(this.facility, this.doctor.subject(), "CLINICIAN", true));
		expect(200, putFacility(this.facility, "PHC", false, "AUDIT"));
		assertThat(this.careTeams.staffAccess(doctorActor, this.participant, Set.of(CareRole.CLINICIAN))).isFalse();
	}

	@Test
	void onlyTheAshaWorkerOrAnAdminAddsAFacilityAndConsentMustBeInEffect() throws Exception {
		ContractProblemSchema.assertConforms(this.driver.body(addFacility(this.asha, this.facility, "CLINIC_REGISTRATION", null)),
				"VALIDATION_FAILED", 400);
		assertThat(addFacility(this.otherAsha, this.facility, "ASHA_REFERRAL", null).getResponse().getStatus()).isEqualTo(403);
		assertThat(addFacility(this.doctor, this.facility, "ASHA_REFERRAL", null).getResponse().getStatus()).isEqualTo(403);
		ContractProblemSchema.assertConforms(this.driver.body(addFacility(this.admin, this.facility, "TRANSFER", null)),
				"VALIDATION_FAILED", 400);
		expect(200, addFacility(this.admin, this.otherFacility, "TRANSFER", "SUPPORT_TICKET"));
		ContractProblemSchema.assertConforms(this.driver.body(addFacility(this.asha, "no-such-facility", "ASHA_REFERRAL", null)),
				"NOT_FOUND", 404);

		expect(202, this.driver.withdraw(this.asha, this.consentEvent, List.of("SCREENING_PARTICIPATION")));
		ContractProblemSchema.assertConforms(this.driver.body(addFacility(this.asha, this.facility, "ASHA_REFERRAL", null)),
				"CONSENT_NOT_ACTIVE", 422);
	}

	@Test
	void administrationAlwaysNeedsAReasonAndIsAudited() throws Exception {
		ContractProblemSchema.assertConforms(this.driver.body(putFacility("phc-noreason", "PHC", true, null)),
				"VALIDATION_FAILED", 400);
		assertThat(putFacilityAs(this.doctor, "phc-bydoctor").getResponse().getStatus()).isEqualTo(403);
		assertThat(this.jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'STAFF_MEMBERSHIP_UPSERTED' AND access_reason = 'AUDIT'")
			.query(Integer.class).single()).isPositive();
	}

	@Test
	void staffSeeOnlyMembershipsTheirTokenRoleAllows() throws Exception {
		MvcResult r = this.mvc.perform(get("/v1/me/care-memberships")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + this.doctor.build())).andReturn();
		ContractSamples.record("listMyCareMemberships", null, r);
		assertThat(this.driver.body(expect(200, r)).toString()).contains(this.facility).doesNotContain(this.otherFacility);
		TestTokens.Builder doctorAsNurse = TestTokens.token().subject(this.doctor.subject()).roles("HEALTH_STAFF").scope(STAFF_SCOPES);
		assertThat(this.driver.body(expect(200, this.mvc.perform(get("/v1/me/care-memberships")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + doctorAsNurse.build())).andReturn())).toString())
			.doesNotContain(this.facility);
		assertThat(this.mvc.perform(get("/v1/me/care-memberships").header(HttpHeaders.AUTHORIZATION, "Bearer " + this.asha.build()))
			.andReturn().getResponse().getStatus()).isEqualTo(403);
	}

	// --- helpers -----------------------------------------------------------------------

	MvcResult putFacility(String code, String kind, boolean active, String reason) throws Exception {
		var req = put("/v1/admin/facilities/{f}", code).header(HttpHeaders.AUTHORIZATION, "Bearer " + this.admin.build())
			.contentType(MediaType.APPLICATION_JSON);
		if (reason != null) {
			req.header("X-Access-Reason", reason);
		}
		String content = this.json.writeValueAsString(Map.of("kind", kind, "facility_label", "Facility " + code + " (synthetic)",
				"active", active));
		MvcResult r = this.mvc.perform(req.content(content)).andReturn();
		ContractSamples.record("putFacility", content, r);
		return r;
	}

	MvcResult putFacilityAs(TestTokens.Builder who, String code) throws Exception {
		return this.mvc.perform(put("/v1/admin/facilities/{f}", code).header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())
			.header("X-Access-Reason", "AUDIT")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"kind\":\"PHC\",\"facility_label\":\"x\",\"active\":true}")).andReturn();
	}

	MvcResult putMembership(String code, String subject, String role, boolean active) throws Exception {
		String content = this.json.writeValueAsString(Map.of("care_role", role, "active", active));
		MvcResult r = this.mvc.perform(put("/v1/admin/facilities/{f}/memberships/{s}", code, subject)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + this.admin.build())
			.header("X-Access-Reason", "AUDIT")
			.contentType(MediaType.APPLICATION_JSON)
			.content(content)).andReturn();
		ContractSamples.record("putStaffMembership", content, r);
		return r;
	}

	MvcResult addFacility(TestTokens.Builder who, String code, String reasonCode, String accessReason) throws Exception {
		String content = this.json.writeValueAsString(Map.of("reason_code", reasonCode, "consent_event_id", this.consentEvent));
		var req = put("/v1/participants/{p}/care-team/{f}", this.participant, code)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())
			.contentType(MediaType.APPLICATION_JSON)
			.content(content);
		if (accessReason != null) {
			req.header("X-Access-Reason", accessReason);
		}
		MvcResult r = this.mvc.perform(req).andReturn();
		ContractSamples.record("addCareTeamFacility", content, r);
		return r;
	}

	MvcResult readCareTeam(TestTokens.Builder who) throws Exception {
		MvcResult r = this.mvc.perform(get("/v1/participants/{p}/care-team", this.participant)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())).andReturn();
		ContractSamples.record("getCareTeam", null, r);
		return r;
	}

	static MvcResult expect(int status, MvcResult r) throws Exception {
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
		return r;
	}

}
