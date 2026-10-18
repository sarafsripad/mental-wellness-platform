package org.mentalwellness.api.consent.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.mentalwellness.api.audit.AccessReason;
import org.mentalwellness.api.audit.AuditEvent;
import org.mentalwellness.api.audit.AuditLog;
import org.mentalwellness.api.audit.AuditOutcome;
import org.mentalwellness.api.consent.ConsentGate;
import org.mentalwellness.api.consent.ConsentScope;
import org.mentalwellness.api.consent.ConsentSnapshot;
import org.mentalwellness.api.consent.ParticipantAccess;
import org.mentalwellness.api.consent.WithdrawalAction;
import org.mentalwellness.api.consent.internal.ConsentApi.EventStatus;
import org.mentalwellness.api.consent.internal.ConsentApi.ParticipantStatus;
import org.mentalwellness.api.shared.idempotency.IdempotencyService;
import org.mentalwellness.api.shared.security.Actor;
import org.mentalwellness.api.shared.security.Pseudonyms;
import org.mentalwellness.api.shared.web.CorrelationId;
import org.mentalwellness.api.shared.web.DomainException;
import org.mentalwellness.api.shared.web.ProblemCode;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consent events, withdrawals and status (ADR-011), plus the {@link ConsentGate} other modules use.
 * Read access follows the v1 assignment rule; resources the caller can't see are reported as not
 * found, so the API doesn't reveal whether they exist.
 */
@Service
class ConsentService implements ConsentGate, ParticipantAccess {

	/** Allowed clock skew for device timestamps that lie in the future. */
	static final Duration FUTURE_SKEW = Duration.ofMinutes(5);

	private final ConsentRepository repository;

	private final IdempotencyService idempotency;

	private final AuditLog audit;

	private final Pseudonyms pseudonyms;

	private final Map<WithdrawalAction.Type, WithdrawalAction> actions;

	private final Clock clock;

	ConsentService(ConsentRepository repository, IdempotencyService idempotency, AuditLog audit, Pseudonyms pseudonyms,
			List<WithdrawalAction> actions, Clock clock) {
		this.repository = repository;
		this.idempotency = idempotency;
		this.audit = audit;
		this.pseudonyms = pseudonyms;
		this.actions = actions.stream().collect(Collectors.toMap(WithdrawalAction::type, Function.identity()));
		this.clock = clock;
	}

	// --- create --------------------------------------------------------------------

	@Transactional
	public IdempotencyService.Result<ConsentApi.ConsentEvent> create(Actor actor, UUID key,
			ConsentApi.ConsentEventCreate request) {
		return this.idempotency.execute(actor.subject(), "createConsentEvent", key, request, ConsentApi.ConsentEvent.class,
				() -> doCreate(actor, request));
	}

	private ConsentApi.ConsentEvent doCreate(Actor actor, ConsentApi.ConsentEventCreate request) {
		if (!Boolean.TRUE.equals(request.safeguards().adultConfirmed())) {
			throw reject(actor, request.consentEventId(), ProblemCode.ADULT_CONFIRMATION_REQUIRED);
		}
		if (!Boolean.TRUE.equals(request.capture().comprehensionConfirmed())) {
			throw DomainException.invalid("/capture/comprehension_confirmed", "const");
		}
		if (request.capture().capturedAtUtc().isAfter(now().plus(FUTURE_SKEW))) {
			throw DomainException.invalid("/capture/captured_at_utc", "notInFuture");
		}
		Set<ConsentScope> offered = this.repository
			.offeredScopes(request.consentForm().formId(), request.consentForm().formVersion(),
					request.consentForm().languageTag())
			.orElseThrow(() -> DomainException.invalid("/consent_form", "unknownFormVersion"));
		if (!ConsentRules.decisionsValid(offered, request.scopeDecisions())) {
			throw reject(actor, request.consentEventId(), ProblemCode.CONSENT_SCOPE_INCOMPLETE);
		}
		if (this.repository.eventExists(request.consentEventId())) {
			throw new DomainException(ProblemCode.RESOURCE_CONFLICT);
		}
		String assigned = this.repository.assignIfAbsent(request.participantRef(), actor.subject());
		if (!assigned.equals(actor.subject())) {
			throw new AccessDeniedException("participant assigned to another worker");
		}
		this.repository.insertEvent(request, actor.subject());
		this.audit.record(event(actor, "CONSENT_EVENT_CREATED", AuditOutcome.SUCCESS, request.consentEventId(), null));
		return view(request.consentEventId());
	}

