/**
 * Integration module (ADR-015).
 *
 * <p>Partner programmes such as Tele-MANAS, eSanjeevani, ABDM and State HMIS. It sends pseudonymous
 * referrals that a clinician chooses to send, through a reliable outbox, receives coded status
 * events back, and revokes referrals when consent is withdrawn. No real partner is connected: only
 * SIMULATED partners on this machine can be enabled until a data-sharing agreement and a named
 * approval exist (docs/integration/partner-onboarding.md).
 */
@ApplicationModule(displayName = "Integration")
package org.mentalwellness.api.integration;

import org.springframework.modulith.ApplicationModule;
