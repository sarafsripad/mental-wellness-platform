package org.mentalwellness.api.careteam.internal;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.mentalwellness.api.audit.AccessReason;
import org.mentalwellness.api.audit.AuditEvent;
import org.mentalwellness.api.audit.AuditLog;
import org.mentalwellness.api.audit.AuditOutcome;
import org.mentalwellness.api.careteam.CareRole;
import org.mentalwellness.api.careteam.CareTeams;
import org.mentalwellness.api.consent.ConsentGate;
import org.mentalwellness.api.consent.ConsentScope;
import org.mentalwellness.api.consent.ParticipantAccess;
import org.mentalwellness.api.shared.security.Actor;
import org.mentalwellness.api.shared.security.Pseudonyms;
import org.mentalwellness.api.shared.web.CorrelationId;
import org.mentalwellness.api.shared.web.DomainException;
import org.mentalwellness.api.shared.web.ProblemCode;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Care teams (ADR-016). Staff reach a participant only through an active membership, in a role
 * they also hold in their token, at an active facility on that participant's care team. The ASHA
 * worker's own link is unchanged and lives in the consent module.
 */
@Service
class CareTeamService implements CareTeams {

	private final CareTeamRepository repository;

	private final ParticipantAccess participants;

	private final ConsentGate consent;

	private final AuditLog audit;

	private final Pseudonyms pseudonyms;

	CareTeamService(CareTeamRepository repository, ParticipantAccess participants, ConsentGate consent, AuditLog audit,
			Pseudonyms pseudonyms) {
		this.repository = repository;
		this.participants = participants;
		this.consent = consent;
		this.audit = audit;
		this.pseudonyms = pseudonyms;
	}

	// --- the access rule (CareTeams) -----------------------------------------------------

	@Override
	@Transactional(readOnly = true)
	public boolean staffAccess(Actor actor, String participantRef, Set<CareRole> roles) {
		Set<String> held = held(actor, roles);
		return !held.isEmpty() && this.repository.staffAccess(actor.subject(), participantRef, held);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean worksAt(Actor actor, String facilityCode, Set<CareRole> roles) {
		Set<String> held = held(actor, roles);
		return !held.isEmpty() && this.repository.worksAt(actor.subject(), facilityCode, held);
	}

	@Override
	@Transactional
	public void addFacility(String participantRef, String facilityCode, String reasonCode, Actor actor) {
		this.repository.findFacility(facilityCode)
			.filter(CareTeamRepository.FacilityRow::active)
			.orElseThrow(() -> new DomainException(ProblemCode.NOT_FOUND));
		this.repository.addToCareTeam(participantRef, facilityCode, reasonCode, actor.subject());
		this.audit.record(event(actor, "CARE_TEAM_FACILITY_ADDED", "participant", participantRef, null));
	}

	/** Roles both requested and present in the token: a membership alone is not enough. */
	private static Set<String> held(Actor actor, Set<CareRole> roles) {
		return roles.stream().map(Enum::name).filter(actor::has).collect(Collectors.toSet());
	}

	// --- administration ----------------------------------------------------------------

	@Transactional
	public CareTeamApi.Facility putFacility(Actor actor, AccessReason reason, String code, CareTeamApi.FacilityPut body) {
		this.repository.upsertFacility(code, body.kind().name(), body.facilityLabel().strip(), body.active());
		this.audit.record(event(actor, "FACILITY_UPSERTED", "facility", null, reason));
		return new CareTeamApi.Facility(code, body.kind(), body.facilityLabel().strip(), body.active());
	}

	@Transactional
	public CareTeamApi.Membership putMembership(Actor actor, AccessReason reason, String code, String subject,
			CareTeamApi.MembershipPut body) {
		CareTeamRepository.FacilityRow f = this.repository.findFacility(code)
			.orElseThrow(() -> new DomainException(ProblemCode.NOT_FOUND));
		this.repository.upsertMembership(subject, code, body.careRole().name(), body.active(), actor.subject());
		this.audit.record(event(actor, "STAFF_MEMBERSHIP_UPSERTED", "facility", null, reason));
		return new CareTeamApi.Membership(code, f.facilityLabel(), CareTeamApi.Kind.valueOf(f.kind()), body.careRole());
	}

	// --- care team of a participant ------------------------------------------------------

	/**
	 * The ASHA worker refers their participant to a facility, or an ADMIN adds one (reason-coded).
	 * Screening consent must be in effect on the event the caller names.
	 */
	@Transactional
	public CareTeamApi.CareTeam add(Actor actor, AccessReason reason, String participantRef, String facilityCode,
			CareTeamApi.CareTeamAdd body) {
		boolean owner = actor.has(Actor.ASHA_WORKER) && this.participants.isAssignedTo(participantRef, actor.subject());
		boolean admin = actor.has(Actor.ADMIN) && reason != null;
		if (!owner && !admin) {
			throw new AccessDeniedException("not the participant's worker or an administrator");
		}
		if (owner && !admin && body.reasonCode() != CareTeamApi.Reason.ASHA_REFERRAL) {
			throw DomainException.invalid("/reason_code", "ashaReferralOnly");
		}
		this.consent.require(body.consentEventId(), participantRef, Set.of(ConsentScope.SCREENING_PARTICIPATION), null);
		addFacility(participantRef, facilityCode, body.reasonCode().name(), actor);
		return view(participantRef);
	}

	@Transactional // not read-only: reads are audited
	public CareTeamApi.CareTeam read(Actor actor, AccessReason reason, String participantRef) {
		boolean owner = actor.has(Actor.ASHA_WORKER) && this.participants.isAssignedTo(participantRef, actor.subject());
		boolean staff = staffAccess(actor, participantRef, EnumSet.allOf(CareRole.class));
		boolean admin = actor.has(Actor.ADMIN) && reason != null;
		if (!owner && !staff && !admin) {
			throw new DomainException(ProblemCode.NOT_FOUND);
		}
		this.audit.record(event(actor, "CARE_TEAM_READ", "participant", participantRef, reason));
		return view(participantRef);
	}

	@Transactional(readOnly = true)
	public CareTeamApi.MyMemberships mine(Actor actor) {
		return new CareTeamApi.MyMemberships(this.pseudonyms.clinicianRef(actor.subject()),
				this.repository.memberships(actor.subject())
					.stream()
					.filter(m -> actor.has(m.careRole()))
					.map(m -> new CareTeamApi.Membership(m.facilityCode(), m.facilityLabel(), CareTeamApi.Kind.valueOf(m.kind()),
							CareRole.valueOf(m.careRole())))
					.toList());
	}

	private CareTeamApi.CareTeam view(String participantRef) {
		String worker = this.participants.assignedWorker(participantRef).map(this.pseudonyms::workerRef).orElse(null);
		return new CareTeamApi.CareTeam(participantRef, worker, this.repository.careTeam(participantRef)
			.stream()
			.map(c -> new CareTeamApi.CareTeamFacility(c.facilityCode(), c.facilityLabel(), CareTeamApi.Kind.valueOf(c.kind()),
					CareTeamApi.Reason.valueOf(c.reasonCode()), c.addedAt()))
			.toList());
	}

	private static AuditEvent event(Actor actor, String action, String type, String id, AccessReason reason) {
		return new AuditEvent(action, AuditOutcome.SUCCESS, actor.subject(), actor.roles(), type, id, reason,
				CorrelationId.current().orElseGet(UUID::randomUUID));
	}

}
