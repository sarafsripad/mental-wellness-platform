package org.mentalwellness.api.integration.internal;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import org.mentalwellness.api.cases.ReferralBasis;
import org.mentalwellness.api.cases.ReviewedCases;
import org.mentalwellness.api.consent.ConsentScope;
import org.mentalwellness.api.consent.WithdrawalAction;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Withdrawal of EXTERNAL_REFERRAL_SHARING (or of everything) revokes the participant's referrals:
 * messages that haven't left are cancelled, and partners that already received a referral are sent
 * a revocation so they can delete it (consent-and-data-lifecycle.md section 4, ADR-015).
 */
@Component
class ReferralRevocation implements WithdrawalAction {

	private final ReferralRepository repository;

	/** Looked up lazily: cases depend on consent, and consent calls this action (a construction cycle otherwise). */
	private final ObjectProvider<ReviewedCases> cases;

	private final ReferralOutbox outbox;

	private final Clock clock;

	ReferralRevocation(ReferralRepository repository, ObjectProvider<ReviewedCases> cases, ReferralOutbox outbox, Clock clock) {
		this.repository = repository;
		this.cases = cases;
		this.outbox = outbox;
		this.clock = clock;
	}

	@Override
	public Type type() {
		return Type.REVOKE_EXTERNAL_REFERRAL;
	}

	@Override
	public Status apply(String consentEventId, String participantRef, Set<ConsentScope> withdrawnScopes) {
		List<ReferralRepository.ReferralRow> live = this.repository.liveForConsentEvent(consentEventId);
		if (live.isEmpty()) {
			return Status.NOT_APPLICABLE;
		}
		boolean revocationsQueued = false;
		for (ReferralRepository.ReferralRow r : live) {
			this.repository.cancelPending(r.referralId());
			// A send in flight may already have reached the partner: revoke it too (security review B1).
			if (this.repository.possiblyDelivered(r.referralId(), "REFERRAL")) {
				ReferralBasis basis = this.cases.getObject().find(r.caseId()).orElseThrow();
				this.outbox.enqueue(r, basis, "REVOCATION", true);
				revocationsQueued = true;
			}
			this.repository.setStatus(r.referralId(), "REVOKED", this.clock.instant());
		}
		return revocationsQueued ? Status.SCHEDULED : Status.COMPLETED;
	}

}
