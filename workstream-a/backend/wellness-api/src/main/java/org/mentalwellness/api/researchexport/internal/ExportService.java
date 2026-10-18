package org.mentalwellness.api.researchexport.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.mentalwellness.api.audit.AuditEvent;
import org.mentalwellness.api.audit.AuditLog;
import org.mentalwellness.api.audit.AuditOutcome;
import org.mentalwellness.api.consent.ConsentGate;
import org.mentalwellness.api.consent.ConsentScope;
import org.mentalwellness.api.consent.ConsentSnapshot;
import org.mentalwellness.api.processing.FeatureRecords;
import org.mentalwellness.api.shared.web.DomainException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds one research export (ADR-010, consent-and-data-lifecycle.md section 6). Each export is a
 * full snapshot of the records eligible now: consent is checked at export time, not taken from the
 * record. Only ever started by a person (CLAUDE.md rule 5).
 */
@Service
@EnableConfigurationProperties(ExportProperties.class)
class ExportService {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final FeatureRecords records;

	private final ConsentGate consent;

	private final ExportSchemas schemas;

	private final JdbcClient jdbc;

	private final AuditLog audit;

	private final ObjectMapper json;

	private final ExportProperties properties;

	private final Clock clock;

	ExportService(FeatureRecords records, ConsentGate consent, ExportSchemas schemas, JdbcClient jdbc, AuditLog audit,
			JsonMapper json, ExportProperties properties, Clock clock) {
		this.records = records;
		this.consent = consent;
		this.schemas = schemas;
		this.jdbc = jdbc;
		this.audit = audit;
		this.json = json;
		this.properties = properties;
		this.clock = clock;
	}

	record Result(String exportId, Path manifest, Path recordsFile, int recordCount, int withdrawnCount) {
	}

	@Transactional
	public Result export(String requestedBy) throws IOException {
		if (this.properties.dir() == null) {
			throw new IllegalStateException("wellness.export.dir (WELLNESS_EXPORT_DIR) must be set");
		}
		Rekeyer rekeyer = new Rekeyer(this.properties.key());
		String exportId = "exp_" + hex(16);
		Instant now = this.clock.instant();

		List<String> lines = new ArrayList<>();
		List<String> exportedIds = new ArrayList<>();
		Map<String, String> groups = new TreeMap<>();
		List<String> trainingExcluded = new ArrayList<>();
		List<String> ineligibleIds = new ArrayList<>();
		int noResearch = 0;
		int audioWithdrawn = 0;

		for (FeatureRecords.Stored stored : this.records.all()) {
			if (stored.flagged()) {
				audioWithdrawn++;
				ineligibleIds.add(stored.recordId());
				continue;
			}
			ConsentSnapshot snapshot;
			try {
				snapshot = this.consent.requireStillActive(stored.consentEventId(), Set.of(ConsentScope.RESEARCH_FEATURE_REUSE));
			}
			catch (DomainException ex) {
				noResearch++;
				ineligibleIds.add(stored.recordId());
				continue;
			}
			ObjectNode record = transform((ObjectNode) this.json.readTree(stored.recordJson()), rekeyer);
			List<String> errors = this.schemas.recordErrors(record);
			if (!errors.isEmpty()) {
				throw new IllegalStateException("Exported record failed contract validation at " + errors.get(0));
			}
			String rekeyedId = record.get("record_id").asString();
			lines.add(this.json.writeValueAsString(record));
			exportedIds.add(stored.recordId());
			groups.put(record.get("session_id").asString(), rekeyer.group(stored.participantRef()));
			if (!snapshot.scopesVerified().contains(ConsentScope.MODEL_TRAINING)) {
				trainingExcluded.add(rekeyedId);
			}
		}

		// Records exported before and no longer eligible are announced once as withdrawn.
		List<String> withdrawn = previouslyExportedAndUnannounced(ineligibleIds).stream().map(rekeyer::rekey).sorted().toList();

		byte[] body = (String.join("\n", lines) + (lines.isEmpty() ? "" : "\n")).getBytes(StandardCharsets.UTF_8);
		Map<String, Object> manifest = new LinkedHashMap<>();
		manifest.put("manifest_version", "1.0.0");
		manifest.put("export_id", exportId);
		manifest.put("created_at_utc", now.truncatedTo(ChronoUnit.SECONDS).toString());
		manifest.put("feature_schema_version", "2.0.0");
		manifest.put("records_file", Map.of("name", exportId + ".jsonl", "sha256", sha256(body), "bytes", body.length));
		manifest.put("record_count", lines.size());
		manifest.put("rekey", Map.of("method", Rekeyer.METHOD, "key_id", rekeyer.keyId()));
		manifest.put("split_groups", groups);
		manifest.put("model_training_excluded", trainingExcluded.stream().sorted().toList());
		manifest.put("withdrawn_record_ids", withdrawn);
		manifest.put("exclusions", Map.of("no_research_consent", noResearch, "audio_consent_withdrawn", audioWithdrawn));
		JsonNode manifestNode = this.json.valueToTree(manifest);
		List<String> manifestErrors = this.schemas.manifestErrors(manifestNode);
		if (!manifestErrors.isEmpty()) {
			throw new IllegalStateException("Export manifest failed contract validation at " + manifestErrors.get(0));
		}

		this.jdbc.sql("""
				INSERT INTO research_export.export (export_id, record_count, withdrawn_count, records_sha256, key_id, requested_by)
				VALUES (?, ?, ?, ?, ?, ?)
				""").params(exportId, lines.size(), withdrawn.size(), sha256(body), rekeyer.keyId(), requestedBy).update();
		for (String id : exportedIds) {
			this.jdbc.sql("INSERT INTO research_export.exported_record VALUES (?, ?)").params(exportId, id).update();
		}
		for (String id : previouslyExportedAndUnannounced(ineligibleIds)) {
			this.jdbc.sql("INSERT INTO research_export.announced_withdrawal (record_id, export_id) VALUES (?, ?)")
				.params(id, exportId).update();
		}

		// Write the records first and the manifest last, each atomically: a reader that sees a
		// manifest can rely on its records file being complete.
		Files.createDirectories(this.properties.dir());
		Path recordsFile = this.properties.dir().resolve(exportId + ".jsonl");
		Path manifestFile = this.properties.dir().resolve(exportId + ".manifest.json");
		atomicWrite(recordsFile, body);
		atomicWrite(manifestFile, this.json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifestNode));

