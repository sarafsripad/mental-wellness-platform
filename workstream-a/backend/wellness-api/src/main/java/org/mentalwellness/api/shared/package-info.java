/**
 * Shared web and security infrastructure used by every module: correlation IDs, RFC 9457 problem
 * responses and OIDC bearer-token security. Contains no domain logic.
 */
@ApplicationModule(displayName = "Shared infrastructure", type = ApplicationModule.Type.OPEN)
package org.mentalwellness.api.shared;

import org.springframework.modulith.ApplicationModule;
