/**
 * Identity module.
 *
 * <p>Direct identifiers (name, village), held in identity_db only (ADR-010, ADR-013). Write-only:
 * {@code identity-api.v1.yaml} stores them, nothing returns them. No other module may depend on
 * this module, and it is only active in the web application, so the research export process never
 * opens an identity connection.
 */
@ApplicationModule(displayName = "Identity")
package org.mentalwellness.api.identity;

import org.springframework.modulith.ApplicationModule;
