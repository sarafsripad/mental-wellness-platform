/**
 * Clinic web app module (ADR-016).
 *
 * <p>Serves the local test build of the clinic app for doctors, counsellors and health staff at
 * {@code /clinic/}. Static files only: the page calls the public API with a bearer token like any
 * other client. Off by default.
 */
@ApplicationModule(displayName = "Clinic web app")
package org.mentalwellness.api.clinicapp;

import org.springframework.modulith.ApplicationModule;
