package org.mentalwellness.api.encounters.internal;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.mentalwellness.api.careteam.CareRole;
import org.mentalwellness.api.shared.web.LanguageTags;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Bodies of the encounter and care-task operations (wellness-api.v1.yaml 1.5). */
final class EncounterApi {

	private EncounterApi() {
	}

	static final String ENCOUNTER_ID = "^enc_[a-f0-9]{32}$";
	static final String TASK_ID = "^task_[a-f0-9]{32}$";
	static final String PPT = "^ppt_[a-f0-9]{32}$";
	static final String CODE = "^[A-Z0-9_]{2,40}$";

	enum EncounterType { IN_PERSON, TELE_PHONE, TELE_VIDEO, COUNSELLING_SESSION }

	enum DataOrigin { SYNTHETIC, PARTICIPANT }

	enum CodeSystem { ICD_11, ICD_10 }

	enum Certainty { PROVISIONAL, CONFIRMED, RULED_OUT }

	enum ContactMode { IN_PERSON, PHONE, VIDEO, HOME_VISIT }

	enum ReferralTarget { NONE, PSYCHIATRY_OPD, DISTRICT_MENTAL_HEALTH_PROGRAMME, TELE_MANAS, COUNSELLING, EMERGENCY_SERVICES }

	enum Assignee { ASHA_WORKER, FACILITY }

	enum TaskType { HOME_VISIT, CHECK_IN_CALL, ACCOMPANY_TO_FACILITY, TREATMENT_ADHERENCE_CHECK }

	enum TaskOutcome { DONE, NOT_REACHABLE, DECLINED }

	// --- requests ------------------------------------------------------------------

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record EncounterCreate(
			@NotNull @Pattern(regexp = ENCOUNTER_ID) String encounterId,
			@NotNull @Pattern(regexp = PPT) String participantRef,
			@NotNull @Pattern(regexp = "^ce_[a-f0-9]{32}$") String consentEventId,
			@NotNull @Pattern(regexp = "^[a-z0-9-]{3,40}$") String facilityCode,
			@NotNull EncounterType encounterType,
			@Pattern(regexp = "^ser_[a-f0-9]{32}$") String seriesId,
			@NotNull Instant startedAtUtc,
			@NotNull DataOrigin dataOrigin) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record InstrumentResponseCreate(
			@NotNull @Pattern(regexp = "^ir_[a-f0-9]{32}$") String responseId,
			@NotNull @Valid Instrument instrument,
			@NotNull @Pattern(regexp = LanguageTags.PATTERN) String languageTag,
			@NotNull Instant answeredAtUtc,
			@NotEmpty @Size(max = 30) List<@NotNull @Valid Answer> items) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record Instrument(@NotNull @Pattern(regexp = "^[a-z0-9-]{3,64}$") String instrumentId,
			@NotNull @Pattern(regexp = "^\\d+\\.\\d+\\.\\d+$") String instrumentVersion) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record Answer(@NotNull @Pattern(regexp = CODE) String itemId, @NotNull @Pattern(regexp = CODE) String answerCode) {
	}

	/** {@code toString} never prints the note. */
	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record NoteCreate(@NotNull @Pattern(regexp = "^note_[a-f0-9]{32}$") String noteId,
			@NotNull @Size(min = 1, max = 4000) @Pattern(regexp = "(?s).*\\S.*") String text) {

		@Override
		public String toString() {
			return "NoteCreate[" + this.noteId + ", <redacted>]";
		}

	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record DiagnosisCreate(@NotNull @Pattern(regexp = "^dx_[a-f0-9]{32}$") String diagnosisId, @NotNull CodeSystem codeSystem,
			@NotNull @Pattern(regexp = "^[0-9A-Z.]{3,10}$") String code, @NotNull Certainty certainty) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record CarePlanCreate(@NotNull @Pattern(regexp = "^plan_[a-f0-9]{32}$") String planId, Instant nextContactAtUtc,
			ContactMode nextContactMode, @NotNull ReferralTarget referralTarget,
			@NotNull @Size(max = 10) List<@NotNull @Valid TaskCreate> tasks) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record TaskCreate(@NotNull @Pattern(regexp = TASK_ID) String taskId, @NotNull Assignee assignee, @NotNull TaskType taskType,
			@NotNull Instant dueByUtc) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record EncounterClose(@NotNull Instant endedAtUtc) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record TaskCompletion(@NotNull Instant completedAtUtc, @NotNull TaskOutcome outcome) {
	}

	// --- responses -----------------------------------------------------------------

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record InstrumentResult(String responseId, String instrumentId, String instrumentVersion, boolean translationValidated,
			boolean complete, Integer totalScore, String severityBand, boolean urgentReviewFlag, List<Answer> items) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record Note(String noteId, String text, String authorRef, Instant createdAtUtc) {

		@Override
		public String toString() {
			return "Note[" + this.noteId + ", <redacted>]";
		}

	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record NoteStored(String noteId, String encounterId, Instant createdAtUtc) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record Diagnosis(String diagnosisId, CodeSystem codeSystem, String code, Certainty certainty, String recordedByRef,
			Instant recordedAtUtc) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record Task(String taskId, String participantRef, Assignee assignee, TaskType taskType, Instant dueByUtc, String status,
			Instant completedAtUtc, String facilityLabel) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record CarePlan(String planId, Instant nextContactAtUtc, ContactMode nextContactMode, ReferralTarget referralTarget,
			List<Task> tasks) {
	}

	/**
	 * Role-filtered view. HEALTH_STAFF: no instruments, notes or diagnoses. Notes: only for clinical
	 * staff working at the encounter's facility, and only while notes are enabled.
	 */
	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record Encounter(String encounterId, String participantRef, String facilityCode, String facilityLabel,
			EncounterType encounterType, String seriesId, CareRole careRole, String recordedByRef, DataOrigin dataOrigin,
			Instant startedAtUtc, Instant endedAtUtc, boolean urgentReviewFlag, boolean clinicalNotesEnabled,
			List<InstrumentResult> instruments, List<Note> notes, List<Diagnosis> clinicianDiagnoses, CarePlan carePlan) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record TimelineItem(String kind, String reference, Instant at, String summaryCode, boolean urgentReviewFlag,
			String facilityLabel) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record Timeline(String participantRef, List<TimelineItem> items) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record TaskList(List<Task> tasks) {
	}

}
