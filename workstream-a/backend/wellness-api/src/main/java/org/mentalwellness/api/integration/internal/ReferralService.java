package org.mentalwellness.api.integration.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.mentalwellness.api.audit.AuditEvent;
import org.mentalwellness.api.audit.AuditLog;
import org.mentalwellness.api.audit.AuditOutcome;
import org.mentalwellness.api.cases.ReferralBasis;
import org.mentalwellness.api.cases.ReviewedCases;
import org.mentalwellness.api.consent.ConsentGate;
import org.mentalwellness.api.consent.ConsentScope;
import org.mentalwellness.api.consent.ParticipantAccess;
import org.mentalwellness.api.shared.idempotency.IdempotencyService;
import org.mentalwellness.api.shared.security.Actor;
import org.mentalwellness.api.shared.web.CorrelationId;
import org.mentalwellness.api.shared.web.DomainException;
import org.mentalwellness.api.shared.web.ProblemCode;
import org.mentalwellness.api.shared.web.ProblemKind;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Partner referrals (ADR-015). A clinician sends one explicitly; nothing is sent automatically.
 * Each referral needs a REFER or URGENT_REFER review as the case's latest decision, the
 * participant's EXTERNAL_REFERRAL_SHARING consent, and an enabled partner. Withdrawal of that
 * consent revokes the referral, including at partners that already received it
 * ({@link ReferralRevocation}).
 */
@Service
class ReferralService {

	private static final Duration FUTURE_SKEW = Duration.ofMinutes(5);

	/** No look-alike characters (0/O, 1/I), so the code can be read out over a phone line. */
	private static final char[] HANDOFF_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

	static final Set<String> TERMINAL = Set.of("CLOSED", "DECLINED", "DELIVERY_FAILED", "REVOKED");

	private final ReferralRepository repository;

	private final ReviewedCases cases;

	private final PartnerRegistry partners;

	private final ReferralOutbox outbox;

	private final ConsentGate consent;

	private final ParticipantAccess participants;

	private final IdempotencyService idempotency;

	private final AuditLog audit;

	private final ObjectMapper canonical;

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	ReferralService(ReferralRepository repository, ReviewedCases cases, PartnerRegistry partners, ReferralOutbox outbox,
			ConsentGate consent, ParticipantAccess participants, IdempotencyService idempotency, AuditLog audit,
			JsonMapper json, Clock clock) {
		this.repository = repository;
		this.cases = cases;
		this.partners = partners;
		this.outbox = outbox;
		this.consent = consent;
		this.participants = participants;
		this.idempotency = idempotency;
		this.audit = audit;
		this.canonical = json.rebuild().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();
		this.clock = clock;
	}

	// --- clinician: send a referral ----------------------------------------------------

	@Transactional
	public IdempotencyService.Result<IntegrationApi.Referral> create(Actor actor, UUID key, String caseId,
			IntegrationApi.ReferralCreate request) {
		ReferralBasis basis = this.cases.find(caseId)
			.filter(ReferralBasis::eligibleForReview)
			.orElseThrow(() -> new DomainException(ProblemCode.NOT_FOUND));
		PartnerProperties.Partner partner = this.partners.find(request.partnerId())
			.orElseThrow(() -> DomainException.invalid("/partner_id", "unknownPartner"));
		if (!partner.enabled() || !partner.referrals()) {
			throw new DomainException(ProblemCode.PARTNER_NOT_ENABLED);
		}
		record Hashed(String caseId, IntegrationApi.ReferralCreate body) {
		}
		return this.idempotency.execute(actor.subject(), "createReferral", key, new Hashed(caseId, request),
				IntegrationApi.Referral.class, () -> {
					if (!basis.referable() || !request.reviewId().equals(basis.latestReviewId())) {
						// Only the latest decision counts, and it must be a referral.
						throw new DomainException(ProblemCode.RESOURCE_CONFLICT);
					}
					try {
						this.consent.require(basis.consentEventId(), basis.participantRef(),
								Set.of(ConsentScope.EXTERNAL_REFERRAL_SHARING), null);
					}
					catch (DomainException ex) {
						throw rejected(actor, request.referralId(), ex.code());
					}
					if (this.repository.referralExists(request.referralId())
							|| this.repository.liveReferralExists(caseId, partner.id())) {
						throw new DomainException(ProblemCode.RESOURCE_CONFLICT);
					}
					ReferralRepository.ReferralRow row = new ReferralRepository.ReferralRow(request.referralId(), caseId,
							basis.participantRef(), basis.consentEventId(), partner.id(), basis.latestReviewId(),
							basis.latestDecision(), basis.latestFollowUpWithin(), newHandoffCode(), "QUEUED", null, null, null);
					this.repository.insertReferral(row, basis.dataOrigin(), actor.subject());
					ReferralRepository.ReferralRow stored = this.repository.find(request.referralId()).orElseThrow();
					this.outbox.enqueue(stored, basis, "REFERRAL", false);
					this.audit.record(event(actor, "REFERRAL_CREATED", AuditOutcome.SUCCESS, "referral", request.referralId()));
					return view(stored);
				});
	}

	@Transactional(readOnly = true)
	public IntegrationApi.PartnerList referralPartners() {
		return new IntegrationApi.PartnerList(this.partners.referralPartners()
			.stream()
			.map(p -> new IntegrationApi.Partner(p.id(), p.displayName(), p.kind().name(), p.mode().name()))
			.toList());
	}

