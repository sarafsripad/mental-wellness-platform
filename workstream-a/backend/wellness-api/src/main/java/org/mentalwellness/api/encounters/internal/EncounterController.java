package org.mentalwellness.api.encounters.internal;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.mentalwellness.api.shared.idempotency.IdempotencyService;
import org.mentalwellness.api.shared.security.Actor;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Encounter, timeline and care-task operations of wellness-api.v1.yaml (1.5). */
@RestController
@Validated
class EncounterController {

	private static final String STAFF_WRITE = "hasAnyRole('CLINICIAN', 'COUNSELLOR', 'HEALTH_STAFF') and hasAuthority('SCOPE_encounters:write')";

	private static final String STAFF_READ = "hasAnyRole('CLINICIAN', 'COUNSELLOR', 'HEALTH_STAFF') and hasAuthority('SCOPE_encounters:read')";

	private final EncounterService service;

	EncounterController(EncounterService service) {
		this.service = service;
	}

	@PostMapping("/v1/encounters")
	@PreAuthorize(STAFF_WRITE)
	ResponseEntity<EncounterApi.Encounter> create(@RequestHeader("Idempotency-Key") UUID key,
			@Valid @RequestBody EncounterApi.EncounterCreate request) {
		return created(this.service.create(Actor.current(), key, request));
	}

	@GetMapping("/v1/encounters/{encounterId}")
	@PreAuthorize(STAFF_READ)
	ResponseEntity<EncounterApi.Encounter> get(@PathVariable @Pattern(regexp = EncounterApi.ENCOUNTER_ID) String encounterId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(this.service.get(Actor.current(), encounterId));
	}

	@PostMapping("/v1/encounters/{encounterId}/instrument-responses")
	@PreAuthorize("hasAnyRole('CLINICIAN', 'COUNSELLOR') and hasAuthority('SCOPE_encounters:write')")
	ResponseEntity<EncounterApi.InstrumentResult> instrument(
			@PathVariable @Pattern(regexp = EncounterApi.ENCOUNTER_ID) String encounterId, @RequestHeader("Idempotency-Key") UUID key,
			@Valid @RequestBody EncounterApi.InstrumentResponseCreate request) {
		return created(this.service.instrument(Actor.current(), key, encounterId, request));
	}

	@PostMapping("/v1/encounters/{encounterId}/notes")
	@PreAuthorize("hasAnyRole('CLINICIAN', 'COUNSELLOR') and hasAuthority('SCOPE_encounters:write')")
	ResponseEntity<EncounterApi.NoteStored> note(@PathVariable @Pattern(regexp = EncounterApi.ENCOUNTER_ID) String encounterId,
			@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody EncounterApi.NoteCreate request) {
		return created(this.service.note(Actor.current(), key, encounterId, request));
	}

	@PostMapping("/v1/encounters/{encounterId}/diagnoses")
	@PreAuthorize("hasRole('CLINICIAN') and hasAuthority('SCOPE_encounters:write')")
	ResponseEntity<EncounterApi.Diagnosis> diagnosis(@PathVariable @Pattern(regexp = EncounterApi.ENCOUNTER_ID) String encounterId,
			@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody EncounterApi.DiagnosisCreate request) {
		return created(this.service.diagnosis(Actor.current(), key, encounterId, request));
	}

	@PostMapping("/v1/encounters/{encounterId}/care-plan")
	@PreAuthorize(STAFF_WRITE)
	ResponseEntity<EncounterApi.CarePlan> plan(@PathVariable @Pattern(regexp = EncounterApi.ENCOUNTER_ID) String encounterId,
			@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody EncounterApi.CarePlanCreate request) {
		return created(this.service.plan(Actor.current(), key, encounterId, request));
	}

	@PostMapping("/v1/encounters/{encounterId}/close")
	@PreAuthorize(STAFF_WRITE)
	ResponseEntity<EncounterApi.Encounter> close(@PathVariable @Pattern(regexp = EncounterApi.ENCOUNTER_ID) String encounterId,
			@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody EncounterApi.EncounterClose request) {
		return created(this.service.close(Actor.current(), key, encounterId, request));
	}

	@GetMapping("/v1/participants/{participantRef}/timeline")
	@PreAuthorize(STAFF_READ)
	ResponseEntity<EncounterApi.Timeline> timeline(@PathVariable @Pattern(regexp = EncounterApi.PPT) String participantRef) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(this.service.timeline(Actor.current(), participantRef));
	}

	@GetMapping("/v1/care-tasks")
	@PreAuthorize("hasRole('ASHA_WORKER') and hasAuthority('SCOPE_cases:read')")
	EncounterApi.TaskList tasks(@RequestParam(name = "status", defaultValue = "OPEN") @Pattern(regexp = "^(OPEN|ALL)$") String status) {
		return this.service.ashaTasks(Actor.current(), "OPEN".equals(status));
	}

	@PostMapping("/v1/care-tasks/{taskId}/completion")
	@PreAuthorize("hasRole('ASHA_WORKER') and hasAuthority('SCOPE_cases:write')")
	ResponseEntity<EncounterApi.Task> completeTask(@PathVariable @Pattern(regexp = EncounterApi.TASK_ID) String taskId,
			@RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody EncounterApi.TaskCompletion request) {
		return created(this.service.completeTask(Actor.current(), key, taskId, request));
	}

	private static <T> ResponseEntity<T> created(IdempotencyService.Result<T> result) {
		return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.body());
	}

}
