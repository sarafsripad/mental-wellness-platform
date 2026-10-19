package org.mentalwellness.api.cases.internal;

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
import org.mentalwellness.api.shared.idempotency.IdempotencyService;
import org.mentalwellness.api.shared.security.Actor;
import org.mentalwellness.api.shared.security.Pseudonyms;
import org.mentalwellness.api.shared.web.CorrelationId;
import org.mentalwellness.api.shared.web.DomainException;
import org.mentalwellness.api.shared.web.ProblemCode;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clinician review of flagged cases (API 1.2, ADR-014). Pseudonymous: identity stays write-only,
 * so the ASHA worker contacts the person. Decisions are coded human judgements, recorded
 * append-only; nothing here suggests or scores a decision.
 */
@Service
class ReviewService {

	private static final Duration FUTURE_SKEW = Duration.ofMinutes(5);

	private final CaseRepository repository;

	private final CaseService cases;

	private final ConsentGate consent;

	private final IdempotencyService idempotency;

	private final AuditLog audit;

	private final Pseudonyms pseudonyms;

	private final Clock clock;

	ReviewService(CaseRepository repository, CaseService cases, ConsentGate consent, IdempotencyService idempotency,
			AuditLog audit, Pseudonyms pseudonyms, Clock clock) {
		this.repository = repository;
		this.cases = cases;
		this.consent = consent;
		this.idempotency = idempotency;
		this.audit = audit;
		this.pseudonyms = pseudonyms;
		this.clock = clock;
	}

	@Transactional // not read-only: reads are audited
	public CaseApi.ReviewQueue queue(Actor actor, CaseApi.QueueFilter filter, int limit) {
		List<CaseApi.QueueItem> items = this.repository.reviewQueue(filter, limit)
			.stream()
			.filter(this::consentActive)
			.map(row -> new CaseApi.QueueItem(priority(row), this.cases.view(row)))
			.toList();
		this.audit.record(event(actor, "REVIEW_QUEUE_READ", "case", null));
		return new CaseApi.ReviewQueue(items);
	}

	@Transactional // not read-only: reads are audited
	public CaseApi.ReviewCase reviewCase(Actor actor, String caseId) {
		CaseRepository.CaseRow row = eligible(caseId);
		this.audit.record(event(actor, "REVIEW_CASE_READ", "case", caseId));
		CaseApi.Questionnaire questionnaire = this.repository
			.questionnaire(caseId, CaseCatalogue.validated(CaseCatalogue.INSTRUMENT_ID, CaseCatalogue.INSTRUMENT_VERSION))
			.orElse(null);
		return new CaseApi.ReviewCase(priority(row), this.cases.view(row), this.repository.observations(caseId), questionnaire,
				this.repository.reviews(caseId).stream().map(this::view).toList());
	}

	@Transactional
	public IdempotencyService.Result<CaseApi.ClinicianReview> submit(Actor actor, UUID key, String caseId,
			CaseApi.ClinicianReviewCreate request) {
		CaseRepository.CaseRow row = eligible(caseId);
		if (!CaseCatalogue.consistent(request.decision(), request.followUpWithin())) {
			throw DomainException.invalid("/follow_up_within", "consistentWithDecision");
		}
		if (request.reviewedAtUtc().isAfter(this.clock.instant().plus(FUTURE_SKEW))) {
			throw DomainException.invalid("/reviewed_at_utc", "notInFuture");
		}
		if (request.reviewedAtUtc().isBefore(row.openedAt())) {
			throw DomainException.invalid("/reviewed_at_utc", "notBeforeOpened");
		}
		record Hashed(String caseId, CaseApi.ClinicianReviewCreate body) {
		}
		return this.idempotency.execute(actor.subject(), "submitClinicianReview", key, new Hashed(caseId, request),
				CaseApi.ClinicianReview.class, () -> {
					if (this.repository.reviewExists(request.reviewId())) {
						throw new DomainException(ProblemCode.RESOURCE_CONFLICT);
					}
					this.repository.insertReview(caseId, request, actor.subject());
					this.audit.record(event(actor, "CLINICIAN_REVIEW_RECORDED", "review", request.reviewId()));
					return view(this.repository.findReview(request.reviewId()).orElseThrow());
				});
	}

	/** Flagged, and the participant's screening consent still in effect. Anything else is 404. */
	private CaseRepository.CaseRow eligible(String caseId) {
		return this.repository.findCase(caseId)
			.filter(CaseRepository.CaseRow::needsReview)
			.filter(this::consentActive)
			.orElseThrow(() -> new DomainException(ProblemCode.NOT_FOUND));
	}

	private boolean consentActive(CaseRepository.CaseRow row) {
		try {
			this.consent.requireStillActive(row.consentEventId(), Set.of(ConsentScope.SCREENING_PARTICIPATION));
			return true;
		}
		catch (DomainException ex) {
			return false;
		}
	}

	private static CaseApi.Priority priority(CaseRepository.CaseRow row) {
		return row.urgentReviewFlag() ? CaseApi.Priority.URGENT : CaseApi.Priority.ROUTINE;
	}

	private CaseApi.ClinicianReview view(CaseRepository.ReviewRow r) {
		return new CaseApi.ClinicianReview(r.reviewId(), r.caseId(), CaseApi.ReviewDecision.valueOf(r.decision()),
				CaseApi.FollowUpWithin.valueOf(r.followUpWithin()), this.pseudonyms.clinicianRef(r.reviewerSubject()),
				r.reviewedAt(), r.createdAt());
	}

	private AuditEvent event(Actor actor, String action, String type, String id) {
		return new AuditEvent(action, AuditOutcome.SUCCESS, actor.subject(), actor.roles(), type, id, null,
				CorrelationId.current().orElseGet(UUID::randomUUID));
	}

}
