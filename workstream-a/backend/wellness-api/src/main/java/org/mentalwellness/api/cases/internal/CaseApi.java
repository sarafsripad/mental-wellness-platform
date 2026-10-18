package org.mentalwellness.api.cases.internal;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.mentalwellness.api.shared.web.LanguageTags;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Bodies of the case operations, mirroring wellness-api.v1.yaml (1.1, tag {@code cases}). */
final class CaseApi {

	private CaseApi() {
	}

	static final String CASE_ID = "^case_[a-f0-9]{32}$";
	static final String CODE = "^[A-Z][A-Z0-9_]{1,40}$";
	static final String SLUG = "^[a-z0-9-]{3,64}$";
	static final String SEMVER = "^\\d+\\.\\d+\\.\\d+$";

	enum DataOrigin { SYNTHETIC, PARTICIPANT }

	enum AgeBand { AGE_UNDER_18, AGE_18_29, AGE_30_44, AGE_45_59, AGE_60_PLUS }

	enum Gender { NOT_STATED, FEMALE, MALE, OTHER }

	enum Status { OPEN, COMPLETED }

	enum ObservationCode { HEALTH_CONDITION, TOBACCO_USE, ALCOHOL_USE, SLEEP_QUALITY }

	// --- requests ------------------------------------------------------------------

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record CaseCreate(
			@NotNull @Pattern(regexp = CASE_ID) String caseId,
			@NotNull @Pattern(regexp = "^ppt_[a-f0-9]{32}$") String participantRef,
			@NotNull @Pattern(regexp = "^ce_[a-f0-9]{32}$") String consentEventId,
			@NotNull @Pattern(regexp = LanguageTags.PATTERN) String languageTag,
			@NotNull Instant openedAtUtc,
			@NotNull DataOrigin dataOrigin,
			@NotNull AgeBand ageBand,
			Gender gender) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record ObservationSetCreate(
			@NotNull @Pattern(regexp = "^obs_[a-f0-9]{32}$") String observationSetId,
			@NotNull @Valid Catalogue catalogue,
			@NotNull Instant observedAtUtc,
			@NotEmpty @Size(max = 12) List<@NotNull @Valid Observation> observations) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record Catalogue(@NotNull @Pattern(regexp = SLUG) String catalogueId,
			@NotNull @Pattern(regexp = SEMVER) String catalogueVersion) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record Observation(@NotNull ObservationCode code, @NotNull @Pattern(regexp = CODE) String valueCode) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record QuestionnaireResponseCreate(
			@NotNull @Pattern(regexp = "^qr_[a-f0-9]{32}$") String responseId,
			@NotNull @Valid Instrument instrument,
			@NotNull @Pattern(regexp = LanguageTags.PATTERN) String languageTag,
			@NotNull Instant answeredAtUtc,
			@NotEmpty @Size(max = 20) List<@NotNull @Valid Answer> items) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record Instrument(@NotNull @Pattern(regexp = SLUG) String instrumentId,
			@NotNull @Pattern(regexp = SEMVER) String instrumentVersion) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record Answer(@NotNull @Pattern(regexp = CODE) String itemId, @NotNull @Pattern(regexp = CODE) String answerCode) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record CaseCompletion(@NotNull Instant completedAtUtc, @NotNull Boolean followUpRequested) {
	}

	// --- responses -----------------------------------------------------------------

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record Case(String caseId, String participantRef, String consentEventId, String languageTag, DataOrigin dataOrigin,
			AgeBand ageBand, Gender gender, Status status, boolean urgentReviewFlag, Boolean followUpRequested,
			boolean observationsSubmitted, boolean questionnaireSubmitted, String openedByWorkerRef,
			Instant openedAtUtc, Instant completedAtUtc, Instant createdAtUtc) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record CaseList(List<Case> cases) {
	}

}
