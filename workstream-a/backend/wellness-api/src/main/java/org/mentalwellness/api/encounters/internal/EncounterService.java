package org.mentalwellness.api.encounters.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.mentalwellness.api.audit.AuditEvent;
import org.mentalwellness.api.audit.AuditLog;
import org.mentalwellness.api.audit.AuditOutcome;
import org.mentalwellness.api.careteam.CareRole;
import org.mentalwellness.api.careteam.CareTeams;
import org.mentalwellness.api.cases.ReviewedCases;
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
 * Clinical encounters (ADR-016). Every write needs: a staff role in the token; membership at the
 * encounter's facility; that facility on the participant's care team; and CLINICAL_CARE in effect
 * on the encounter's consent event. Instruments and notes: CLINICIAN or COUNSELLOR. Diagnoses:
 * CLINICIAN only. HEALTH_STAFF: in-person encounters and care plans, but never scores, notes or
 * diagnoses.
 */
@Service
class EncounterService {

	private static final Duration FUTURE_SKEW = Duration.ofMinutes(5);

	private static final Set<CareRole> ALL_STAFF = EnumSet.allOf(CareRole.class);

	private static final Set<CareRole> CLINICAL = EnumSet.of(CareRole.CLINICIAN, CareRole.COUNSELLOR);

	private static final Set<ConsentScope> REQUIRED = Set.of(ConsentScope.CLINICAL_CARE);

	private final EncounterRepository repository;

	private final CareTeams careTeams;

	private final ConsentGate consent;

	private final ParticipantAccess participants;

	private final ReviewedCases cases;

	private final ClinicalNotes notes;

	private final IdempotencyService idempotency;

	private final AuditLog audit;

	private final Pseudonyms pseudonyms;

	private final Set<String> allowedOrigins;

	private final Clock clock;

	EncounterService(EncounterRepository repository, CareTeams careTeams, ConsentGate consent, ParticipantAccess participants,
			ReviewedCases cases, ClinicalNotes notes, IdempotencyService idempotency, AuditLog audit, Pseudonyms pseudonyms,
			@Value("${wellness.data.allowed-origins}") Set<String> allowedOrigins, Clock clock) {
		this.repository = repository;
		this.careTeams = careTeams;
		this.consent = consent;
		this.participants = participants;
		this.cases = cases;
		this.notes = notes;
		this.idempotency = idempotency;
		this.audit = audit;
		this.pseudonyms = pseudonyms;
		this.allowedOrigins = Set.copyOf(allowedOrigins);
		this.clock = clock;
	}

	// --- encounters ----------------------------------------------------------------

	@Transactional
	public IdempotencyService.Result<EncounterApi.Encounter> create(Actor actor, UUID key, EncounterApi.EncounterCreate request) {
		notInFuture(request.startedAtUtc(), "/started_at_utc");
		return this.idempotency.execute(actor.subject(), "createEncounter", key, request, EncounterApi.Encounter.class, () -> {
			if (request.dataOrigin() != EncounterApi.DataOrigin.SYNTHETIC || !this.allowedOrigins.contains("SYNTHETIC")) {
				throw rejected(actor, request.encounterId(), ProblemCode.DATA_ORIGIN_NOT_PERMITTED);
			}
			Set<CareRole> allowed = request.encounterType() == EncounterApi.EncounterType.IN_PERSON ? ALL_STAFF : CLINICAL;
			CareRole role = strongestRole(actor, request.facilityCode(), allowed);
			if (role == null || !this.careTeams.onCareTeam(request.participantRef(), request.facilityCode())) {
				throw new AccessDeniedException("not staff of a care-team facility in a role allowed for this encounter type");
			}
			requireConsent(actor, request.encounterId(), request.consentEventId(), request.participantRef(), request.startedAtUtc());
			if (this.repository.encounterExists(request.encounterId())) {
				throw new DomainException(ProblemCode.RESOURCE_CONFLICT);
			}
			this.repository.insertEncounter(request, role.name(), actor.subject());
			this.audit.record(event(actor, "ENCOUNTER_CREATED", AuditOutcome.SUCCESS, "encounter", request.encounterId()));
			return view(actor, this.repository.find(request.encounterId()).orElseThrow(), false);
		});
	}

