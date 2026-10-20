/**
 * Care team module (ADR-016).
 *
 * <p>Facilities, the staff who work in them, and which facilities are on each participant's care
 * team. Answers one question for every other module: may this person act on this participant in
 * this role? The ASHA worker's own link (consent's first-consent assignment) is unchanged; facility
 * staff are added on top of it.
 */
@ApplicationModule(displayName = "Care teams")
package org.mentalwellness.api.careteam;

import org.springframework.modulith.ApplicationModule;