		this.audit.record(new AuditEvent("RESEARCH_EXPORT_CREATED", AuditOutcome.SUCCESS, requestedBy, Set.of(), "research_export",
				exportId, null, UUID.randomUUID()));
		return new Result(exportId, manifestFile, recordsFile, lines.size(), withdrawn.size());
	}

	/** Re-keys every ID, truncates timestamps to the date, and drops free text. */
	static ObjectNode transform(ObjectNode record, Rekeyer rekeyer) {
		for (String field : List.of("record_id", "session_id", "recording_id", "processing_job_id")) {
			record.put(field, rekeyer.rekey(record.get(field).asString()));
		}
		ObjectNode consent = (ObjectNode) record.get("consent_reference");
		consent.put("consent_event_id", rekeyer.rekey(consent.get("consent_event_id").asString()));
		consent.put("verified_at_utc", day(consent.get("verified_at_utc").asString()));
		ObjectNode processing = (ObjectNode) record.get("processing");
		processing.put("processed_at_utc", day(processing.get("processed_at_utc").asString()));
		ObjectNode provenance = (ObjectNode) record.get("provenance");
		provenance.put("recorded_at_utc", day(provenance.get("recorded_at_utc").asString()));
		ObjectNode quality = (ObjectNode) record.get("quality");
		if ("DEGRADED".equals(quality.path("status").asString())) {
			quality.put("degraded_reason", "See quality.flags"); // fixed text replaces any free text
		}
		else {
			quality.putNull("degraded_reason");
		}
		return record;
	}

	private static String day(String timestamp) {
		return Instant.parse(timestamp).truncatedTo(ChronoUnit.DAYS).toString();
	}

	private List<String> previouslyExportedAndUnannounced(List<String> ineligible) {
		if (ineligible.isEmpty()) {
			return List.of();
		}
		return this.jdbc.sql("""
				SELECT DISTINCT e.record_id FROM research_export.exported_record e
				WHERE e.record_id = ANY(?) AND NOT EXISTS
				  (SELECT 1 FROM research_export.announced_withdrawal a WHERE a.record_id = e.record_id)
				ORDER BY e.record_id
				""").param(ineligible.toArray(String[]::new)).query(String.class).list();
	}

	private static void atomicWrite(Path target, byte[] content) throws IOException {
		Path tmp = Files.createTempFile(target.getParent(), ".partial-", ".tmp");
		Files.write(tmp, content);
		Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	private static String sha256(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String hex(int bytes) {
		byte[] b = new byte[bytes];
		RANDOM.nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

}