	@Transactional // not read-only: reads are audited
	public EncounterApi.Encounter get(Actor actor, String encounterId) {
		EncounterRepository.EncounterRow e = visible(actor, encounterId);
		boolean showNotes = this.notes.enabled() && this.careTeams.worksAt(actor, e.facilityCode(), CLINICAL);
		this.audit.record(event(actor, "ENCOUNTER_READ", AuditOutcome.SUCCESS, "encounter", encounterId));
		if (showNotes && !this.repository.notes(encounterId).isEmpty()) {
			this.audit.record(event(actor, "CLINICAL_NOTE_READ", AuditOutcome.SUCCESS, "encounter", encounterId));
		}
		return view(actor, e, showNotes);
	}

	@Transactional
	public IdempotencyService.Result<EncounterApi.Encounter> close(Actor actor, UUID key, String encounterId,
			EncounterApi.EncounterClose request) {
		record Hashed(String id, EncounterApi.EncounterClose body) {
		}
		return this.idempotency.execute(actor.subject(), "closeEncounter", key, new Hashed(encounterId, request),
				EncounterApi.Encounter.class, () -> {
					EncounterRepository.EncounterRow e = writable(actor, encounterId, ALL_STAFF, request.endedAtUtc());
					if (request.endedAtUtc().isBefore(e.startedAt())) {
						throw DomainException.invalid("/ended_at_utc", "notBeforeStart");
					}
					this.repository.close(encounterId, request.endedAtUtc());
					this.audit.record(event(actor, "ENCOUNTER_CLOSED", AuditOutcome.SUCCESS, "encounter", encounterId));
					return view(actor, this.repository.find(encounterId).orElseThrow(), false);
				});
	}

	// --- instruments, notes, diagnoses ---------------------------------------------

	@Transactional
	public IdempotencyService.Result<EncounterApi.InstrumentResult> instrument(Actor actor, UUID key, String encounterId,
			EncounterApi.InstrumentResponseCreate request) {
		InstrumentCatalogue.Scored scored = InstrumentCatalogue.score(request.instrument().instrumentId(),
				request.instrument().instrumentVersion(), request.items());
		record Hashed(String id, EncounterApi.InstrumentResponseCreate body) {
		}
		return this.idempotency.execute(actor.subject(), "submitInstrumentResponse", key, new Hashed(encounterId, request),
				EncounterApi.InstrumentResult.class, () -> {
					writable(actor, encounterId, CLINICAL, request.answeredAtUtc());
					if (this.repository.responseExists(request.responseId())
							|| this.repository.instrumentAnswered(encounterId, request.instrument().instrumentId())) {
						throw new DomainException(ProblemCode.RESOURCE_CONFLICT);
					}
					this.repository.insertResponse(encounterId, request, scored, actor.subject());
					this.audit.record(event(actor, "INSTRUMENT_RESPONSE_SUBMITTED", AuditOutcome.SUCCESS, "instrument_response",
							request.responseId()));
					if (scored.urgent()) {
						this.repository.raiseUrgent(encounterId);
						this.audit.record(event(actor, "URGENT_REVIEW_FLAG_RAISED", AuditOutcome.SUCCESS, "encounter", encounterId));
					}
					return new EncounterApi.InstrumentResult(request.responseId(), request.instrument().instrumentId(),
							request.instrument().instrumentVersion(), InstrumentCatalogue.translationValidated(), scored.complete(),
							scored.totalScore(), scored.severityBand(), scored.urgent(), request.items());
				});
	}

