/**
 * Clinical encounters module (ADR-016).
 *
 * <p>Clinic visits, tele-consultations and counselling sessions recorded by facility staff under
 * CLINICAL_CARE consent: validated instruments (scored only by their published rule; never a
 * diagnosis), encrypted clinical notes (off by default), clinician-entered diagnoses, and care plans
 * with follow-up tasks for the ASHA worker. Nothing here reaches research exports.
 */
@ApplicationModule(displayName = "Clinical encounters")
package org.mentalwellness.api.encounters;

import org.springframework.modulith.ApplicationModule;
