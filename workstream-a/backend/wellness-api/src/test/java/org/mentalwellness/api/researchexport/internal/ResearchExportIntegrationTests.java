package org.mentalwellness.api.researchexport.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mentalwellness.api.IntegrationTest;
import org.mentalwellness.api.support.ApiDriver;
import org.mentalwellness.api.support.ContractProblemSchema;
import org.mentalwellness.api.support.Fixtures;
import org.mentalwellness.api.support.TestTokens;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
class ResearchExportIntegrationTests {

	static final String KEY = "test-only-export-key-0123456789abcdef0123";

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired ExportService exports;

	final TestTokens.Builder asha = TestTokens.token().roles("ASHA_WORKER").scope(Fixtures.USER_SCOPES);
	final TestTokens.Builder worker = TestTokens.token().roles("PROCESSING_WORKER").scope("processing:worker");
	final Rekeyer rekeyer = new Rekeyer(KEY);
	ApiDriver api;
	Path example;

	@BeforeEach
	void setUp() {
		this.api = new ApiDriver(mvc, json);
		this.example = ContractProblemSchema.contractsDir().resolve("voice-features/examples/valid/complete-pass.json");
	}

	record Export(Map<String, Object> manifest, List<Map<String, Object>> records, String raw) {
		List<String> ids() {
			return records.stream().map(r -> (String) r.get("record_id")).toList();
		}
	}

	Export export() throws Exception {
		ExportService.Result r = exports.export("test-runner");
		String manifest = Files.readString(r.manifest());
		String body = Files.readString(r.recordsFile());
		List<Map<String, Object>> records = body.lines().filter(l -> !l.isBlank())
			.map(l -> json.readValue(l, new TypeReference<Map<String, Object>>() {})).toList();
		return new Export(json.readValue(manifest, new TypeReference<Map<String, Object>>() {}), records, manifest + body);
	}

	@SuppressWarnings("unchecked")
	static List<String> list(Object o) {
		return (List<String>) o;
	}

	@Test
	void exportsConsentedRecordsReKeyedWithoutApplicationIds() throws Exception {
		ApiDriver.Participant p = api.consentedParticipant(asha);
		String recordId = api.processedRecord(asha, worker, p, example);
		Export e = export();
		String rekeyed = rekeyer.rekey(recordId);
		assertThat(e.ids()).contains(rekeyed);
		Map<String, Object> mine = e.records().stream().filter(r -> rekeyed.equals(r.get("record_id"))).findFirst().orElseThrow();
		assertThat(e.raw()).doesNotContain(recordId).doesNotContain(p.participantRef()).doesNotContain(p.consentEventId());
		assertThat(mine.get("consent_reference").toString()).contains(rekeyer.rekey(p.consentEventId()));
		assertThat((String) ((Map<?, ?>) mine.get("provenance")).get("recorded_at_utc")).as("truncated to the date")
			.endsWith("T00:00:00Z");
		assertThat((String) ((Map<?, ?>) mine.get("processing")).get("processed_at_utc")).endsWith("T00:00:00Z");
		assertThat(e.manifest()).containsEntry("record_count", e.records().size());
		assertThat(((Map<?, ?>) e.manifest().get("rekey")).get("key_id")).isEqualTo(rekeyer.keyId());
	}

	@Test
	void reKeyingIsStableAcrossExports() throws Exception {
		String recordId = api.processedRecord(asha, worker, api.consentedParticipant(asha), example);
		assertThat(export().ids()).contains(rekeyer.rekey(recordId));
		assertThat(export().ids()).contains(rekeyer.rekey(recordId));
	}

	@Test
	void sessionsOfOneParticipantShareASplitGroup() throws Exception {
		ApiDriver.Participant p = api.consentedParticipant(asha);
		api.processedRecord(asha, worker, p, example);
		api.processedRecord(asha, worker, p, example);
		ApiDriver.Participant other = api.consentedParticipant(asha);
		api.processedRecord(asha, worker, other, example);
		Map<?, ?> groups = (Map<?, ?>) export().manifest().get("split_groups");
		long mine = groups.values().stream().filter(rekeyer.group(p.participantRef())::equals).count();
		assertThat(mine).isEqualTo(2);
		assertThat(new java.util.ArrayList<Object>(groups.values())).contains(rekeyer.group(other.participantRef()));
		assertThat(groups.toString()).doesNotContain(p.participantRef());
	}

	@Test
	void recordsWithoutTrainingConsentAreMarkedTrainingExcluded() throws Exception {
		ApiDriver.Participant p = api.consentedParticipant(asha,
				Set.of("SCREENING_PARTICIPATION", "AUDIO_RECORDING", "RESEARCH_FEATURE_REUSE"));
		String recordId = api.processedRecord(asha, worker, p, example);
		Export e = export();
		assertThat(e.ids()).contains(rekeyer.rekey(recordId));
		assertThat(list(e.manifest().get("model_training_excluded"))).contains(rekeyer.rekey(recordId));
	}

	@Test
	void recordsWithoutResearchConsentAreNeverExported() throws Exception {
		ApiDriver.Participant p = api.consentedParticipant(asha, Set.of("SCREENING_PARTICIPATION", "AUDIO_RECORDING"));
		String recordId = api.processedRecord(asha, worker, p, example);
		assertThat(export().ids()).doesNotContain(rekeyer.rekey(recordId));
	}

	@Test
	void withdrawalIsAnnouncedOnceAndExcludedThereafter() throws Exception {
		ApiDriver.Participant p = api.consentedParticipant(asha);
		String recordId = api.processedRecord(asha, worker, p, example);
		String rekeyed = rekeyer.rekey(recordId);
		assertThat(export().ids()).contains(rekeyed);

		var w = api.body(api.withdraw(asha, p.consentEventId(), List.of("RESEARCH_FEATURE_REUSE")));
		assertThat(w.get("downstream_actions").toString()).contains("action=EXCLUDE_FROM_RESEARCH_EXPORT, status=SCHEDULED");

		Export next = export();
		assertThat(next.ids()).doesNotContain(rekeyed);
		assertThat(list(next.manifest().get("withdrawn_record_ids"))).contains(rekeyed);
		assertThat(list(export().manifest().get("withdrawn_record_ids"))).as("announced once").doesNotContain(rekeyed);
	}

	@Test
	void audioWithdrawalFlagsAndExcludesDerivedRecords() throws Exception {
		ApiDriver.Participant p = api.consentedParticipant(asha);
		String recordId = api.processedRecord(asha, worker, p, example);
		export();
		api.withdraw(asha, p.consentEventId(), List.of("AUDIO_RECORDING"));
		Export next = export();
		assertThat(next.ids()).doesNotContain(rekeyer.rekey(recordId));
		assertThat(list(next.manifest().get("withdrawn_record_ids"))).contains(rekeyer.rekey(recordId));
		assertThat(((Map<?, ?>) next.manifest().get("exclusions")).get("audio_consent_withdrawn")).isNotEqualTo(0);
	}

	@Test
	void exportRefusesToRunWithoutAProperKey() {
		assertThatThrownBy(() -> new Rekeyer("short")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new Rekeyer(null)).isInstanceOf(IllegalStateException.class);
	}

}
