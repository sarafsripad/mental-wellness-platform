package org.mentalwellness.api.integration.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configured partners, checked once at startup (CLAUDE.md rule 7, ADR-015).
 *
 * <ul>
 * <li>REAL partners are refused unless a signed agreement and a named approver are recorded, and the
 * environment is approved for participant data. No environment is approved today, so no REAL partner
 * can start.</li>
 * <li>An enabled SIMULATED partner must point at this machine (loopback), so a test configuration
 * can never send to a real system by mistake.</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(PartnerProperties.class)
class PartnerRegistry {

	private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

	private final Map<String, PartnerProperties.Partner> partners;

	private final boolean dispatchEnabled;

	PartnerRegistry(PartnerProperties properties, @Value("${wellness.data.allowed-origins}") Set<String> allowedOrigins) {
		for (PartnerProperties.Partner p : properties.partners()) {
			if (p.mode() == PartnerProperties.Mode.REAL) {
				if (blank(p.agreementRef()) || blank(p.approvedBy()) || !allowedOrigins.contains("PARTICIPANT")) {
					throw new IllegalStateException("Partner " + p.id() + " is REAL but has no recorded data-sharing agreement "
							+ "and approver, or this environment is not approved for participant data");
				}
			}
			if (p.enabled()) {
				if (p.endpoint() == null || blank(p.clientId()) || p.signingSecret() == null || p.signingSecret().length() < 32) {
					throw new IllegalStateException("Partner " + p.id() + " is enabled but lacks endpoint, client-id or a "
							+ "32+ character signing secret");
				}
				if (p.mode() == PartnerProperties.Mode.SIMULATED && !LOOPBACK.contains(p.endpoint().getHost())) {
					throw new IllegalStateException("SIMULATED partner " + p.id() + " must use a loopback endpoint");
				}
			}
		}
		this.partners = properties.partners()
			.stream()
			.collect(Collectors.toUnmodifiableMap(PartnerProperties.Partner::id, Function.identity()));
		this.dispatchEnabled = properties.dispatch().enabled();
	}

	Optional<PartnerProperties.Partner> find(String id) {
		return Optional.ofNullable(this.partners.get(id));
	}

	/** Enabled partners that accept referrals (not HMIS). */
	List<PartnerProperties.Partner> referralPartners() {
		return this.partners.values()
			.stream()
			.filter(p -> p.enabled() && p.referrals())
			.sorted((a, b) -> a.id().compareTo(b.id()))
			.toList();
	}

	boolean dispatchEnabled() {
		return this.dispatchEnabled;
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}

}
