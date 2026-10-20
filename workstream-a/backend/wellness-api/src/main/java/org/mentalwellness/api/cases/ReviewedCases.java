package org.mentalwellness.api.cases;

import java.util.Optional;

/** Read-only view of reviewed cases for the integration module (ADR-015). */
public interface ReviewedCases {

	Optional<ReferralBasis> find(String caseId);

	/** The participant's ASHA visits, oldest first, as coded summaries (for the care-team timeline). */
	java.util.List<CaseSummary> forParticipant(String participantRef);

}
