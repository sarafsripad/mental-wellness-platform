package org.mentalwellness.api.integration.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.mentalwellness.api.audit.AuditEvent;
import org.mentalwellness.api.audit.AuditLog;
import org.mentalwellness.api.audit.AuditOutcome;
import org.mentalwellness.api.cases.CaseStatistics;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the monthly HMIS aggregate report (contracts/integration/hmis-aggregate-report.schema.json):
 * counts only, with counts from 1 to 4 replaced by "&lt;5". Written to a dropzone and, when the
 * HMIS partner is enabled, posted to it signed. Real HMIS formats are unknown and not invented.
 */
@Component
class HmisReports {

	static final String RESOURCE = "/contracts/hmis-aggregate-report.schema.json";

	static final int THRESHOLD = 5;

	private final CaseStatistics statistics;

	private final ReferralRepository referrals;

	private final PartnerRegistry partners;

	private final PartnerClient client;

	private final AuditLog audit;

	private final JsonMapper json;

	private final Schema schema;

	private final Clock clock;

	/** {@code wellness.hmis.deliver}: post to the HMIS partner (off by default; the report is still written). */
	private final boolean deliver;

	private final SecureRandom random = new SecureRandom();

	HmisReports(CaseStatistics statistics, ReferralRepository referrals, PartnerRegistry partners, PartnerClient client,
			AuditLog audit, JsonMapper json, Clock clock, @Value("${wellness.hmis.deliver:false}") boolean deliver)
			throws IOException {
		this.statistics = statistics;
		this.referrals = referrals;
		this.partners = partners;
		this.client = client;
		this.audit = audit;
		this.json = json;
		this.clock = clock;
		this.deliver = deliver;
		try (InputStream in = HmisReports.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("HMIS report schema missing from the classpath: " + RESOURCE);
			}
			this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
		}
	}

	record Result(String reportId, Path file, String delivery) {
	}

	/**
	 * Not transactional: the queries run read-only, the HTTP call (if any) runs outside any
	 * transaction, and the audit event is written on its own.
	 */
	public Result run(YearMonth month, Path dir) throws IOException {
		byte[] id = new byte[16];
		this.random.nextBytes(id);
		String reportId = "hmis_" + HexFormat.of().formatHex(id);
		String report = build(reportId, month);
		Files.createDirectories(dir);
		Path file = dir.resolve(reportId + ".json");
		Files.writeString(file, report);
		String delivery = "NOT_CONFIGURED";
		if (this.deliver) {
			for (PartnerProperties.Partner p : this.partners.find("sim-state-hmis").filter(PartnerProperties.Partner::enabled).stream().toList()) {
				delivery = this.client.send(p, "/reports", "application/json", reportId, report).outcome().name();
			}
		}
		this.audit.recordIndependently(new AuditEvent("HMIS_REPORT_CREATED", AuditOutcome.SUCCESS, "hmis-report-runner",
				java.util.Set.of(), null, null, null, UUID.randomUUID()));
		return new Result(reportId, file, delivery);
	}

	String build(String reportId, YearMonth month) {
		ObjectNode report = this.json.createObjectNode();
		report.put("report_id", reportId);
		report.put("report_version", "1.0.0");
		report.putObject("period").put("month", month.toString());
		report.put("generated_at_utc", this.clock.instant().toString());
		report.put("data_origin", "SYNTHETIC"); // no environment is approved for participant data
		report.putObject("suppression").put("threshold", THRESHOLD).put("marker", "<5");
		ArrayNode groups = report.putArray("visits_by_group");
		List<CaseStatistics.Counts> counts = this.statistics.monthly(month, "SYNTHETIC");
		for (CaseStatistics.Counts c : counts) {
			ObjectNode g = groups.addObject();
			g.put("age_band", c.ageBand());
			g.put("gender", c.gender());
			count(g, "visits", c.visits());
			count(g, "urgent_review_flags", c.urgentFlags());
			count(g, "follow_up_requests", c.followUpRequests());
			count(g, "clinician_reviewed", c.reviewed());
		}
		ReferralRepository.ReferralCounts r = this.referrals.monthly(month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
				month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(), "SYNTHETIC");
		ObjectNode refs = report.putObject("referrals");
		count(refs, "created", r.created());
		count(refs, "delivered", r.delivered());
		count(refs, "contact_made", r.contactMade());
		count(refs, "revoked", r.revoked());
		if (!this.schema.validate(report).isEmpty()) {
			throw new IllegalStateException("HMIS report does not match its contract");
		}
		return this.json.writeValueAsString(report);
	}

	/** Small-cell suppression: 1 to 4 become "&lt;5"; zero and five or more are shown. */
	static void count(ObjectNode node, String field, long value) {
		if (value > 0 && value < THRESHOLD) {
			node.put(field, "<5");
		}
		else {
			node.put(field, value);
		}
	}

}
