/**
 * Identity module.
 *
 * <p>Direct identifiers, held in identity_db only (ADR-010). No other module may depend on this module's internals. Not used in the first slice.
 */
@ApplicationModule(displayName = "Identity")
package org.mentalwellness.api.identity;

import org.springframework.modulith.ApplicationModule;