	@Transactional
	public IdempotencyService.Result<EncounterApi.NoteStored> note(Actor actor, UUID key, String encounterId,
			EncounterApi.NoteCreate request) {
		if (!this.notes.enabled()) {
			throw new DomainException(ProblemCode.CLINICAL_NOTES_DISABLED);
		}
		record Hashed(String id, EncounterApi.NoteCreate body) {
		}
		return this.idempotency.execute(actor.subject(), "addClinicalNote", key, new Hashed(encounterId, request),
				EncounterApi.NoteStored.class, () -> {
					writable(actor, encounterId, CLINICAL, this.clock.instant());
					if (this.repository.noteExists(request.noteId())) {
						throw new DomainException(ProblemCode.RESOURCE_CONFLICT);
					}
					this.repository.insertNote(request.noteId(), encounterId,
							this.notes.seal(request.noteId(), encounterId, request.text().strip()), ClinicalNotes.KEY_VERSION,
							actor.subject());
					this.audit.record(event(actor, "CLINICAL_NOTE_ADDED", AuditOutcome.SUCCESS, "note", request.noteId()));
					return new EncounterApi.NoteStored(request.noteId(), encounterId, this.repository.noteCreatedAt(request.noteId()));
				});
	}

	@Transactional
	public IdempotencyService.Result<EncounterApi.Diagnosis> diagnosis(Actor actor, UUID key, String encounterId,
			EncounterApi.DiagnosisCreate request) {
		if (!validCode(request.codeSystem(), request.code())) {
			throw DomainException.invalid("/code", "mentalHealthChapter");
		}
		record Hashed(String id, EncounterApi.DiagnosisCreate body) {
		}
		return this.idempotency.execute(actor.subject(), "recordDiagnosis", key, new Hashed(encounterId, request),
				EncounterApi.Diagnosis.class, () -> {
					writable(actor, encounterId, EnumSet.of(CareRole.CLINICIAN), this.clock.instant());
					if (this.repository.diagnosisExists(request.diagnosisId())) {
						throw new DomainException(ProblemCode.RESOURCE_CONFLICT);
					}
					this.repository.insertDiagnosis(encounterId, request, actor.subject());
					this.audit.record(event(actor, "DIAGNOSIS_RECORDED", AuditOutcome.SUCCESS, "diagnosis", request.diagnosisId()));
					return this.repository.diagnoses(encounterId)
						.stream()
						.filter(d -> d.diagnosisId().equals(request.diagnosisId()))
						.map(this::diagnosisView)
						.findFirst()
						.orElseThrow();
				});
	}

	/** ICD-10 chapter F, or ICD-11 chapter 6 (mental, behavioural or neurodevelopmental disorders). */
	static boolean validCode(EncounterApi.CodeSystem system, String code) {
		return switch (system) {
			case ICD_10 -> code.matches("^F[0-9]{2}(\\.[0-9]{1,2})?$");
			case ICD_11 -> code.matches("^6[A-E][0-9A-Z]{2}(\\.[0-9A-Z]{1,2})?$");
		};
	}

	// --- care plan -----------------------------------------------------------------

	@Transactional
	public IdempotencyService.Result<EncounterApi.CarePlan> plan(Actor actor, UUID key, String encounterId,
			EncounterApi.CarePlanCreate request) {
		record Hashed(String id, EncounterApi.CarePlanCreate body) {
		}
		return this.idempotency.execute(actor.subject(), "recordCarePlan", key, new Hashed(encounterId, request),
				EncounterApi.CarePlan.class, () -> {
					EncounterRepository.EncounterRow e = writable(actor, encounterId, ALL_STAFF, this.clock.instant());
					boolean hasWorker = this.participants.assignedWorker(e.participantRef()).isPresent();
					for (int i = 0; i < request.tasks().size(); i++) {
						if (request.tasks().get(i).assignee() == EncounterApi.Assignee.ASHA_WORKER && !hasWorker) {
							throw DomainException.invalid("/tasks/" + i + "/assignee", "noAshaWorker");
						}
					}
					if (this.repository.planExists(encounterId) || this.repository.planIdOrTaskTaken(request.planId(),
							request.tasks().stream().map(EncounterApi.TaskCreate::taskId).toList())) {
						throw new DomainException(ProblemCode.RESOURCE_CONFLICT);
					}
					this.repository.insertPlan(encounterId, e.participantRef(), request, actor.subject());
					this.audit.record(event(actor, "CARE_PLAN_RECORDED", AuditOutcome.SUCCESS, "plan", request.planId()));
					return planView(e).orElseThrow();
				});
	}

