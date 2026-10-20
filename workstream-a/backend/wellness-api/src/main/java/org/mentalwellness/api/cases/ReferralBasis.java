package org.mentalwellness.api.cases;

import java.time.Instant;

/**
 * What another module may know about a reviewed case in order to refer it (ADR-015): pseudonymous
 * references and coded facts only, never answers, names or places.
 *
 * @param latestReviewId       the latest clinician review, or null
 * @param latestDecision       its decision code ({@code REFER}, {@code URGENT_REFER}, ...), or null
 * @param latestFollowUpWithin its timeframe code, or null
 * @param urgentReviewFlag     raised by the safety question
 * @param eligibleForReview    flagged and SCREENING_PARTICIPATION still in effect
 */
public record ReferralBasis(String caseId, String participantRef, String consentEventId, String languageTag,
		String dataOrigin, boolean urgentReviewFlag, boolean followUpRequested, String latestReviewId, String latestDecision, String latestFollowUpWithin,
		Instant latestReviewedAt, boolean eligibleForReview) {

	public boolean referable() {
		return this.eligibleForReview && ("REFER".equals(this.latestDecision) || "URGENT_REFER".equals(this.latestDecision));
	}

}
