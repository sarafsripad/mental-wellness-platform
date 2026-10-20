package org.mentalwellness.api.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.mentalwellness.api.audit.AuditEvent;
import org.mentalwellness.api.audit.AuditLog;
import org.mentalwellness.api.audit.AuditOutcome;
import org.mentalwellness.api.careteam.CareRole;
import org.mentalwellness.api.careteam.CareTeams;
import org.mentalwellness.api.consent.ConsentGate;
import org.mentalwellness.api.consent.ConsentScope;
import org.mentalwellness.api.consent.ParticipantAccess;
import org.mentalwellness.api.shared.security.Actor;
import org.mentalwellness.api.shared.web.CorrelationId;
import org.mentalwellness.api.shared.web.DomainException;
import org.mentalwellness.api.shared.web.ProblemCode;
import org.mentalwellness.api.shared.web.ProblemKind;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Identity (identity-api.v1.yaml 1.1, ADR-010/013/016).
 *
 * <ul>
 * <li>Store: the participant's ASHA worker, or staff on their care team. Screening or clinical-care
 * consent must be in effect.</li>
 * <li>Lookup and search: care-team staff only, reason-coded, rate-limited and audited. Consent behind
 * the stored record must still be in effect. ASHA workers can't read identity back.</li>
 * </ul>
 */
@Service
@ConditionalOnWebApplication
class IdentityService {

	private static final Set<CareRole> STAFF = EnumSet.allOf(CareRole.class);

	private static final Set<ConsentScope> STORING_SCOPES = Set.of(ConsentScope.SCREENING_PARTICIPATION, ConsentScope.CLINICAL_CARE);

	private final IdentityStore store;

	private final ConsentGate consent;

	private final ParticipantAccess participants;

	private final CareTeams careTeams;

	private final AuditLog audit;

	private final Set<String> allowedOrigins;

	private final LookupLimiter limiter;

	IdentityService(IdentityStore store, ConsentGate consent, ParticipantAccess participants, CareTeams careTeams,
			AuditLog audit, @Value("${wellness.data.allowed-origins}") Set<String> allowedOrigins,
			@Value("${wellness.identity.lookups-per-hour:30}") int lookupsPerHour, Clock clock) {
		this.store = store;
		this.consent = consent;
		this.participants = participants;
		this.careTeams = careTeams;
		this.audit = audit;
		this.allowedOrigins = Set.copyOf(allowedOrigins);
		this.limiter = new LookupLimiter(lookupsPerHour, Duration.ofHours(1), clock);
	}

	/** Consent is checked in app_db, then identity_db is written; a failed write is audited on its own. */
	@Transactional
	public IdentityApi.IdentityStored put(Actor actor, String participantRef, IdentityApi.IdentityRecordPut request) {
		if (request.dataOrigin() != IdentityApi.DataOrigin.SYNTHETIC
				|| !this.allowedOrigins.contains(request.dataOrigin().name())) {
			throw rejected(actor, participantRef, ProblemCode.DATA_ORIGIN_NOT_PERMITTED);
		}
		boolean worker = actor.has(Actor.ASHA_WORKER) && this.participants.isAssignedTo(participantRef, actor.subject());
		if (!worker && !this.careTeams.staffAccess(actor, participantRef, STAFF)) {
			throw new AccessDeniedException("not the participant's worker or care-team staff");
		}
		requireAny(actor, participantRef, request.consentEventId());
		String card;
		try {
			card = this.store.put(participantRef, request.consentEventId(), request.dataOrigin().name(),
					request.displayName().strip(), request.villageName().strip(), request.contactPhone(), actor.subject());
		}
		catch (RuntimeException ex) {
			this.audit.recordIndependently(event(actor, "IDENTITY_RECORD_FAILED", AuditOutcome.FAILED, participantRef));
			throw ex;
		}
		this.audit.record(event(actor, "IDENTITY_RECORD_STORED", AuditOutcome.SUCCESS, participantRef));
		return new IdentityApi.IdentityStored(card);
	}

