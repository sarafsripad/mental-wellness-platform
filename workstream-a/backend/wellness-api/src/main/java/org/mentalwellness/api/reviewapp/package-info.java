/**
 * Clinician review dashboard module (ADR-014).
 *
 * <p>Serves the local test build of the clinician review dashboard at {@code /review/}. Static
 * files only: the page calls the public API with a bearer token like any other client. Off by
 * default.
 */
@ApplicationModule(displayName = "Clinician review dashboard")
package org.mentalwellness.api.reviewapp;

import org.springframework.modulith.ApplicationModule;
