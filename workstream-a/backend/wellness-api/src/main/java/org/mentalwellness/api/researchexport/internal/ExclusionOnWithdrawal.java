package org.mentalwellness.api.researchexport.internal;

import java.util.Set;

import org.mentalwellness.api.consent.ConsentScope;
import org.mentalwellness.api.consent.WithdrawalAction;
import org.mentalwellness.api.processing.FeatureRecords;

import org.springframework.stereotype.Component;

/**
 * {@code EXCLUDE_FROM_RESEARCH_EXPORT}: exports re-check consent when they are built, so a
 * withdrawal takes effect from the next export, which also tells Workstream B to remove anything
 * it already received. Hence SCHEDULED when records exist for the event.
 */
@Component
class ExclusionOnWithdrawal implements WithdrawalAction {

	private final FeatureRecords records;

	ExclusionOnWithdrawal(FeatureRecords records) {
		this.records = records;
	}

	@Override
	public Type type() {
		return Type.EXCLUDE_FROM_RESEARCH_EXPORT;
	}

	@Override
	public Status apply(String consentEventId, String participantRef, Set<ConsentScope> withdrawnScopes) {
		return this.records.existForConsentEvent(consentEventId) ? Status.SCHEDULED : Status.NOT_APPLICABLE;
	}

}
