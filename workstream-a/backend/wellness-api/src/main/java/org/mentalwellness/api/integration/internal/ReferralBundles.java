package org.mentalwellness.api.integration.internal;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.mentalwellness.api.cases.ReferralBasis;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Component;

/**
 * Builds the FHIR R4 referral bundle (contracts/integration/referral-bundle.schema.json) and checks
 * every bundle against that schema before it can be queued. The bundle is pseudonymous by
 * construction: there is no input from which a name, place or answer could be copied.
 */
@Component
class ReferralBundles {

	static final String RESOURCE = "/contracts/referral-bundle.schema.json";

	private static final String BASE = "https://mental-wellness-platform/";

	private final Schema schema;

	private final JsonMapper json;

	ReferralBundles(JsonMapper json) throws IOException {
		this.json = json;
		try (InputStream in = ReferralBundles.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("Referral bundle schema missing from the classpath: " + RESOURCE);
			}
			this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
		}
	}

	record Input(String messageId, ReferralRepository.ReferralRow referral, ReferralBasis basis, String partnerKey,
			boolean revoked, Instant at) {
	}

	String bundle(Input in) {
		ObjectNode bundle = this.json.createObjectNode();
		bundle.put("resourceType", "Bundle");
		bundle.put("id", in.messageId());
		bundle.put("type", "collection");
		bundle.put("timestamp", in.at().toString());
		bundle.putObject("meta").putArray("tag").addObject()
			.put("system", BASE + "data-origin")
			.put("code", in.basis().dataOrigin());
		ArrayNode entries = bundle.putArray("entry");

		ObjectNode patient = entries.addObject().putObject("resource");
		patient.put("resourceType", "Patient");
		patient.put("id", in.partnerKey());
		patient.putArray("identifier").addObject()
			.put("system", BASE + "partner-key/" + in.referral().partnerId())
			.put("value", in.partnerKey());
		patient.putArray("communication").addObject().putObject("language").putArray("coding").addObject()
			.put("system", "urn:ietf:bcp:47")
			.put("code", in.basis().languageTag());

		ReferralRepository.ReferralRow r = in.referral();
		ObjectNode request = entries.addObject().putObject("resource");
		request.put("resourceType", "ServiceRequest");
		request.put("id", r.referralId());
		request.putArray("identifier").addObject().put("system", BASE + "handoff-code").put("value", r.handoffCode());
		request.put("status", in.revoked() ? "revoked" : "active");
		request.put("intent", "order");
		request.put("priority", "URGENT_REFER".equals(r.decision()) ? "urgent" : "routine");
		request.putObject("code").putArray("coding").addObject()
			.put("system", BASE + "codes/referral")
			.put("code", "MENTAL_HEALTH_FOLLOW_UP");
		ArrayNode reasons = request.putArray("reasonCode");
		if (in.basis().urgentReviewFlag()) {
			reason(reasons, "URGENT_REVIEW_FLAG");
		}
		if (in.basis().followUpRequested() || !in.basis().urgentReviewFlag()) {
			reason(reasons, "FOLLOW_UP_REQUESTED");
		}
		request.putObject("subject").put("reference", "Patient/" + in.partnerKey());
		request.put("authoredOn", r.createdAt().toString());
		request.putObject("occurrencePeriod")
			.put("start", r.createdAt().toString())
			.put("end", r.createdAt().plus(window(r.followUpWithin())).toString());

		List<com.networknt.schema.Error> errors = this.schema.validate(bundle);
		if (!errors.isEmpty()) {
			// A programming error, never a partner or user error: refuse rather than send something off-contract.
			throw new IllegalStateException("Referral bundle does not match its contract (" + errors.size() + " errors)");
		}
		return this.json.writeValueAsString(bundle);
	}

	private static void reason(ArrayNode reasons, String code) {
		reasons.addObject().putArray("coding").addObject().put("system", BASE + "codes/referral-reason").put("code", code);
	}

	static Duration window(String followUpWithin) {
		return switch (followUpWithin) {
			case "WITHIN_24_HOURS" -> Duration.ofHours(24);
			case "WITHIN_7_DAYS" -> Duration.ofDays(7);
			case "WITHIN_30_DAYS" -> Duration.ofDays(30);
			default -> throw new IllegalArgumentException("not a referral timeframe");
		};
	}

}
