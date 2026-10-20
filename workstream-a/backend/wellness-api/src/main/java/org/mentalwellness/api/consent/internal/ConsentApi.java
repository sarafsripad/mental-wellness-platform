package org.mentalwellness.api.consent.internal;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.mentalwellness.api.consent.ConsentScope;
import org.mentalwellness.api.shared.web.LanguageTags;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** Request and response bodies of the consent operations, mirroring wellness-api.v1.yaml. */
final class ConsentApi {

	private ConsentApi() {
	}

	static final String CONSENT_EVENT_ID = "^ce_[a-f0-9]{32}$";
	static final String PARTICIPANT_REF = "^ppt_[a-f0-9]{32}$";
	static final String WITHDRAWAL_ID = "^cw_[a-f0-9]{32}$";

	enum CaptureMethod { SIGNED_ON_DEVICE, THUMBPRINT_ON_DEVICE, VERBAL_WITNESSED }

	enum Channel { IN_PERSON_ASHA, IN_PERSON_CLINICIAN, PHONE_TO_PROGRAMME, WRITTEN_REQUEST }

	enum ReasonCode { NO_LONGER_WISHES, PRIVACY_CONCERN, OTHER, NOT_STATED }

	enum EventStatus { ACTIVE, PARTIALLY_WITHDRAWN, WITHDRAWN, EXPIRED }

	enum ParticipantStatus { NO_CONSENT, ACTIVE, PARTIALLY_WITHDRAWN, WITHDRAWN, EXPIRED }

	// --- requests ------------------------------------------------------------------

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record ConsentEventCreate(
			@NotNull @Pattern(regexp = CONSENT_EVENT_ID) String consentEventId,
			@NotNull @Pattern(regexp = PARTICIPANT_REF) String participantRef,
			@NotNull @Valid ConsentForm consentForm,
			@NotEmpty @Size(max = 7) List<@NotNull @Valid ScopeDecision> scopeDecisions,
			@NotNull @Valid Capture capture,
			@NotNull @Valid Safeguards safeguards) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record ConsentForm(
			@NotNull @Pattern(regexp = "^[a-z0-9-]{3,64}$") String formId,
			@NotNull @Pattern(regexp = "^\\d+\\.\\d+\\.\\d+$") String formVersion,
			@NotNull @Pattern(regexp = LanguageTags.PATTERN) String languageTag) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record ScopeDecision(@NotNull ConsentScope scope, @NotNull Boolean granted) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record Capture(
			@NotNull Instant capturedAtUtc,
			@NotNull CaptureMethod method,
			@NotNull Boolean readAloud,
			@NotNull Boolean comprehensionConfirmed,
			Boolean witnessPresent) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record Safeguards(@NotNull Boolean adultConfirmed, Boolean assistedConsent) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record ConsentWithdrawalCreate(
			@NotNull @Pattern(regexp = WITHDRAWAL_ID) String withdrawalId,
			@Size(min = 1, max = 7) Set<@NotNull ConsentScope> scopes,
			@NotNull Instant withdrawnAtUtc,
			@NotNull Channel channel,
			ReasonCode reasonCode) {
	}

	// --- responses -----------------------------------------------------------------

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record ConsentEvent(String consentEventId, String participantRef, ConsentForm consentForm,
			List<ScopeDecision> scopeDecisions, EventStatus status, List<ConsentScope> effectiveScopes,
			String capturedByWorkerRef, Instant capturedAtUtc, Instant receivedAtUtc, Instant expiresAtUtc) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record DownstreamAction(String action, String status) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record ConsentWithdrawal(String withdrawalId, String consentEventId, List<ConsentScope> withdrawnScopes,
			EventStatus resultingStatus, List<DownstreamAction> downstreamActions, Instant recordedAtUtc) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record ConsentStatus(String participantRef, ParticipantStatus status, String activeConsentEventId,
			List<ConsentScope> effectiveScopes, Instant asOfUtc) {
	}

}