	// --- timeline ------------------------------------------------------------------

	@Transactional // not read-only: reads are audited
	public EncounterApi.Timeline timeline(Actor actor, String participantRef) {
		if (!this.careTeams.staffAccess(actor, participantRef, ALL_STAFF)) {
			throw new DomainException(ProblemCode.NOT_FOUND);
		}
		// By membership at a care-team facility, not by token role alone (security review W1).
		boolean clinical = this.careTeams.staffAccess(actor, participantRef, CLINICAL);
		List<EncounterApi.TimelineItem> items = new ArrayList<>();
		for (var c : this.cases.forParticipant(participantRef)) {
			items.add(new EncounterApi.TimelineItem("ASHA_VISIT", c.caseId(), c.openedAt(),
					c.latestDecision() == null ? (c.completedAt() == null ? "OPEN" : "COMPLETED") : "REVIEWED_" + c.latestDecision(),
					c.urgentReviewFlag(), null));
		}
		for (var e : this.repository.forParticipant(participantRef)) {
			String summary = e.encounterType();
			if (clinical) {
				summary += this.repository.responses(e.encounterId())
					.stream()
					.filter(r -> r.severityBand() != null)
					.map(r -> " " + r.instrumentId().toUpperCase() + "=" + r.severityBand())
					.reduce("", String::concat);
			}
			items.add(new EncounterApi.TimelineItem("ENCOUNTER", e.encounterId(), e.startedAt(), summary.strip(),
					e.urgentReviewFlag(), this.careTeams.facilityLabel(e.facilityCode()).orElse(e.facilityCode())));
		}
		items.sort(Comparator.comparing(EncounterApi.TimelineItem::at));
		this.audit.record(event(actor, "TIMELINE_READ", AuditOutcome.SUCCESS, "participant", participantRef));
		return new EncounterApi.Timeline(participantRef, items);
	}

	// --- ASHA follow-up tasks ------------------------------------------------------

	@Transactional // not read-only: reads are audited
	public EncounterApi.TaskList ashaTasks(Actor actor, boolean openOnly) {
		List<EncounterApi.Task> tasks = this.repository.ashaTasks(this.participants.participantsOf(actor.subject()), openOnly)
			.stream()
			.filter(t -> stillConsented(t.consentEventId()))
			.map(this::taskView)
			.toList();
		this.audit.record(event(actor, "CARE_TASK_LIST_READ", AuditOutcome.SUCCESS, "task", null));
		return new EncounterApi.TaskList(tasks);
	}

