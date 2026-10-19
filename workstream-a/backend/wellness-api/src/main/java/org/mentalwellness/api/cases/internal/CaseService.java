package org.mentalwellness.api.cases.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.mentalwellness.api.audit.AccessReason;
import org.mentalwellness.api.audit.AuditEvent;
import org.mentalwellness.api.audit.AuditLog;
import org.mentalwellness.api.audit.AuditOutcome;
import org.mentalwellness.api.consent.ConsentGate;
import org.mentalwellness.api.consent.ConsentScope;
import org.mentalwellness.api.consent.ParticipantAccess;
import org.mentalwellness.api.shared.idempotency.IdempotencyService;
import org.mentalwellness.api.shared.security.Actor;
import org.mentalwellness.api.shared.security.Pseudonyms;
import org.mentalwellness.api.shared.web.CorrelationId;
import org.mentalwellness.api.shared.web.DomainException;
import org.mentalwellness.api.shared.web.ProblemCode;
import org.mentalwellness.api.shared.web.ProblemKind;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ASHA assessment cases (API 1.1, ADR-013). Every write re-checks SCREENING_PARTICIPATION on the
 * case's own consent event with {@link ConsentGate}, so a withdrawal stops further submissions.
 * Answers are stored as coded values; nothing is scored.
 */
@Service
class CaseService {

	static final Duration FUTURE_SKEW = Duration.ofMinutes(5);

	private static final Set<ConsentScope> REQUIRED = Set.of(ConsentScope.SCREENING_PARTICIPATION);

	private final CaseRepository repository;

	private final ConsentGate consent;

	private final ParticipantAccess participants;

	private final IdempotencyService idempotency;

	private final AuditLog audit;

	private final Pseudonyms pseudonyms;

	private final Set<String> allowedOrigins;

	private final Clock clock;

	CaseService(CaseRepository repository, ConsentGate consent, ParticipantAccess participants,
			IdempotencyService idempotency, AuditLog audit, Pseudonyms pseudonyms,
			@Value("${wellness.data.allowed-origins}") Set<String> allowedOrigins, Clock clock) {
		this.repository = repository;
		this.consent = consent;
		this.participants = participants;
		this.idempotency = idempotency;
		this.audit = audit;
		this.pseudonyms = pseudonyms;
		this.allowedOrigins = Set.copyOf(allowedOrigins);
		this.clock = clock;
	}

	// --- cases ---------------------------------------------------------------------

	@Transactional
	public IdempotencyService.Result<CaseApi.Case> createCase(Actor actor, UUID key, CaseApi.CaseCreate request) {
		notInFuture(request.openedAtUtc(), "/opened_at_utc");
		return this.idempotency.execute(actor.subject(), "createCase", key, request, CaseApi.Case.class,
				() -> doCreateCase(actor, request));
	}

	private CaseApi.Case doCreateCase(Actor actor, CaseApi.CaseCreate request) {
		// The only instrument is illustrative and unvalidated: synthetic data only (ADR-013).
		if (request.dataOrigin() != CaseApi.DataOrigin.SYNTHETIC || !this.allowedOrigins.contains(request.dataOrigin().name())) {
			throw rejected(actor, request.caseId(), ProblemCode.DATA_ORIGIN_NOT_PERMITTED);
		}
		if (request.ageBand() == CaseApi.AgeBand.AGE_UNDER_18) {
			throw rejected(actor, request.caseId(), ProblemCode.ADULT_CONFIRMATION_REQUIRED);
		}
		if (!this.participants.isAssignedTo(request.participantRef(), actor.subject())) {
			throw new AccessDeniedException("participant not assigned to caller");
		}
		requireConsent(actor, request.caseId(), request.consentEventId(), request.participantRef(), request.openedAtUtc());
		if (this.repository.caseExists(request.caseId())) {
			throw new DomainException(ProblemCode.RESOURCE_CONFLICT);
		}
		CaseApi.Gender gender = request.gender() != null ? request.gender() : CaseApi.Gender.NOT_STATED;
		this.repository.insertCase(request, gender, actor.subject());
		this.audit.record(event(actor, "CASE_CREATED", AuditOutcome.SUCCESS, "case", request.caseId(), null));
		return view(this.repository.findCase(request.caseId()).orElseThrow());
	}

