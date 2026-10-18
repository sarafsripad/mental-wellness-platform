/**
 * Local browser test console (development only). Serves a static page at {@code /dev-console/}
 * when {@code wellness.dev-console.enabled=true}, which only run-local.sh sets. The page holds no
 * data: it logs in through Keycloak (PKCE) and calls the normal API with the user's token.
 */
@ApplicationModule(displayName = "Dev console (local only)")
package org.mentalwellness.api.devconsole;

import org.springframework.modulith.ApplicationModule;
