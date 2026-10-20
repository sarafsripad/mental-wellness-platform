package org.mentalwellness.api.cases;

import java.time.Instant;

/** One ASHA visit, coded only, for the care-team timeline (ADR-016). No answers. */
public record CaseSummary(String caseId, String languageTag, Instant openedAt, Instant completedAt, boolean urgentReviewFlag,
		Boolean followUpRequested, String latestDecision, String latestFollowUpWithin, Instant latestReviewedAt) {
}
