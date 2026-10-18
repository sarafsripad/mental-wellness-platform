/**
 * Cases module.
 *
 * <p>ASHA assessment visits (API 1.1, ADR-013): coded registration answers, health and lifestyle
 * observations, questionnaire answers and the follow-up decision. Never names, places or free
 * text; direct identifiers belong to the identity module. Answers are stored, never scored.
 */
@ApplicationModule(displayName = "Cases")
package org.mentalwellness.api.cases;

import org.springframework.modulith.ApplicationModule;
