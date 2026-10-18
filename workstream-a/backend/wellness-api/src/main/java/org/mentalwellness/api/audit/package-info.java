/**
 * Audit module: append-only audit events (solution-architecture.md section 10).
 *
 * <p>Other modules record events through {@link org.mentalwellness.api.audit.AuditLog}. Events carry
 * pseudonymous IDs, codes and outcomes only, never request bodies, names, audio or feature values.
 * The database rejects updates, deletes and truncation of audit rows.
 */
@ApplicationModule(displayName = "Audit")
package org.mentalwellness.api.audit;

import org.springframework.modulith.ApplicationModule;
