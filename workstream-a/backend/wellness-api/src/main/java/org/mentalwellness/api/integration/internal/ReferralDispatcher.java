package org.mentalwellness.api.integration.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.mentalwellness.api.audit.AuditEvent;
import org.mentalwellness.api.audit.AuditLog;
import org.mentalwellness.api.audit.AuditOutcome;
import org.mentalwellness.api.consent.ConsentGate;
import org.mentalwellness.api.consent.ConsentScope;
import org.mentalwellness.api.shared.web.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers the outbox (ADR-015). Off unless {@code wellness.integration.dispatch.enabled=true}
 * (kill switch). Messages are claimed with a lease in one transaction, sent outside any
 * transaction, and the outcome recorded in another; retries back off and stop after
 * {@link #MAX_ATTEMPTS}.
 *
 * <ul>
 * <li>A referral is sent only to an enabled partner, and only if EXTERNAL_REFERRAL_SHARING is still in
 * effect at send time; otherwise it is cancelled (closes the race with a concurrent withdrawal).</li>
 * <li>A revocation is sent to any configured partner, even one disabled since it received the referral.
 * A revocation that can't be delivered is logged as an error and audited for operator follow-up.</li>
 * </ul>
 */
@Component
@ConditionalOnBooleanProperty("wellness.integration.dispatch.enabled")
class ReferralDispatcher {

	private static final Logger log = LoggerFactory.getLogger(ReferralDispatcher.class);

	static final int MAX_ATTEMPTS = 5;

	private static final Duration LEASE = Duration.ofSeconds(60);

	private static final String ACTOR = "integration-dispatcher";

	private final ReferralRepository repository;

	private final PartnerRegistry partners;

	private final PartnerClient client;

	private final AuditLog audit;

	private final TransactionTemplate tx;

	private final Clock clock;

	private final ConsentGate consent;

	ReferralDispatcher(ReferralRepository repository, PartnerRegistry partners, PartnerClient client, AuditLog audit,
			TransactionTemplate tx, Clock clock, ConsentGate consent) {
		this.repository = repository;
		this.partners = partners;
		this.client = client;
		this.audit = audit;
		this.tx = tx;
		this.clock = clock;
		this.consent = consent;
	}

	@Scheduled(fixedDelayString = "${wellness.integration.dispatch.interval:PT5S}")
	void tick() {
		dispatchDue();
	}

	/** One pass over due messages; returns how many were attempted. */
	int dispatchDue() {
		Set<String> enabled = Set.copyOf(this.partners.referralPartners().stream().map(PartnerProperties.Partner::id).toList());
		List<ReferralRepository.MessageRow> claimed = this.tx.execute(status -> {
			List<ReferralRepository.MessageRow> due = new java.util.ArrayList<>();
			for (ReferralRepository.MessageRow m : this.repository.claimDue(20)) {
				boolean revocation = "REVOCATION".equals(m.kind());
				if (revocation ? !this.partners.find(m.partnerId()).map(ReferralDispatcher::sendable).orElse(false)
						: !enabled.contains(m.partnerId())) {
					if (revocation) {
						log.error("Revocation {} can't be sent: partner {} has no endpoint or secret configured", m.messageId(),
								m.partnerId());
					}
					continue;
				}
				if (!revocation && !sharingStillConsented(m.consentEventId())) {
					this.repository.cancel(m.messageId(), "CONSENT_WITHDRAWN");
					this.repository.lock(m.referralId())
						.filter(r -> "QUEUED".equals(r.status()))
						.ifPresent(r -> this.repository.setStatus(r.referralId(), "REVOKED", this.clock.instant()));
					audit("REFERRAL_CANCELLED_CONSENT", AuditOutcome.SUCCESS, m.referralId());
					continue;
				}
				this.repository.lease(m.messageId(), this.clock.instant().plus(LEASE));
				due.add(m);
			}
			return due;
		});
		for (ReferralRepository.MessageRow m : claimed) {
			PartnerProperties.Partner partner = this.partners.find(m.partnerId()).orElseThrow();
			PartnerClient.Result result = this.client.send(partner, m.messageId(), m.payload());
			this.tx.executeWithoutResult(status -> record(m, result));
		}
		return claimed.size();
	}

	private void record(ReferralRepository.MessageRow m, PartnerClient.Result result) {
		int attempt = m.attempts() + 1;
		switch (result.outcome()) {
			case DELIVERED -> {
				if (!this.repository.markDelivered(m.messageId())) {
					return; // no longer ours (cancelled or re-leased); its own outcome is recorded elsewhere
				}
				if ("REFERRAL".equals(m.kind())) {
					this.repository.lock(m.referralId())
						.filter(r -> "QUEUED".equals(r.status()))
						.ifPresent(r -> this.repository.setStatus(r.referralId(), "SENT", this.clock.instant()));
					if (result.partnerReference() != null) {
						this.repository.setPartnerReference(m.referralId(), result.partnerReference());
					}
				}
				audit(m.kind().equals("REFERRAL") ? "REFERRAL_DELIVERED" : "REFERRAL_REVOCATION_DELIVERED",
						AuditOutcome.SUCCESS, m.referralId());
			}
			case RETRY -> {
				if (attempt >= MAX_ATTEMPTS) {
					fail(m, result.error());
				}
				else {
					Duration backoff = Duration.ofSeconds(10L << (attempt - 1)); // 10 s, 20 s, 40 s, 80 s
					this.repository.retryLater(m.messageId(), this.clock.instant().plus(backoff), result.error());
				}
			}
			case REJECTED -> fail(m, result.error());
		}
	}

	private void fail(ReferralRepository.MessageRow m, String error) {
		if (this.repository.markFailed(m.messageId(), error) && "REFERRAL".equals(m.kind())) {
			this.repository.lock(m.referralId())
				.filter(r -> "QUEUED".equals(r.status()))
				.ifPresent(r -> this.repository.setStatus(r.referralId(), "DELIVERY_FAILED", this.clock.instant()));
		}
		if ("REVOCATION".equals(m.kind())) {
			// The partner may still hold a referral the person withdrew: someone must follow up by hand.
			log.error("Revocation {} for partner {} failed ({}); manual follow-up with the partner is required",
					m.messageId(), m.partnerId(), error);
			audit("REFERRAL_REVOCATION_FAILED", AuditOutcome.FAILED, m.referralId());
		}
		else {
			audit("REFERRAL_DELIVERY_FAILED", AuditOutcome.FAILED, m.referralId());
		}
	}

	private boolean sharingStillConsented(String consentEventId) {
		try {
			this.consent.requireStillActive(consentEventId, Set.of(ConsentScope.EXTERNAL_REFERRAL_SHARING));
			return true;
		}
		catch (DomainException ex) {
			return false;
		}
	}

	private static boolean sendable(PartnerProperties.Partner p) {
		return p.endpoint() != null && p.signingSecret() != null && p.signingSecret().length() >= 32;
	}

	private void audit(String action, AuditOutcome outcome, String referralId) {
		this.audit.record(new AuditEvent(action, outcome, ACTOR, Set.of(), "referral", referralId, null, UUID.randomUUID()));
	}

}