	@Transactional // not read-only: reads are audited
	public CaseApi.Case getCase(Actor actor, String caseId, AccessReason reason) {
		CaseRepository.CaseRow row = this.repository.findCase(caseId)
			.filter(c -> actor.has(Actor.ADMIN)
					|| (actor.has(Actor.ASHA_WORKER) && this.participants.isAssignedTo(c.participantRef(), actor.subject())))
			.orElseThrow(() -> new DomainException(ProblemCode.NOT_FOUND));
		this.audit.record(event(actor, "CASE_READ", AuditOutcome.SUCCESS, "case", caseId, reason));
		return view(row);
	}

	@Transactional // not read-only: reads are audited
	public CaseApi.CaseList listMyCases(Actor actor, boolean followUpOnly, int limit) {
		List<CaseApi.Case> cases = this.repository.casesOpenedBy(actor.subject(), followUpOnly, limit)
			.stream()
			.map(this::view)
			.toList();
		this.audit.record(event(actor, "CASE_LIST_READ", AuditOutcome.SUCCESS, "case", null, null));
		return new CaseApi.CaseList(cases);
	}

	// --- submissions ---------------------------------------------------------------

	@Transactional
	public IdempotencyService.Result<CaseApi.Case> submitObservations(Actor actor, UUID key, String caseId,
			CaseApi.ObservationSetCreate request) {
		ownedCase(actor, caseId);
		CaseCatalogue.checkObservations(request);
		notInFuture(request.observedAtUtc(), "/observed_at_utc");
		record Hashed(String caseId, CaseApi.ObservationSetCreate body) {
		}
		return this.idempotency.execute(actor.subject(), "submitObservations", key, new Hashed(caseId, request),
				CaseApi.Case.class, () -> openForWriting(actor, caseId, request.observedAtUtc(), row -> {
					if (row.observationsSubmitted() || this.repository.observationSetExists(request.observationSetId())) {
						throw new DomainException(ProblemCode.RESOURCE_CONFLICT);
					}
					this.repository.insertObservations(caseId, request);
					this.audit.record(event(actor, "OBSERVATIONS_SUBMITTED", AuditOutcome.SUCCESS, "observation_set",
							request.observationSetId(), null));
				}));
	}

	@Transactional
	public IdempotencyService.Result<CaseApi.Case> submitQuestionnaireResponse(Actor actor, UUID key, String caseId,
			CaseApi.QuestionnaireResponseCreate request) {
		ownedCase(actor, caseId);
		CaseCatalogue.checkAnswers(request);
		notInFuture(request.answeredAtUtc(), "/answered_at_utc");
		record Hashed(String caseId, CaseApi.QuestionnaireResponseCreate body) {
		}
		return this.idempotency.execute(actor.subject(), "submitQuestionnaireResponse", key,
				new Hashed(caseId, request), CaseApi.Case.class,
				() -> openForWriting(actor, caseId, request.answeredAtUtc(), row -> {
					if (row.questionnaireSubmitted() || this.repository.responseExists(request.responseId())) {
						throw new DomainException(ProblemCode.RESOURCE_CONFLICT);
					}
					this.repository.insertResponse(caseId, request);
					this.audit.record(event(actor, "QUESTIONNAIRE_SUBMITTED", AuditOutcome.SUCCESS,
							"questionnaire_response", request.responseId(), null));
					if (CaseCatalogue.needsUrgentReview(request.items())) {
						this.repository.raiseUrgentReviewFlag(caseId);
						this.audit.record(event(actor, "URGENT_REVIEW_FLAG_RAISED", AuditOutcome.SUCCESS, "case", caseId, null));
					}
				}));
	}

	@Transactional
	public IdempotencyService.Result<CaseApi.Case> completeCase(Actor actor, UUID key, String caseId,
			CaseApi.CaseCompletion request) {
		CaseRepository.CaseRow owned = ownedCase(actor, caseId);
		notInFuture(request.completedAtUtc(), "/completed_at_utc");
		if (request.completedAtUtc().isBefore(owned.openedAt())) {
			throw DomainException.invalid("/completed_at_utc", "notBeforeOpened");
		}
		record Hashed(String caseId, CaseApi.CaseCompletion body) {
		}
		return this.idempotency.execute(actor.subject(), "completeCase", key, new Hashed(caseId, request),
				CaseApi.Case.class, () -> openForWriting(actor, caseId, request.completedAtUtc(), row -> {
					if (!row.questionnaireSubmitted()) {
						// The safety question must have been asked before a visit can be closed.
						throw new DomainException(ProblemCode.RESOURCE_CONFLICT);
					}
					this.repository.complete(caseId, request.completedAtUtc(), request.followUpRequested());
					this.audit.record(event(actor, "CASE_COMPLETED", AuditOutcome.SUCCESS, "case", caseId, null));
				}));
	}