	/** Care-team staff see who the person is, for a stated reason. Every lookup is audited. */
	@Transactional
	public IdentityApi.IdentityDetails lookup(Actor actor, String participantRef, IdentityApi.LookupReason reason) {
		if (!this.careTeams.staffAccess(actor, participantRef, STAFF)) {
			this.audit.recordIndependently(event(actor, "IDENTITY_LOOKUP_DENIED", AuditOutcome.DENIED, participantRef));
			throw new DomainException(ProblemCode.NOT_FOUND);
		}
		if (!this.limiter.tryAcquire(actor.subject())) {
			throw rejectedAs(actor, participantRef, "IDENTITY_LOOKUP_RATE_LIMITED", ProblemCode.RATE_LIMITED);
		}
		IdentityStore.Person person = this.store.read(participantRef)
			.orElseThrow(() -> new DomainException(ProblemCode.NOT_FOUND));
		if (!stillConsented(person.consentEventId())) {
			throw rejectedAs(actor, participantRef, "IDENTITY_LOOKUP_REJECTED", ProblemCode.CONSENT_NOT_ACTIVE);
		}
		this.audit.record(event(actor, "IDENTITY_LOOKUP_" + reason.name(), AuditOutcome.SUCCESS, participantRef));
		return new IdentityApi.IdentityDetails(participantRef, person.displayName(), person.villageName(), person.contactPhone(),
				person.patientCardCode());
	}

	/** Finds participants on the caller's care teams; returns references only. */
	@Transactional
	public IdentityApi.SearchResult search(Actor actor, IdentityApi.SearchRequest request) {
		boolean byCard = request.patientCardCode() != null;
		boolean byName = request.displayName() != null && request.villageName() != null
				&& !request.displayName().isBlank() && !request.villageName().isBlank();
		if (byCard == byName) {
			throw DomainException.invalid("/", "cardCodeOrNameAndVillage");
		}
		if (!this.limiter.tryAcquire(actor.subject())) {
			this.audit.recordIndependently(event(actor, "IDENTITY_SEARCH_RATE_LIMITED", AuditOutcome.DENIED, null));
			throw new DomainException(ProblemCode.RATE_LIMITED);
		}
		List<String> found = byCard ? this.store.byCardCode(request.patientCardCode())
				: this.store.byNameAndVillage(request.displayName(), request.villageName());
		List<String> visible = found.stream().filter(ref -> this.careTeams.staffAccess(actor, ref, STAFF)).toList();
		this.audit.record(event(actor, byCard ? "IDENTITY_SEARCH_CARD" : "IDENTITY_SEARCH_NAME", AuditOutcome.SUCCESS, null));
		// A match links a person to a pseudonym, so each one is audited against that participant (review W7).
		visible.forEach(ref -> this.audit.record(event(actor, "IDENTITY_SEARCH_MATCH", AuditOutcome.SUCCESS, ref)));
		return new IdentityApi.SearchResult(visible);
	}

	private void requireAny(Actor actor, String participantRef, String consentEventId) {
		DomainException last = null;
		for (ConsentScope scope : STORING_SCOPES) {
			try {
				// No capture time: identity is stored now, after consent; the scope must still be in effect.
				this.consent.require(consentEventId, participantRef, Set.of(scope), null);
				return;
			}
			catch (DomainException ex) {
				last = ex;
			}
		}
		throw rejected(actor, participantRef, last.code());
	}

	private boolean stillConsented(String consentEventId) {
		for (ConsentScope scope : STORING_SCOPES) {
			try {
				this.consent.requireStillActive(consentEventId, Set.of(scope));
				return true;
			}
			catch (DomainException ex) {
				// try the other scope
			}
		}
		return false;
	}

	private DomainException rejected(Actor actor, String participantRef, ProblemKind code) {
		return rejectedAs(actor, participantRef, "IDENTITY_RECORD_REJECTED", code);
	}

	private DomainException rejectedAs(Actor actor, String participantRef, String action, ProblemKind code) {
		this.audit.recordIndependently(event(actor, action, AuditOutcome.REJECTED, participantRef));
		return new DomainException(code);
	}

	private static AuditEvent event(Actor actor, String action, AuditOutcome outcome, String participantRef) {
		return new AuditEvent(action, outcome, actor.subject(), actor.roles(), participantRef == null ? null : "participant",
				participantRef, null, CorrelationId.current().orElseGet(UUID::randomUUID));
	}

}
