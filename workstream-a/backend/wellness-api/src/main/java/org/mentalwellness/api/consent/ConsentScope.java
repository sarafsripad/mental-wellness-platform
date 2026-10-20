package org.mentalwellness.api.consent;

import java.util.EnumSet;
import java.util.Set;

/**
 * Consent scopes and their dependencies (docs/privacy/consent-and-data-lifecycle.md section 2,
 * ADR-011). A scope can only be granted if the scope it depends on is granted, and withdrawing a
 * scope withdraws everything that depends on it.
 */
public enum ConsentScope {

	SCREENING_PARTICIPATION(null),
	AUDIO_RECORDING(SCREENING_PARTICIPATION),
	AUDIO_RETENTION(AUDIO_RECORDING),
	RESEARCH_FEATURE_REUSE(AUDIO_RECORDING),
	MODEL_TRAINING(RESEARCH_FEATURE_REUSE),
	QUALITY_REVIEW(AUDIO_RECORDING),
	/** Sharing a pseudonymous referral with a named partner programme such as Tele-MANAS (ADR-015). */
	EXTERNAL_REFERRAL_SHARING(SCREENING_PARTICIPATION),
	/** Care by facility staff: clinic encounters, assessments, notes, diagnoses and care plans (ADR-016). */
	CLINICAL_CARE(null);

	/** The scopes the feature-record schema v2.0.0 knows; feature records list only these. */
	public static final Set<ConsentScope> FEATURE_RECORD_SCOPES = EnumSet.of(SCREENING_PARTICIPATION, AUDIO_RECORDING,
			AUDIO_RETENTION, RESEARCH_FEATURE_REUSE, MODEL_TRAINING, QUALITY_REVIEW);

	private final ConsentScope requires;

	ConsentScope(ConsentScope requires) {
		this.requires = requires;
	}

	/** The scope this one depends on, or null. */
	public ConsentScope requires() {
		return this.requires;
	}

	/** These scopes plus everything that depends on them, directly or indirectly. */
	public static Set<ConsentScope> withDependents(Set<ConsentScope> scopes) {
		EnumSet<ConsentScope> result = scopes.isEmpty() ? EnumSet.noneOf(ConsentScope.class) : EnumSet.copyOf(scopes);
		boolean changed = true;
		while (changed) {
			changed = false;
			for (ConsentScope scope : values()) {
				if (scope.requires != null && result.contains(scope.requires) && result.add(scope)) {
					changed = true;
				}
			}
		}
		return result;
	}

}