	// --- read ----------------------------------------------------------------------

	@Transactional // not read-only: every read is audited
	public ConsentApi.ConsentEvent get(Actor actor, String consentEventId, AccessReason reason) {
		ConsentRepository.StoredEvent stored = this.repository.findEvent(consentEventId)
			.filter(e -> canRead(actor, e.participantRef()))
			.orElseThrow(() -> new DomainException(ProblemCode.NOT_FOUND));
		this.audit.record(event(actor, "CONSENT_EVENT_READ", AuditOutcome.SUCCESS, stored.consentEventId(), reason));
		return view(consentEventId);
	}

	@Transactional // not read-only: every read is audited
	public ConsentApi.ConsentStatus status(Actor actor, String participantRef, AccessReason reason) {
		if (!canRead(actor, participantRef)) {
			throw new DomainException(ProblemCode.NOT_FOUND);
		}
		this.audit.record(new AuditEvent("CONSENT_STATUS_READ", AuditOutcome.SUCCESS, actor.subject(), actor.roles(),
				"participant", participantRef, reason, correlationId()));
		return this.repository.latestEventFor(participantRef).map(id -> {
			ConsentApi.ConsentEvent e = view(id);
			return new ConsentApi.ConsentStatus(participantRef, ParticipantStatus.valueOf(e.status().name()), id,
					e.effectiveScopes(), now());
		}).orElseGet(() -> new ConsentApi.ConsentStatus(participantRef, ParticipantStatus.NO_CONSENT, null, List.of(), now()));
	}

	// --- withdraw ------------------------------------------------------------------

	public record WithdrawalOutcome(ConsentApi.ConsentWithdrawal body, boolean changed, boolean replayed) {
	}

	@Transactional
	public WithdrawalOutcome withdraw(Actor actor, UUID key, String consentEventId,
			ConsentApi.ConsentWithdrawalCreate request, AccessReason reason) {
		ConsentRepository.StoredEvent stored = this.repository.findEvent(consentEventId)
			.filter(e -> canRead(actor, e.participantRef()))
			.orElseThrow(() -> new DomainException(ProblemCode.NOT_FOUND));
		if (request.withdrawnAtUtc().isAfter(now().plus(FUTURE_SKEW))) {
			throw DomainException.invalid("/withdrawn_at_utc", "notInFuture");
		}
		record Hashed(String consentEventId, ConsentApi.ConsentWithdrawalCreate body) {
		}
		var result = this.idempotency.execute(actor.subject(), "withdrawConsent", key, new Hashed(consentEventId, request),
				ConsentApi.ConsentWithdrawal.class, () -> doWithdraw(actor, stored, request, reason));
		return new WithdrawalOutcome(result.body(), !result.body().withdrawnScopes().isEmpty(), result.replayed());
	}

	private ConsentApi.ConsentWithdrawal doWithdraw(Actor actor, ConsentRepository.StoredEvent stored,
			ConsentApi.ConsentWithdrawalCreate request, AccessReason reason) {
		if (this.repository.withdrawalExists(request.withdrawalId())) {
			throw new DomainException(ProblemCode.RESOURCE_CONFLICT);
		}
		this.repository.lockEvent(stored.consentEventId());
		Set<ConsentScope> granted = ConsentRules.granted(decisions(stored.consentEventId()));
		Set<ConsentScope> before = ConsentRules.effective(granted, this.repository.withdrawnScopes(stored.consentEventId()));
		Set<ConsentScope> withdrawn = ConsentRules.toWithdraw(request.scopes(), before);
		Instant recordedAt = this.repository.insertWithdrawal(stored.consentEventId(), request, withdrawn, actor.subject());

		List<ConsentApi.DownstreamAction> downstream = new ArrayList<>();
		for (WithdrawalAction.Type type : WithdrawalAction.requiredFor(withdrawn)) {
			WithdrawalAction action = this.actions.get(type);
			WithdrawalAction.Status status = action == null ? WithdrawalAction.Status.NOT_APPLICABLE
					: action.apply(stored.consentEventId(), stored.participantRef(), withdrawn);
			downstream.add(new ConsentApi.DownstreamAction(type.name(), status.name()));
			if (status == WithdrawalAction.Status.SCHEDULED) {
				this.audit.record(event(actor, "CONSENT_DOWNSTREAM_ACTION_SCHEDULED", AuditOutcome.SUCCESS,
						stored.consentEventId(), reason));
			}
		}
		if (!withdrawn.isEmpty()) {
			this.audit.record(event(actor, "CONSENT_WITHDRAWN", AuditOutcome.SUCCESS, stored.consentEventId(), reason));
		}
		Set<ConsentScope> allWithdrawn = this.repository.withdrawnScopes(stored.consentEventId());
		return new ConsentApi.ConsentWithdrawal(request.withdrawalId(), stored.consentEventId(), List.copyOf(withdrawn),
				ConsentRules.status(granted, allWithdrawn), downstream, recordedAt);
	}