	@Transactional
	public IdempotencyService.Result<EncounterApi.Task> completeTask(Actor actor, UUID key, String taskId,
			EncounterApi.TaskCompletion request) {
		notInFuture(request.completedAtUtc(), "/completed_at_utc");
		record Hashed(String id, EncounterApi.TaskCompletion body) {
		}
		return this.idempotency.execute(actor.subject(), "completeCareTask", key, new Hashed(taskId, request),
				EncounterApi.Task.class, () -> {
					EncounterRepository.TaskRow t = this.repository.lockTask(taskId)
						.filter(x -> "ASHA_WORKER".equals(x.assignee()))
						.filter(x -> this.participants.isAssignedTo(x.participantRef(), actor.subject()))
						.orElseThrow(() -> new DomainException(ProblemCode.NOT_FOUND));
					if (!stillConsented(t.consentEventId())) {
						throw new DomainException(ProblemCode.CONSENT_NOT_ACTIVE);
					}
					if (!"OPEN".equals(t.status())) {
						throw new DomainException(ProblemCode.RESOURCE_CONFLICT);
					}
					this.repository.completeTask(taskId, request.outcome().name(), request.completedAtUtc(), actor.subject());
					this.audit.record(event(actor, "CARE_TASK_COMPLETED", AuditOutcome.SUCCESS, "task", taskId));
					return taskView(this.repository.lockTask(taskId).orElseThrow());
				});
	}

	// --- helpers -------------------------------------------------------------------

	/** Staff who may read: on the participant's care team. Others get 404. */
	private EncounterRepository.EncounterRow visible(Actor actor, String encounterId) {
		return this.repository.find(encounterId)
			.filter(e -> this.careTeams.staffAccess(actor, e.participantRef(), ALL_STAFF))
			.orElseThrow(() -> new DomainException(ProblemCode.NOT_FOUND));
	}

	/** Locks an encounter the caller may write to in one of {@code roles}, with consent still in effect. */
	private EncounterRepository.EncounterRow writable(Actor actor, String encounterId, Set<CareRole> roles, Instant capturedAt) {
		EncounterRepository.EncounterRow e = this.repository.lock(encounterId)
			.filter(x -> this.careTeams.staffAccess(actor, x.participantRef(), ALL_STAFF))
			.orElseThrow(() -> new DomainException(ProblemCode.NOT_FOUND));
		if (!this.careTeams.worksAt(actor, e.facilityCode(), roles)
				|| !this.careTeams.onCareTeam(e.participantRef(), e.facilityCode())) {
			throw new AccessDeniedException("role not allowed for this action at this encounter's facility");
		}
		if (e.endedAt() != null) {
			throw new DomainException(ProblemCode.RESOURCE_CONFLICT);
		}
		requireConsent(actor, encounterId, e.consentEventId(), e.participantRef(), capturedAt);
		return e;
	}

	private CareRole strongestRole(Actor actor, String facility, Set<CareRole> allowed) {
		for (CareRole r : List.of(CareRole.CLINICIAN, CareRole.COUNSELLOR, CareRole.HEALTH_STAFF)) {
			if (allowed.contains(r) && this.careTeams.worksAt(actor, facility, EnumSet.of(r))) {
				return r;
			}
		}
		return null;
	}

	private void requireConsent(Actor actor, String resourceId, String consentEventId, String participantRef, Instant at) {
		try {
			this.consent.require(consentEventId, participantRef, REQUIRED, at);
		}
		catch (DomainException ex) {
			throw rejected(actor, resourceId, ex.code());
		}
	}

	private boolean stillConsented(String consentEventId) {
		try {
			this.consent.requireStillActive(consentEventId, REQUIRED);
			return true;
		}
		catch (DomainException ex) {
			return false;
		}
	}

	private void notInFuture(Instant at, String pointer) {
		if (at.isAfter(this.clock.instant().plus(FUTURE_SKEW))) {
			throw DomainException.invalid(pointer, "notInFuture");
		}
	}

