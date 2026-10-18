package org.mentalwellness.api.shared;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mentalwellness.api.IntegrationTest;
import org.mentalwellness.api.support.ContractProblemSchema;
import org.mentalwellness.api.support.TestOnlyController;
import org.mentalwellness.api.support.TestTokens;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ApiSecurityIntegrationTests {

	private static final String CORRELATION = "X-Correlation-ID";

	@Autowired
	MockMvc mvc;

	@Autowired
	ObjectMapper json;

	@Autowired
	JdbcClient jdbc;

	// --- health ------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness" })
	void healthProbesAreAnonymousAndStatusOnly(String path) throws Exception {
		MvcResult result = this.mvc.perform(get(path)).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		Map<String, Object> body = body(result);
		assertThat(body).containsEntry("status", "UP");
		// Only the status and, on the root endpoint, the probe group names; no components or details.
		assertThat(body.keySet()).isSubsetOf("status", "groups");
	}

	@ParameterizedTest
	@ValueSource(strings = { "/actuator/env", "/actuator/info", "/actuator/beans", "/actuator/modulith", "/actuator" })
	void otherActuatorEndpointsNeedATokenAndAreNotExposed(String path) throws Exception {
		assertProblem(this.mvc.perform(get(path)).andReturn(), "UNAUTHENTICATED", 401);
		String admin = TestTokens.token().roles("ADMIN").build();
		MvcResult authed = this.mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andReturn();
		assertProblem(authed, "NOT_FOUND", 404);
	}

	@Test
	void protectedResourceMetadataDescribesTheApiAccurately() throws Exception {
		MvcResult result = this.mvc.perform(get("/.well-known/oauth-protected-resource")).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		Map<String, Object> metadata = body(result);
		assertThat(metadata).containsEntry("authorization_servers", java.util.List.of(TestTokens.ISSUER))
			.containsEntry("tls_client_certificate_bound_access_tokens", false)
			.containsEntry("bearer_methods_supported", java.util.List.of("header"));
		assertThat(metadata.get("scopes_supported")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
			.contains("consent:write", "processing:worker");
	}

	// --- authentication ------------------------------------------------------------

	@Test
	void requestWithoutTokenIsRejectedWithProblemAndBearerChallenge() throws Exception {
		MvcResult result = this.mvc.perform(get("/test-only/ping")).andReturn();
		assertProblem(result, "UNAUTHENTICATED", 401);
		assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
	}

	@Test
	void validTokenIsAccepted() throws Exception {
		MvcResult result = this.mvc.perform(withToken(get("/test-only/ping"), TestTokens.token().roles("ASHA_WORKER").build()))
			.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void expiredTokenIsRejected() throws Exception {
		String token = TestTokens.token().expiresAt(Instant.now().minusSeconds(600)).build();
		MvcResult result = this.mvc.perform(withToken(get("/test-only/ping"), token)).andReturn();
		assertProblem(result, "UNAUTHENTICATED", 401);
		assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains("invalid_token");
	}

	@Test
	void wrongAudienceIsRejected() throws Exception {
		assertRejected(TestTokens.token().audience("account").build());
	}

	@Test
	void wrongIssuerIsRejected() throws Exception {
		assertRejected(TestTokens.token().issuer("http://localhost:8081/realms/other").build());
	}

	@Test
	void tokenSignedByUnknownKeyIsRejected() throws Exception {
		assertRejected(TestTokens.token().signedWithUnknownKey().build());
	}

	@Test
	void hs256TokenIsRejected() throws Exception {
		assertRejected(TestTokens.token().buildHs256());
	}

	@Test
	void unsignedTokenIsRejected() throws Exception {
		assertRejected(TestTokens.token().buildUnsigned());
	}

	@Test
	void garbageTokenIsRejected() throws Exception {
		assertRejected("not.a.jwt");
	}

	@Test
	void noSessionCookieIsCreated() throws Exception {
		MvcResult result = this.mvc.perform(withToken(get("/test-only/ping"), TestTokens.token().build())).andReturn();
		assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
		assertThat(result.getRequest().getSession(false)).isNull();
	}

	// --- authorization -------------------------------------------------------------

	@Test
	void wrongRoleIsForbiddenAndAudited() throws Exception {
		var token = TestTokens.token().roles("ASHA_WORKER");
		MvcResult result = this.mvc.perform(withToken(get("/test-only/admin"), token.build())).andReturn();
		Map<String, Object> problem = assertProblem(result, "FORBIDDEN", 403);

		Map<String, Object> row = this.jdbc
			.sql("SELECT action, outcome, actor_ref, actor_roles FROM audit.audit_event WHERE correlation_id = ?")
			.param(UUID.fromString((String) problem.get("correlation_id")))
			.query()
			.singleRow();
		assertThat(row).containsEntry("action", "ACCESS_DENIED")
			.containsEntry("outcome", "DENIED")
			.containsEntry("actor_ref", token.subject())
			.containsEntry("actor_roles", "ASHA_WORKER");
	}

	@Test
	void correctRoleIsAllowed() throws Exception {
		MvcResult result = this.mvc.perform(withToken(get("/test-only/admin"), TestTokens.token().roles("ADMIN").build()))
			.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void nonPlatformRealmRolesGrantNothing() throws Exception {
		String token = TestTokens.token().roles("admin", "offline_access", "SUPERUSER").build();
		assertProblem(this.mvc.perform(withToken(get("/test-only/admin"), token)).andReturn(), "FORBIDDEN", 403);
	}

	// --- correlation IDs -----------------------------------------------------------

	@Test
	void validClientCorrelationIdIsEchoedInHeaderAndBody() throws Exception {
		String id = UUID.randomUUID().toString();
		MvcResult result = this.mvc.perform(get("/test-only/ping").header(CORRELATION, id)).andReturn();
		assertThat(result.getResponse().getHeader(CORRELATION)).isEqualTo(id);
		assertThat(body(result)).containsEntry("correlation_id", id);
	}

	@ParameterizedTest
	@ValueSource(strings = { "<script>alert(1)</script>", "not-a-uuid", "123", "6F1C2B8E-3D4A-4F5B-9C6D-7E8F9A0B1C2D-extra" })
	void malformedCorrelationIdIsReplacedNotReflected(String bad) throws Exception {
		MvcResult result = this.mvc.perform(get("/test-only/ping").header(CORRELATION, bad)).andReturn();
		String echoed = result.getResponse().getHeader(CORRELATION);
		assertThat(echoed).isNotEqualTo(bad);
		assertThat(UUID.fromString(echoed)).isNotNull();
		assertThat(result.getResponse().getContentAsString()).doesNotContain(bad);
	}

	@Test
	void correlationIdIsGeneratedWhenAbsent() throws Exception {
		MvcResult result = this.mvc.perform(withToken(get("/test-only/ping"), TestTokens.token().build())).andReturn();
		assertThat(UUID.fromString(result.getResponse().getHeader(CORRELATION))).isNotNull();
	}

	// --- error handling ------------------------------------------------------------

	@Test
	void unexpectedErrorRevealsNothingToClientOrLogs(CapturedOutput output) throws Exception {
		MvcResult result = this.mvc.perform(withToken(get("/test-only/boom"), TestTokens.token().build())).andReturn();
		assertProblem(result, "INTERNAL_ERROR", 500);
		String responseBody = result.getResponse().getContentAsString();
		assertThat(responseBody).doesNotContain(TestOnlyController.SENSITIVE_MESSAGE)
			.doesNotContain("IllegalStateException")
			.doesNotContain("trace");
		assertThat(output.getAll()).as("logs keep the exception type but not its message")
			.contains("IllegalStateException")
			.contains("message redacted")
			.doesNotContain(TestOnlyController.SENSITIVE_MESSAGE);
	}

	@Test
	void validationErrorsPointAtFieldsWithoutEchoingValues() throws Exception {
		String rejected = "sess_<injected-value>";
		MvcResult result = this.mvc.perform(withToken(post("/test-only/echo"), TestTokens.token().build())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"session_id\":\"" + rejected + "\"}")).andReturn();
		Map<String, Object> problem = assertProblem(result, "VALIDATION_FAILED", 400);
		assertThat(problem.get("errors").toString()).contains("/session_id").contains("pattern");
		assertThat(result.getResponse().getContentAsString()).doesNotContain(rejected);
	}

	@Test
	void malformedJsonIsAValidationProblem() throws Exception {
		MvcResult result = this.mvc.perform(withToken(post("/test-only/echo"), TestTokens.token().build())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{not json")).andReturn();
		assertProblem(result, "VALIDATION_FAILED", 400);
		assertThat(result.getResponse().getContentAsString()).doesNotContain("not json");
	}

	@Test
	void unsupportedContentTypeIsAProblem() throws Exception {
		MvcResult result = this.mvc.perform(withToken(post("/test-only/echo"), TestTokens.token().build())
			.contentType(MediaType.TEXT_PLAIN)
			.content("x")).andReturn();
		assertProblem(result, "UNSUPPORTED_MEDIA_TYPE", 415);
	}

	@Test
	void unknownPathIsANotFoundProblem() throws Exception {
		assertProblem(this.mvc.perform(withToken(get("/v1/does-not-exist"), TestTokens.token().build())).andReturn(),
				"NOT_FOUND", 404);
	}

	@Test
	void wrongMethodIsAMethodNotAllowedProblem() throws Exception {
		assertProblem(this.mvc.perform(withToken(delete("/test-only/ping"), TestTokens.token().build())).andReturn(),
				"METHOD_NOT_ALLOWED", 405);
	}

	// --- helpers -------------------------------------------------------------------

	private void assertRejected(String token) throws Exception {
		assertProblem(this.mvc.perform(withToken(get("/test-only/ping"), token)).andReturn(), "UNAUTHENTICATED", 401);
	}

	private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
	}

	private Map<String, Object> assertProblem(MvcResult result, String code, int status) throws Exception {
		assertThat(result.getResponse().getStatus()).isEqualTo(status);
		assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
		Map<String, Object> body = body(result);
		ContractProblemSchema.assertConforms(body, code, status);
		assertThat(body.get("correlation_id")).as("body and header carry the same correlation ID")
			.isEqualTo(result.getResponse().getHeader(CORRELATION));
		return body;
	}

	private Map<String, Object> body(MvcResult result) throws Exception {
		return this.json.readValue(result.getResponse().getContentAsString(), new TypeReference<Map<String, Object>>() {
		});
	}

}
