package org.mentalwellness.api.integration.internal;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;

import org.mentalwellness.api.cases.ReferralBasis;
import org.mentalwellness.api.shared.security.Pseudonyms;

import org.springframework.stereotype.Component;

/** Queues a schema-checked bundle in the outbox, in the caller's transaction (ADR-015). */
@Component
class ReferralOutbox {

	private final ReferralRepository repository;

	private final ReferralBundles bundles;

	private final Pseudonyms pseudonyms;

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	ReferralOutbox(ReferralRepository repository, ReferralBundles bundles, Pseudonyms pseudonyms, Clock clock) {
		this.repository = repository;
		this.bundles = bundles;
		this.pseudonyms = pseudonyms;
		this.clock = clock;
	}

	void enqueue(ReferralRepository.ReferralRow r, ReferralBasis basis, String kind, boolean revoked) {
		byte[] id = new byte[16];
		this.random.nextBytes(id);
		String messageId = "msg_" + HexFormat.of().formatHex(id);
		String payload = this.bundles.bundle(new ReferralBundles.Input(messageId, r, basis,
				this.pseudonyms.partnerKey(r.partnerId(), r.participantRef()), revoked, this.clock.instant()));
		this.repository.enqueue(messageId, r.referralId(), r.partnerId(), kind, payload);
	}

}
