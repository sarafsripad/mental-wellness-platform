package org.mentalwellness.api.identity.internal;

import java.util.Set;
import java.util.UUID;

import org.mentalwellness.api.audit.AuditEvent;
import org.mentalwellness.api.audit.AuditLog;
import org.mentalwellness.api.audit.AuditOutcome;
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
 * Write-only identity intake (identity-api.v1.yaml). Checks assignment and consent in app_db, then
 * writes ciphertext to identity_db. The audit event names the participant reference only.
 */
@Service
@ConditionalOnWebApplication
class IdentityService {

	private final IdentityStore store;

	private final ConsentGate consent;

	private final ParticipantAccess participants;

	private final AuditLog audit;

	private final Set<String> allowedOrigins;

	IdentityService(IdentityStore store, ConsentGate consent, ParticipantAccess participants, AuditLog audit,
			@Value("${wellness.data.allowed-origins}") Set<String> allowedOrigins) {
		this.store = store;
		this.consent = consent;
		this.participants = participants;
		this.audit = audit;
		this.allowedOrigins = Set.copyOf(allowedOrigins);
	}

	/** Consent is checked in app_db, then identity_db is written; a failed write is audited on its own. */
	@Transactional
	public void put(Actor actor, String participantRef, IdentityApi.IdentityRecordPut request) {
		if (request.dataOrigin() != IdentityApi.DataOrigin.SYNTHETIC
				|| !this.allowedOrigins.contains(request.dataOrigin().name())) {
			throw rejected(actor, participantRef, ProblemCode.DATA_ORIGIN_NOT_PERMITTED);
		}
		if (!this.participants.isAssignedTo(participantRef, actor.subject())) {
			throw new AccessDeniedException("participant not assigned to caller");
		}
		try {
			// No capture time: identity is stored now, after consent; the scope must still be in effect.
			this.consent.require(request.consentEventId(), participantRef, Set.of(ConsentScope.SCREENING_PARTICIPATION), null);
		}
		catch (DomainException ex) {
			throw rejected(actor, participantRef, ex.code());
		}
		try {
			this.store.put(participantRef, request.consentEventId(), request.dataOrigin().name(),
					request.displayName().strip(), request.villageName().strip(), actor.subject());
		}
		catch (RuntimeException ex) {
			this.audit.recordIndependently(event(actor, "IDENTITY_RECORD_FAILED", AuditOutcome.FAILED, participantRef));
			throw ex;
		}
		this.audit.record(event(actor, "IDENTITY_RECORD_STORED", AuditOutcome.SUCCESS, participantRef));
	}

	private DomainException rejected(Actor actor, String participantRef, ProblemKind code) {
		this.audit.recordIndependently(event(actor, "IDENTITY_RECORD_REJECTED", AuditOutcome.REJECTED, participantRef));
		return new DomainException(code);
	}

	private static AuditEvent event(Actor actor, String action, AuditOutcome outcome, String participantRef) {
		return new AuditEvent(action, outcome, actor.subject(), actor.roles(), "participant", participantRef, null,
				CorrelationId.current().orElseGet(UUID::randomUUID));
	}

}