	private EncounterApi.Encounter view(Actor actor, EncounterRepository.EncounterRow e, boolean showNotes) {
		// Scores need a CLINICIAN or COUNSELLOR membership on the care team, and diagnoses a CLINICIAN
		// one (security review W1, W2): a token role alone isn't enough, and counsellors don't see
		// diagnoses until the clinical lead decides (ADR-016).
		boolean clinical = this.careTeams.staffAccess(actor, e.participantRef(), CLINICAL);
		boolean clinician = this.careTeams.staffAccess(actor, e.participantRef(), EnumSet.of(CareRole.CLINICIAN));
		List<EncounterApi.InstrumentResult> instruments = clinical ? this.repository.responses(e.encounterId())
			.stream()
			.map(r -> {
				List<EncounterApi.Answer> answers = this.repository.answers(r.responseId());
				boolean urgent = InstrumentCatalogue.score(r.instrumentId(), r.instrumentVersion(), answers).urgent();
				return new EncounterApi.InstrumentResult(r.responseId(), r.instrumentId(), r.instrumentVersion(),
						InstrumentCatalogue.translationValidated(), r.totalScore() != null, r.totalScore(), r.severityBand(), urgent,
						answers);
			})
			.toList() : List.of();
		List<EncounterApi.Note> noteViews = showNotes ? this.repository.notes(e.encounterId())
			.stream()
			.map(n -> new EncounterApi.Note(n.noteId(), this.notes.open(n.noteId(), e.encounterId(), n.noteEnc()),
					this.pseudonyms.clinicianRef(n.authorSubject()), n.createdAt()))
			.toList() : List.of();
		List<EncounterApi.Diagnosis> diagnoses = clinician
				? this.repository.diagnoses(e.encounterId()).stream().map(this::diagnosisView).toList() : List.of();
		return new EncounterApi.Encounter(e.encounterId(), e.participantRef(), e.facilityCode(),
				this.careTeams.facilityLabel(e.facilityCode()).orElse(e.facilityCode()),
				EncounterApi.EncounterType.valueOf(e.encounterType()), e.seriesId(), CareRole.valueOf(e.careRole()),
				this.pseudonyms.clinicianRef(e.createdBySubject()), EncounterApi.DataOrigin.valueOf(e.dataOrigin()), e.startedAt(),
				e.endedAt(), e.urgentReviewFlag(), this.notes.enabled(), instruments, noteViews, diagnoses, planView(e).orElse(null));
	}

	private java.util.Optional<EncounterApi.CarePlan> planView(EncounterRepository.EncounterRow e) {
		return this.repository.plan(e.encounterId())
			.map(p -> new EncounterApi.CarePlan(p.planId(), p.nextContactAt(),
					p.nextContactMode() == null ? null : EncounterApi.ContactMode.valueOf(p.nextContactMode()),
					EncounterApi.ReferralTarget.valueOf(p.referralTarget()),
					this.repository.tasksOfPlan(p.planId()).stream().map(this::taskView).toList()));
	}

	private EncounterApi.Diagnosis diagnosisView(EncounterRepository.DiagnosisRow d) {
		return new EncounterApi.Diagnosis(d.diagnosisId(), EncounterApi.CodeSystem.valueOf(d.codeSystem()), d.code(),
				EncounterApi.Certainty.valueOf(d.certainty()), this.pseudonyms.clinicianRef(d.recordedBySubject()), d.recordedAt());
	}

	private EncounterApi.Task taskView(EncounterRepository.TaskRow t) {
		return new EncounterApi.Task(t.taskId(), t.participantRef(), EncounterApi.Assignee.valueOf(t.assignee()),
				EncounterApi.TaskType.valueOf(t.taskType()), t.dueBy(), t.status(), t.completedAt(),
				this.careTeams.facilityLabel(t.facilityCode()).orElse(t.facilityCode()));
	}

	private DomainException rejected(Actor actor, String resourceId, ProblemKind code) {
		this.audit.recordIndependently(event(actor, "ENCOUNTER_REJECTED", AuditOutcome.REJECTED, "encounter",
				resourceId != null && resourceId.startsWith("enc_") ? resourceId : null));
		return new DomainException(code);
	}

	private static AuditEvent event(Actor actor, String action, AuditOutcome outcome, String type, String id) {
		return new AuditEvent(action, outcome, actor.subject(), actor.roles(), type, id, null,
				CorrelationId.current().orElseGet(UUID::randomUUID));
	}

}