	// --- ConsentGate and ParticipantAccess -----------------------------------------

	@Override
	@Transactional(readOnly = true)
	public ConsentSnapshot require(String consentEventId, String participantRef, Set<ConsentScope> required,
			Instant capturedAt) {
		ConsentRepository.StoredEvent stored = this.repository.findEvent(consentEventId)
			.filter(e -> e.participantRef().equals(participantRef))
			.orElseThrow(() -> new DomainException(ProblemCode.CONSENT_PARTICIPANT_MISMATCH));
		if (capturedAt != null && capturedAt.isBefore(stored.capturedAt())) {
			throw new DomainException(ProblemCode.CONSENT_NOT_ACTIVE);
		}
		return check(stored, required);
	}

	@Override
	@Transactional(readOnly = true)
	public ConsentSnapshot requireStillActive(String consentEventId, Set<ConsentScope> required) {
		ConsentRepository.StoredEvent stored = this.repository.findEvent(consentEventId)
			.orElseThrow(() -> new DomainException(ProblemCode.CONSENT_NOT_ACTIVE));
		return check(stored, required);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isAssignedTo(String participantRef, String workerSubject) {
		return this.repository.assignedWorker(participantRef).map(workerSubject::equals).orElse(false);
	}

	private ConsentSnapshot check(ConsentRepository.StoredEvent stored, Set<ConsentScope> required) {
		Set<ConsentScope> effective = ConsentRules.effective(ConsentRules.granted(decisions(stored.consentEventId())),
				this.repository.withdrawnScopes(stored.consentEventId()));
		if (!effective.containsAll(required)) {
			throw new DomainException(ProblemCode.CONSENT_NOT_ACTIVE);
		}
		return new ConsentSnapshot(stored.consentEventId(), stored.participantRef(), effective, now());
	}

	// --- helpers -------------------------------------------------------------------

	/** ASHA workers see their assigned participants; ADMIN sees all (reason-coded); clinicians: none yet. */
	private boolean canRead(Actor actor, String participantRef) {
		if (actor.has(Actor.ADMIN)) {
			return true;
		}
		return actor.has(Actor.ASHA_WORKER) && isAssignedTo(participantRef, actor.subject());
	}

	private ConsentApi.ConsentEvent view(String consentEventId) {
		ConsentRepository.StoredEvent e = this.repository.findEvent(consentEventId).orElseThrow();
		List<ConsentApi.ScopeDecision> decisions = decisions(consentEventId);
		Set<ConsentScope> granted = ConsentRules.granted(decisions);
		Set<ConsentScope> withdrawn = this.repository.withdrawnScopes(consentEventId);
		return new ConsentApi.ConsentEvent(e.consentEventId(), e.participantRef(),
				new ConsentApi.ConsentForm(e.formId(), e.formVersion(), e.languageTag()), decisions,
				ConsentRules.status(granted, withdrawn), List.copyOf(ConsentRules.effective(granted, withdrawn)),
				this.pseudonyms.workerRef(e.capturedBySubject()), e.capturedAt(), e.receivedAt(), null);
	}

	private List<ConsentApi.ScopeDecision> decisions(String consentEventId) {
		return this.repository.decisions(consentEventId)
			.stream()
			.map(d -> new ConsentApi.ScopeDecision(d.scope(), d.granted()))
			.toList();
	}

	private DomainException reject(Actor actor, String consentEventId, ProblemCode code) {
		this.audit.recordIndependently(event(actor, "CONSENT_EVENT_REJECTED", AuditOutcome.REJECTED, consentEventId, null));
		return new DomainException(code);
	}

	private AuditEvent event(Actor actor, String action, AuditOutcome outcome, String consentEventId, AccessReason reason) {
		return new AuditEvent(action, outcome, actor.subject(), actor.roles(), "consent_event", consentEventId, reason,
				correlationId());
	}

	private static UUID correlationId() {
		return CorrelationId.current().orElseGet(UUID::randomUUID);
	}

	private Instant now() {
		return this.clock.instant();
	}

}