	// --- helpers -------------------------------------------------------------------

	interface Write {

		void apply(CaseRepository.CaseRow row);

	}

	/** Locks the case, refuses completed cases and inactive consent, applies the write, returns the new view. */
	private CaseApi.Case openForWriting(Actor actor, String caseId, Instant capturedAt, Write write) {
		this.repository.lockCase(caseId);
		CaseRepository.CaseRow row = this.repository.findCase(caseId).orElseThrow();
		if (row.completed()) {
			throw rejected(actor, caseId, ProblemCode.RESOURCE_CONFLICT);
		}
		requireConsent(actor, caseId, row.consentEventId(), row.participantRef(), capturedAt);
		write.apply(row);
		return view(this.repository.findCase(caseId).orElseThrow());
	}

	/** The case, if the caller is its participant's assigned worker. Others get 404. */
	private CaseRepository.CaseRow ownedCase(Actor actor, String caseId) {
		return this.repository.findCase(caseId)
			.filter(c -> this.participants.isAssignedTo(c.participantRef(), actor.subject()))
			.orElseThrow(() -> new DomainException(ProblemCode.NOT_FOUND));
	}

	private void requireConsent(Actor actor, String caseId, String consentEventId, String participantRef,
			Instant capturedAt) {
		try {
			this.consent.require(consentEventId, participantRef, REQUIRED, capturedAt);
		}
		catch (DomainException ex) {
			throw rejected(actor, caseId, ex.code());
		}
	}

	private void notInFuture(Instant at, String pointer) {
		if (at.isAfter(this.clock.instant().plus(FUTURE_SKEW))) {
			throw DomainException.invalid(pointer, "notInFuture");
		}
	}

	/** The pseudonymous case view, shared with {@link ReviewService}. */
	CaseApi.Case view(CaseRepository.CaseRow r) {
		return new CaseApi.Case(r.caseId(), r.participantRef(), r.consentEventId(), r.languageTag(),
				CaseApi.DataOrigin.valueOf(r.dataOrigin()), CaseApi.AgeBand.valueOf(r.ageBand()),
				CaseApi.Gender.valueOf(r.gender()), r.completed() ? CaseApi.Status.COMPLETED : CaseApi.Status.OPEN,
				r.urgentReviewFlag(), r.followUpRequested(), r.observationsSubmitted(), r.questionnaireSubmitted(),
				this.pseudonyms.workerRef(r.openedBySubject()), r.openedAt(), r.completedAt(), r.createdAt(),
				reviewState(r));
	}

	private static CaseApi.ReviewState reviewState(CaseRepository.CaseRow r) {
		if (r.reviewDecision() != null) {
			return new CaseApi.ReviewState(CaseApi.ReviewStatus.REVIEWED, CaseApi.ReviewDecision.valueOf(r.reviewDecision()),
					CaseApi.FollowUpWithin.valueOf(r.reviewFollowUpWithin()), r.reviewedAt());
		}
		return new CaseApi.ReviewState(r.needsReview() ? CaseApi.ReviewStatus.AWAITING_REVIEW : CaseApi.ReviewStatus.NOT_REQUIRED,
				null, null, null);
	}

	private DomainException rejected(Actor actor, String caseId, ProblemKind code) {
		this.audit.recordIndependently(event(actor, "CASE_REJECTED", AuditOutcome.REJECTED, "case", caseId, null));
		return new DomainException(code);
	}

	private AuditEvent event(Actor actor, String action, AuditOutcome outcome, String type, String id, AccessReason reason) {
		return new AuditEvent(action, outcome, actor.subject(), actor.roles(), type, id, reason,
				CorrelationId.current().orElseGet(UUID::randomUUID));
	}

}
