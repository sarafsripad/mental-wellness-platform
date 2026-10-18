/**
 * ASHA Care web app module (ADR-013).
 *
 * <p>Serves the local test build of the ASHA field-worker app at {@code /asha/}. Static files only:
 * the app talks to the public API with a bearer token like any other client. Off by default.
 */
@ApplicationModule(displayName = "ASHA Care web app")
package org.mentalwellness.api.ashaapp;

import org.springframework.modulith.ApplicationModule;
