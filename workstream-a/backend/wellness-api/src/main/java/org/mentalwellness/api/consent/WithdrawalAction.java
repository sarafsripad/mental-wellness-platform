package org.mentalwellness.api.consent;

import java.util.Set;

/**
 * Extension point for downstream effects of a withdrawal, implemented by the modules that hold the
 * affected data (voice session: audio; processing: queued jobs and feature records; research
 * export: exclusions). Called in the withdrawal's transaction. When no module implements an action
 * that a withdrawal triggers, the response reports it as {@code NOT_APPLICABLE}: nothing exists yet
 * that it could act on.
 */
public interface WithdrawalAction {

	enum Type {
		CANCEL_QUEUED_PROCESSING, EXCLUDE_FROM_RESEARCH_EXPORT, DELETE_RETAINED_AUDIO, FLAG_DERIVED_FEATURES,
		REVOKE_EXTERNAL_REFERRAL
	}

	enum Status {
		SCHEDULED, COMPLETED, NOT_APPLICABLE
	}

	/** Which downstream action this is. */
	Type type();

	/** Applies the action for a withdrawal of {@code withdrawnScopes} (already cascaded) on one event. */
	Status apply(String consentEventId, String participantRef, Set<ConsentScope> withdrawnScopes);

	/** Which actions a withdrawal of these scopes requires (consent-and-data-lifecycle.md section 4). */
	static Set<Type> requiredFor(Set<ConsentScope> withdrawn) {
		java.util.EnumSet<Type> types = java.util.EnumSet.noneOf(Type.class);
		if (withdrawn.contains(ConsentScope.AUDIO_RECORDING)) {
			types.add(Type.CANCEL_QUEUED_PROCESSING);
			types.add(Type.FLAG_DERIVED_FEATURES);
		}
		if (withdrawn.contains(ConsentScope.AUDIO_RECORDING) || withdrawn.contains(ConsentScope.AUDIO_RETENTION)) {
			types.add(Type.DELETE_RETAINED_AUDIO);
		}
		if (withdrawn.contains(ConsentScope.RESEARCH_FEATURE_REUSE) || withdrawn.contains(ConsentScope.MODEL_TRAINING)) {
			types.add(Type.EXCLUDE_FROM_RESEARCH_EXPORT);
		}
		if (withdrawn.contains(ConsentScope.EXTERNAL_REFERRAL_SHARING)) {
			types.add(Type.REVOKE_EXTERNAL_REFERRAL);
		}
		return types;
	}

}
