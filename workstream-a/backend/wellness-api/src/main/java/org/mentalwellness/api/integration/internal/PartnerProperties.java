package org.mentalwellness.api.integration.internal;

import java.net.URI;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Partner programmes ({@code wellness.integration.partners}) and the outbox dispatcher switch.
 * Every partner is disabled unless configured otherwise; {@link PartnerRegistry} enforces the rules.
 */
@ConfigurationProperties("wellness.integration")
record PartnerProperties(List<Partner> partners, Dispatch dispatch) {

	PartnerProperties {
		partners = partners == null ? List.of() : List.copyOf(partners);
		dispatch = dispatch == null ? new Dispatch(false) : dispatch;
	}

	enum Kind { TELE_MANAS, ESANJEEVANI, ABDM, HMIS }

	enum Mode { SIMULATED, REAL }

	/** @param enabled the dispatcher sends queued messages only when true (kill switch, default off) */
	record Dispatch(boolean enabled) {
	}

	/**
	 * @param id            stable code, e.g. {@code sim-tele-manas}
	 * @param displayName   shown to clinicians
	 * @param kind          which programme
	 * @param mode          SIMULATED (local test partner) or REAL
	 * @param enabled       whether referrals may be created and sent; default false
	 * @param endpoint      base URL; referrals are POSTed to {@code <endpoint>/referrals}
	 * @param clientId      the partner's OAuth client, matched against the {@code azp} claim on inbound events
	 * @param signingSecret HMAC key for outbound message signatures (32+ characters)
	 * @param agreementRef  the signed data-sharing agreement (REAL only)
	 * @param approvedBy    named approver (REAL only)
	 */
	record Partner(String id, String displayName, Kind kind, Mode mode, boolean enabled, URI endpoint, String clientId,
			String signingSecret, String agreementRef, String approvedBy) {

		Partner {
			if (id == null || !id.matches("^[a-z0-9-]{3,40}$")) {
				throw new IllegalArgumentException("partner id must match ^[a-z0-9-]{3,40}$");
			}
			if (kind == null || mode == null) {
				throw new IllegalArgumentException("partner " + id + ": kind and mode are required");
			}
			if (displayName == null || displayName.isBlank()) {
				displayName = id;
			}
		}

		boolean referrals() {
			return this.kind != Kind.HMIS;
		}

	}

}
