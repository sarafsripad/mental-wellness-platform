package org.mentalwellness.api.researchexport.internal;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Research export settings ({@code wellness.export.*}).
 *
 * @param dir     one-way drop zone that Workstream B reads (a directory locally; a separate bucket later)
 * @param key     HMAC key for re-keying IDs; stays in the application environment; required to export
 * @param runOnce run one export at startup and exit (the only way an export is triggered)
 */
@ConfigurationProperties("wellness.export")
record ExportProperties(Path dir, String key, boolean runOnce) {
}