	// --- workers and clinicians: referral status ---------------------------------------

	@Transactional // not read-only: reads are audited
	public IntegrationApi.ReferralList forCase(Actor actor, String caseId) {
		ReferralBasis basis = this.cases.find(caseId).orElseThrow(() -> new DomainException(ProblemCode.NOT_FOUND));
		boolean worker = actor.has(Actor.ASHA_WORKER) && this.participants.isAssignedTo(basis.participantRef(), actor.subject());
		boolean clinician = actor.has(Actor.CLINICIAN) && basis.eligibleForReview();
		if (!worker && !clinician) {
			throw new DomainException(ProblemCode.NOT_FOUND);
		}
		this.audit.record(event(actor, "REFERRAL_LIST_READ", AuditOutcome.SUCCESS, "case", caseId));
		return new IntegrationApi.ReferralList(this.repository.forCase(caseId).stream().map(this::view).toList());
	}

	// --- partners: status events ---------------------------------------------------------

	record EventResult(IntegrationApi.PartnerEventResult body, boolean replayed) {
	}

	@Transactional
	public EventResult receive(Actor partnerActor, String authorizedParty, String partnerId, IntegrationApi.PartnerEvent event) {
		PartnerProperties.Partner partner = this.partners.find(partnerId)
			.filter(PartnerProperties.Partner::enabled)
			.filter(p -> p.clientId().equals(authorizedParty))
			.orElseThrow(() -> new AccessDeniedException("token does not belong to this partner"));
		if (event.occurredAtUtc().isAfter(this.clock.instant().plus(FUTURE_SKEW))) {
			throw DomainException.invalid("/occurred_at_utc", "notInFuture");
		}
		String hash = hash(event);
		var existing = this.repository.findEvent(partner.id(), event.eventId());
		if (existing.isPresent()) {
			if (!existing.get().requestHash().equals(hash)) {
				throw new DomainException(ProblemCode.IDEMPOTENCY_CONFLICT);
			}
			ReferralRepository.ReferralRow r = this.repository.find(existing.get().referralId()).orElseThrow();
			return new EventResult(new IntegrationApi.PartnerEventResult(r.referralId(), r.status(), false), true);
		}
		ReferralRepository.ReferralRow referral = this.repository.lock(event.referralId())
			.filter(r -> r.partnerId().equals(partner.id())) // a partner only ever sees its own referrals
			.orElseThrow(() -> new DomainException(ProblemCode.NOT_FOUND));
		if ("REVOKED".equals(referral.status())) {
			throw new DomainException(ProblemCode.RESOURCE_CONFLICT);
		}
		boolean newer = this.repository.latestEventAt(referral.referralId())
			.map(latest -> !event.occurredAtUtc().isBefore(latest))
			.orElse(true);
		boolean applied = newer && !TERMINAL.contains(referral.status());
		this.repository.insertEvent(partner.id(), event.eventId(), referral.referralId(), event.status().name(),
				event.occurredAtUtc(), hash, applied);
		if (applied) {
			this.repository.setStatus(referral.referralId(), event.status().name(), event.occurredAtUtc());
		}
		if (event.partnerReference() != null) {
			this.repository.setPartnerReference(referral.referralId(), event.partnerReference());
		}
		this.audit.record(event(partnerActor, "REFERRAL_STATUS_RECEIVED", AuditOutcome.SUCCESS, "referral",
				referral.referralId()));
		String status = applied ? event.status().name() : referral.status();
		return new EventResult(new IntegrationApi.PartnerEventResult(referral.referralId(), status, applied), false);
	}

	// --- helpers -----------------------------------------------------------------------

	private String newHandoffCode() {
		for (int attempt = 0; attempt < 10; attempt++) {
			StringBuilder code = new StringBuilder(8);
			for (byte b : randomBytes(8)) {
				code.append(HANDOFF_ALPHABET[(b & 0xff) % HANDOFF_ALPHABET.length]);
			}
			if (!this.repository.handoffCodeExists(code.toString())) {
				return code.toString();
			}
		}
		throw new IllegalStateException("could not allocate a unique handoff code");
	}

	private byte[] randomBytes(int n) {
		byte[] bytes = new byte[n];
		this.random.nextBytes(bytes);
		return bytes;
	}

	private IntegrationApi.Referral view(ReferralRepository.ReferralRow r) {
		PartnerProperties.Partner p = this.partners.find(r.partnerId()).orElse(null);
		return new IntegrationApi.Referral(r.referralId(), r.caseId(), r.partnerId(), p == null ? r.partnerId() : p.displayName(),
				p == null ? "UNKNOWN" : p.mode().name(), r.decision(), r.followUpWithin(), r.handoffCode(), r.status(),
				r.statusAt(), r.createdAt());
	}

	private String hash(IntegrationApi.PartnerEvent event) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(this.canonical.writeValueAsString(event).getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private DomainException rejected(Actor actor, String referralId, ProblemKind code) {
		this.audit.recordIndependently(event(actor, "REFERRAL_REJECTED", AuditOutcome.REJECTED, "referral", referralId));
		return new DomainException(code);
	}

	private AuditEvent event(Actor actor, String action, AuditOutcome outcome, String type, String id) {
		return new AuditEvent(action, outcome, actor.subject(), actor.roles(), type, id, null,
				CorrelationId.current().orElseGet(UUID::randomUUID));
	}

}
