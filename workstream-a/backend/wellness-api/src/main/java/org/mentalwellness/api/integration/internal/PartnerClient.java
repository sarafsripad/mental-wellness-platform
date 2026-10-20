package org.mentalwellness.api.integration.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Component;

/**
 * Sends one outbox message to a partner. The body is signed with the partner's secret
 * (HMAC-SHA256 over message id, timestamp and body) so the partner can verify origin and
 * integrity. A real partner's own requirements (mTLS, OAuth, a FHIR server) replace this once
 * its specification exists.
 */
@Component
class PartnerClient {

	enum Outcome { DELIVERED, RETRY, REJECTED }

	record Result(Outcome outcome, String error, String partnerReference) {
	}

	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(5))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	private final JsonMapper json;

	private final Clock clock;

	PartnerClient(JsonMapper json, Clock clock) {
		this.json = json;
		this.clock = clock;
	}

	/** A referral bundle to {@code <endpoint>/referrals}. */
	Result send(PartnerProperties.Partner partner, String messageId, String payload) {
		return send(partner, "/referrals", "application/fhir+json", messageId, payload);
	}

	Result send(PartnerProperties.Partner partner, String path, String contentType, String messageId, String payload) {
		String timestamp = Long.toString(this.clock.instant().getEpochSecond());
		HttpRequest request = HttpRequest.newBuilder(URI.create(partner.endpoint().toString().replaceAll("/+$", "") + path))
			.timeout(Duration.ofSeconds(10))
			.header("Content-Type", contentType)
			.header("X-Wellness-Message-Id", messageId)
			.header("X-Wellness-Timestamp", timestamp)
			.header("X-Wellness-Signature", "v1=" + sign(partner.signingSecret(), messageId + "." + timestamp + "." + payload))
			.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
			.build();
		HttpResponse<String> response;
		try {
			response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (HttpTimeoutException ex) {
			return new Result(Outcome.RETRY, "TIMEOUT", null);
		}
		catch (IOException ex) {
			return new Result(Outcome.RETRY, "NETWORK", null);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return new Result(Outcome.RETRY, "INTERRUPTED", null);
		}
		int status = response.statusCode();
		if (status >= 200 && status < 300) {
			return new Result(Outcome.DELIVERED, null, partnerReference(response.body()));
		}
		if (status == 408 || status == 429 || status >= 500) {
			return new Result(Outcome.RETRY, "HTTP_" + status, null);
		}
		return new Result(Outcome.REJECTED, "HTTP_" + status, null);
	}

	/** The partner's own reference, if it returned a well-formed one; anything else is ignored. */
	private String partnerReference(String body) {
		try {
			JsonNode node = this.json.readTree(body);
			JsonNode ref = node == null ? null : node.get("partner_reference");
			return ref != null && ref.isString() && ref.asString().matches(IntegrationApi.PARTNER_TEXT) ? ref.asString() : null;
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	static String sign(String secret, String data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
		}
	}

}
