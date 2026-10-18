package org.mentalwellness.api.processing;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import tools.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@IntegrationTest
class ProcessingIntegrationTests {

	static final String WORKER_FILE = "processing-worker.internal.v1.yaml";
	static final String PROFILE = "EGEMAPS_V02_FUNCTIONALS_V1";

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired JdbcClient jdbc;
	@Autowired org.mentalwellness.api.processing.internal.WorkerTestAccess workerAccess;

	final TestTokens.Builder asha = TestTokens.token().roles("ASHA_WORKER").scope(Fixtures.USER_SCOPES);
	final TestTokens.Builder otherAsha = TestTokens.token().roles("ASHA_WORKER").scope(Fixtures.USER_SCOPES);
	final TestTokens.Builder admin = TestTokens.token().roles("ADMIN").scope(Fixtures.USER_SCOPES);
	final TestTokens.Builder worker = TestTokens.token().roles("PROCESSING_WORKER").scope("processing:worker");
	final String workerId = "wrk_" + Ids.of("x").substring(2, 18);

	ApiDriver api;

	@BeforeEach
	void setUp() {
		this.api = new ApiDriver(mvc, json);
		// Each test starts with an empty queue, so claims see only that test's jobs.
		jdbc.sql("UPDATE processing.job SET status = 'CANCELLED', failure_code = 'INTERNAL_ERROR', lease_token_hash = NULL, "
				+ "lease_worker_id = NULL, lease_expires_at = NULL WHERE status IN ('QUEUED', 'RUNNING')").update();
	}

	// --- helpers -------------------------------------------------------------------

	MvcResult submit(TestTokens.Builder who, String recordingId, String reason) throws Exception {
		String content = json.writeValueAsString(Map.of("recording_id", recordingId, "pipeline_profile", PROFILE));
		var req = post("/v1/processing-jobs").header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())
			.header("Idempotency-Key", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(content);
		if (reason != null) {
			req.header("X-Access-Reason", reason);
		}
		MvcResult r = mvc.perform(req).andReturn();
		ContractSamples.record("submitProcessingJob", content, r);
		return r;
	}

