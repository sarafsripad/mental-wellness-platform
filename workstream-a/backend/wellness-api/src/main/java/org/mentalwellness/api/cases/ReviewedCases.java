package org.mentalwellness.api.cases;

import java.util.Optional;

/** Read-only view of reviewed cases for the integration module (ADR-015). */
public interface ReviewedCases {

	Optional<ReferralBasis> find(String caseId);

}