	MvcResult getJob(TestTokens.Builder who, String jobId) throws Exception {
		MvcResult r = mvc.perform(get("/v1/processing-jobs/{id}", jobId).header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build()))
			.andReturn();
		ContractSamples.record("getProcessingJob", null, r);
		return r;
	}

	MvcResult claim(TestTokens.Builder who) throws Exception {
		String content = json.writeValueAsString(Map.of("supported_profiles", List.of(PROFILE), "worker_version", "0.1.0"));
		MvcResult r = mvc.perform(post("/internal/v1/processing-jobs/claim").header(HttpHeaders.AUTHORIZATION, "Bearer " + who.build())
			.header("X-Worker-Id", workerId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(content)).andReturn();
		ContractSamples.record(WORKER_FILE, "claimProcessingJob", content, r);
		return r;
	}

	MvcResult audio(String jobId, String token, String asWorker) throws Exception {
		MvcResult r = mvc.perform(get("/internal/v1/processing-jobs/{id}/audio", jobId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + worker.build())
			.header("X-Lease-Token", token)
			.header("X-Worker-Id", asWorker)
			.header("X-Correlation-ID", UUID.randomUUID().toString())).andReturn();
		ContractSamples.record(WORKER_FILE, "fetchJobAudio", null, r);
		return r;
	}

	MvcResult result(String jobId, String token, ObjectNode record) throws Exception {
		String content = json.writeValueAsString(record);
		MvcResult r = mvc.perform(post("/internal/v1/processing-jobs/{id}/result", jobId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + worker.build())
			.header("X-Lease-Token", token)
			.header("X-Worker-Id", workerId)
			.header("X-Correlation-ID", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(content)).andReturn();
		ContractSamples.record(WORKER_FILE, "submitJobResult", content, r);
		return r;
	}

	MvcResult failure(String jobId, String token, Map<String, Object> report) throws Exception {
		String content = json.writeValueAsString(report);
		MvcResult r = mvc.perform(post("/internal/v1/processing-jobs/{id}/failure", jobId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + worker.build())
			.header("X-Lease-Token", token)
			.header("X-Worker-Id", workerId)
			.header("X-Correlation-ID", UUID.randomUUID().toString())
			.contentType(MediaType.APPLICATION_JSON)
			.content(content)).andReturn();
		ContractSamples.record(WORKER_FILE, "reportJobFailure", content, r);
		return r;
	}

	/** The contract's own valid example, with this job's IDs. */
	ObjectNode featureRecord(Map<String, Object> lease) throws Exception {
		ObjectNode record = (ObjectNode) json.readTree(Files.readString(
				ContractProblemSchema.contractsDir().resolve("voice-features/examples/valid/complete-pass.json")));
		record.put("record_id", Ids.of("frec"));
		record.put("session_id", (String) lease.get("session_id"));
		record.put("recording_id", (String) lease.get("recording_id"));
		record.put("processing_job_id", (String) lease.get("job_id"));
		return record;
	}

	record Leased(ApiDriver.Recording recording, String jobId, Map<String, Object> lease) {
		String token() {
			return (String) lease.get("lease_token");
		}
	}

	Leased leasedJob() throws Exception {
		ApiDriver.Recording rec = api.storedRecording(asha, api.consentedParticipant(asha));
		MvcResult s = submit(asha, rec.recordingId(), null);
		assertThat(s.getResponse().getStatus()).as(s.getResponse().getContentAsString()).isEqualTo(202);
		MvcResult c = claim(worker);
		assertThat(c.getResponse().getStatus()).isEqualTo(200);
		Map<String, Object> lease = api.body(c);
		return new Leased(rec, (String) lease.get("job_id"), lease);
	}

	Map<String, Object> problem(MvcResult r, String code, int status) throws Exception {
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(status);
		Map<String, Object> b = api.body(r);
		assertThat(b).containsEntry("code", code).containsEntry("status", status);
		return b;
	}

	String jobStatus(String jobId) {
		return jdbc.sql("SELECT status FROM processing.job WHERE job_id = ?").param(jobId).query(String.class).single();
	}

	// --- public API ----------------------------------------------------------------

	@Test
	void submitQueuesOnceAndReportsStatus() throws Exception {
		ApiDriver.Recording rec = api.storedRecording(asha, api.consentedParticipant(asha));
		MvcResult first = submit(asha, rec.recordingId(), null);
		assertThat(first.getResponse().getStatus()).isEqualTo(202);
		Map<String, Object> job = api.body(first);
		assertThat(job).containsEntry("status", "QUEUED").containsEntry("attempt", 0);
		MvcResult again = submit(asha, rec.recordingId(), null);
		assertThat(again.getResponse().getStatus()).isEqualTo(200);
		assertThat(api.body(again)).containsEntry("job_id", job.get("job_id"));
		MvcResult read = getJob(asha, (String) job.get("job_id"));
		assertThat(read.getResponse().getHeader("Retry-After")).isEqualTo("5");
	}

	@Test
	void recordingMustBeUploadedFirst() throws Exception {
		ApiDriver.Recording rec = api.registeredRecording(asha, api.consentedParticipant(asha));
		problem(submit(asha, rec.recordingId(), null), "RECORDING_NOT_UPLOADED", 422);
	}

	@Test
	void othersCannotSubmitOrReadButAdminCanWithAReason() throws Exception {
		ApiDriver.Recording rec = api.storedRecording(asha, api.consentedParticipant(asha));
		problem(submit(otherAsha, rec.recordingId(), null), "NOT_FOUND", 404);
		String jobId = (String) api.body(submit(asha, rec.recordingId(), null)).get("job_id");
		problem(getJob(otherAsha, jobId), "NOT_FOUND", 404);
		problem(submit(admin, rec.recordingId(), null), "VALIDATION_FAILED", 400);
		assertThat(submit(admin, rec.recordingId(), "SUPPORT_TICKET").getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void rolesAreSeparated() throws Exception {
		problem(claim(asha), "FORBIDDEN", 403);
		ApiDriver.Recording rec = api.storedRecording(asha, api.consentedParticipant(asha));
		problem(submit(worker, rec.recordingId(), null), "FORBIDDEN", 403);
	}

	// --- claim and audio -----------------------------------------------------------

	@Test
	void claimLeasesTheJobWithConsentSnapshot() throws Exception {
		Leased l = leasedJob();
		assertThat(l.lease()).containsEntry("lease_seconds", 300).containsEntry("data_origin", "SYNTHETIC");
		assertThat(l.token()).hasSizeBetween(32, 128);
		assertThat(l.lease().get("consent_reference").toString()).contains("AUDIO_RECORDING");
		assertThat(jobStatus(l.jobId())).isEqualTo("RUNNING");
		assertThat(jdbc.sql("SELECT lease_token_hash FROM processing.job WHERE job_id = ?").param(l.jobId()).query(String.class).single())
			.as("only a hash of the token is stored").isNotEqualTo(l.token()).hasSize(64);
		assertThat(claim(worker).getResponse().getStatus()).as("nothing left to claim").isEqualTo(204);
	}

	@Test
	void audioIsServedOnlyToTheLeaseHolder() throws Exception {
		Leased l = leasedJob();
		MvcResult ok = audio(l.jobId(), l.token(), workerId);
		assertThat(ok.getResponse().getStatus()).isEqualTo(200);
		assertThat(ok.getResponse().getContentAsByteArray()).isEqualTo(l.recording().audio());
		assertThat(ok.getResponse().getHeader("X-Content-SHA256")).isEqualTo(l.recording().sha256());
		problem(audio(l.jobId(), "x".repeat(64), workerId), "LEASE_INVALID", 409);
		problem(audio(l.jobId(), l.token(), "wrk_0000000000000000"), "LEASE_INVALID", 409);
	}

	@Test
	void concurrentClaimsNeverShareAJob() throws Exception {
		ApiDriver.Participant p = api.consentedParticipant(asha);
		for (int i = 0; i < 3; i++) {
			submit(asha, api.storedRecording(asha, p).recordingId(), null);
		}
		ExecutorService pool = Executors.newFixedThreadPool(6);
		List<String> claimed = new ArrayList<>();
		try {
			List<Callable<MvcResult>> calls = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				calls.add(() -> claim(worker));
			}
			for (Future<MvcResult> f : pool.invokeAll(calls)) {
				if (f.get().getResponse().getStatus() == 200) {
					claimed.add((String) api.body(f.get()).get("job_id"));
				}
			}
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(claimed).hasSize(3);
		assertThat(new HashSet<>(claimed)).hasSize(3);
	}

	// --- results -------------------------------------------------------------------

	@Test
	void validResultIsStoredWithServerKnownFieldsOverwritten() throws Exception {
		Leased l = leasedJob();
		ObjectNode record = featureRecord(l.lease());
		record.put("language_tag", "en-IN"); // wrong on purpose: the session says kn-IN
		((ObjectNode) record.get("provenance")).put("data_origin", "AUTHORIZED_TEST_AUDIO");
		((ObjectNode) record.get("consent_reference")).put("consent_event_id", Ids.of("ce"));
		MvcResult r = result(l.jobId(), l.token(), record);
		assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);

		Map<String, Object> stored = json.readValue(jdbc.sql("SELECT record::text FROM processing.feature_record WHERE job_id = ?")
			.param(l.jobId()).query(String.class).single(), Map.class);
		assertThat(stored).containsEntry("language_tag", "kn-IN");
		assertThat(stored.get("provenance").toString()).contains("data_origin=SYNTHETIC");
		assertThat(stored.get("consent_reference").toString()).contains(l.recording().participant().consentEventId());

		Map<String, Object> job = api.body(getJob(asha, l.jobId()));
		assertThat(job).containsEntry("status", "SUCCEEDED").containsEntry("feature_record_id", record.get("record_id").asString());
		assertThat(job.get("quality_outcome").toString()).contains("status=PASS");
		assertThat(api.body(mvc.perform(get("/v1/voice-sessions/{id}", l.recording().sessionId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + asha.build())).andReturn())).containsEntry("status", "PROCESSED");
		assertThat(result(l.jobId(), l.token(), record).getResponse().getStatus()).as("identical resubmission").isEqualTo(200);
	}

	@Test
	void invalidRecordsAreRejectedAndTheJobKeepsRunning() throws Exception {
		Leased l = leasedJob();
		ObjectNode clinical = featureRecord(l.lease());
		((ObjectNode) clinical.get("features").get(0)).put("name", "depression_score");
		Map<String, Object> p = problem(result(l.jobId(), l.token(), clinical), "SCHEMA_INVALID", 400);
		assertThat(p.get("errors").toString()).contains("/features/0/name");
		ObjectNode pii = featureRecord(l.lease());
		pii.put("participant_name", "Synthetic Person");
		problem(result(l.jobId(), l.token(), pii), "SCHEMA_INVALID", 400);
		ObjectNode wrongJob = featureRecord(l.lease());
		wrongJob.put("recording_id", Ids.of("rec"));
		problem(result(l.jobId(), l.token(), wrongJob), "JOB_MISMATCH", 400);
		assertThat(jobStatus(l.jobId())).isEqualTo("RUNNING");
	}

	// --- failures and leases -------------------------------------------------------

	@Test
	void qualityFailureEndsTheJobAndShowsInStatus() throws Exception {
		Leased l = leasedJob();
		MvcResult f = failure(l.jobId(), l.token(), Map.of("code", "AUDIO_QUALITY_FAILED", "retryable", false,
				"quality_outcome", Map.of("status", "FAIL", "flags", List.of("SILENCE"))));
		assertThat(api.body(f)).containsEntry("job_status", "FAILED");
		Map<String, Object> job = api.body(getJob(asha, l.jobId()));
		assertThat(job.get("failure").toString()).contains("code=AUDIO_QUALITY_FAILED");
		assertThat(job.get("quality_outcome").toString()).contains("status=FAIL");
		assertThat(api.body(mvc.perform(get("/v1/voice-sessions/{id}", l.recording().sessionId())
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + asha.build())).andReturn())).containsEntry("status", "PROCESSING_FAILED");
		problem(result(l.jobId(), l.token(), featureRecord(l.lease())), "JOB_NOT_RUNNING", 409);
	}

	@Test
	void retryableFailuresRequeueUntilTheAttemptLimit() throws Exception {
		Leased l = leasedJob();
		Map<String, Object> retryable = Map.of("code", "EXTRACTION_ERROR", "retryable", true);
		assertThat(api.body(failure(l.jobId(), l.token(), retryable))).containsEntry("job_status", "QUEUED");
		String token2 = (String) api.body(claim(worker)).get("lease_token");
		assertThat(api.body(failure(l.jobId(), token2, retryable))).containsEntry("job_status", "QUEUED");
		String token3 = (String) api.body(claim(worker)).get("lease_token");
		assertThat(api.body(failure(l.jobId(), token3, retryable))).as("third attempt is the last").containsEntry("job_status", "FAILED");
		problem(failure(l.jobId(), l.token(), retryable), "JOB_NOT_RUNNING", 409);
	}

	@Test
	void expiredLeasesAreRequeuedAndOldTokensStopWorking() throws Exception {
		Leased l = leasedJob();
		jdbc.sql("UPDATE processing.job SET lease_expires_at = now() - interval '1 minute' WHERE job_id = ?").param(l.jobId()).update();
		problem(audio(l.jobId(), l.token(), workerId), "LEASE_EXPIRED", 409);
		workerAccess.expireLeases();
		assertThat(jobStatus(l.jobId())).isEqualTo("QUEUED");
		problem(audio(l.jobId(), l.token(), workerId), "JOB_NOT_RUNNING", 409);
	}

	// --- consent -------------------------------------------------------------------

	@Test
	void withdrawalDuringProcessingCancelsTheJobAndBlocksTheWorker() throws Exception {
		Leased l = leasedJob();
		MvcResult w = api.withdraw(asha, l.recording().participant().consentEventId(), List.of("AUDIO_RECORDING"));
		assertThat(w.getResponse().getStatus()).isEqualTo(202);
		assertThat(api.body(w).get("downstream_actions").toString())
			.contains("action=CANCEL_QUEUED_PROCESSING, status=COMPLETED");
		problem(audio(l.jobId(), l.token(), workerId), "CONSENT_WITHDRAWN", 409);
		problem(result(l.jobId(), l.token(), featureRecord(l.lease())), "CONSENT_WITHDRAWN", 409);
		assertThat(api.body(getJob(asha, l.jobId())).get("failure").toString()).contains("code=CONSENT_WITHDRAWN");
	}

	@Test
	void withdrawalAfterSuccessFlagsTheFeatureRecord() throws Exception {
		Leased l = leasedJob();
		result(l.jobId(), l.token(), featureRecord(l.lease()));
		MvcResult w = api.withdraw(asha, l.recording().participant().consentEventId(), List.of("AUDIO_RECORDING"));
		assertThat(api.body(w).get("downstream_actions").toString()).contains("action=FLAG_DERIVED_FEATURES, status=COMPLETED");
		assertThat(jdbc.sql("SELECT count(*) FROM processing.feature_record WHERE job_id = ? AND flagged_at IS NOT NULL")
			.param(l.jobId()).query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void claimSkipsJobsWhoseConsentWasWithdrawnElsewhere() throws Exception {
		ApiDriver.Recording rec = api.storedRecording(asha, api.consentedParticipant(asha));
		String jobId = (String) api.body(submit(asha, rec.recordingId(), null)).get("job_id");
		String withdrawal = Ids.of("cw");
		jdbc.sql("INSERT INTO consent.withdrawal (withdrawal_id, consent_event_id, withdrawn_at, channel, recorded_by_subject) "
				+ "VALUES (?, ?, now(), 'WRITTEN_REQUEST', 'test-subject')").params(withdrawal, rec.participant().consentEventId()).update();
		jdbc.sql("INSERT INTO consent.withdrawn_scope VALUES (?, 'AUDIO_RECORDING')").param(withdrawal).update();
		assertThat(claim(worker).getResponse().getStatus()).isEqualTo(204);
		assertThat(jobStatus(jobId)).isEqualTo("CANCELLED");
	}

	@Test
	void runningJobIsStoppedIfConsentDisappearsWithoutTheHandler() throws Exception {
		// Withdrawal recorded without the processing handler running (another instance or channel):
		// the job is still RUNNING, so only the re-checks at audio fetch and result intake stop it.
		Leased l = leasedJob();
		String withdrawal = Ids.of("cw");
		jdbc.sql("INSERT INTO consent.withdrawal (withdrawal_id, consent_event_id, withdrawn_at, channel, recorded_by_subject) "
				+ "VALUES (?, ?, now(), 'WRITTEN_REQUEST', 'test-subject')").params(withdrawal, l.recording().participant().consentEventId()).update();
		jdbc.sql("INSERT INTO consent.withdrawn_scope VALUES (?, 'AUDIO_RECORDING')").param(withdrawal).update();
		assertThat(jobStatus(l.jobId())).isEqualTo("RUNNING");
		problem(audio(l.jobId(), l.token(), workerId), "CONSENT_WITHDRAWN", 409);
		assertThat(jobStatus(l.jobId())).isEqualTo("CANCELLED");
	}

	@Test
	void resultIsRefusedIfConsentDisappearsWithoutTheHandler() throws Exception {
		Leased l = leasedJob();
		String withdrawal = Ids.of("cw");
		jdbc.sql("INSERT INTO consent.withdrawal (withdrawal_id, consent_event_id, withdrawn_at, channel, recorded_by_subject) "
				+ "VALUES (?, ?, now(), 'WRITTEN_REQUEST', 'test-subject')").params(withdrawal, l.recording().participant().consentEventId()).update();
		jdbc.sql("INSERT INTO consent.withdrawn_scope VALUES (?, 'AUDIO_RECORDING')").param(withdrawal).update();
		problem(result(l.jobId(), l.token(), featureRecord(l.lease())), "CONSENT_WITHDRAWN", 409);
		assertThat(jdbc.sql("SELECT count(*) FROM processing.feature_record WHERE job_id = ?").param(l.jobId()).query(Integer.class).single())
			.isZero();
	}

	@Test
	void missingWorkerHeadersAreValidationErrors() throws Exception {
		MvcResult r = mvc.perform(post("/internal/v1/processing-jobs/claim").header(HttpHeaders.AUTHORIZATION, "Bearer " + worker.build())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"supported_profiles\":[\"" + PROFILE + "\"],\"worker_version\":\"0.1.0\"}")).andReturn();
		ContractSamples.record(WORKER_FILE, "claimProcessingJob", null, r);
		problem(r, "VALIDATION_FAILED", 400);
	}

}
