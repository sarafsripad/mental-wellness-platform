package org.mentalwellness.api.cases;

import java.time.YearMonth;
import java.util.List;

/**
 * Aggregate counts for programme reporting (ADR-015). Counts only: no IDs, dates finer than the
 * month, or locality. Callers must apply small-cell suppression before anything leaves the platform.
 */
public interface CaseStatistics {

	record Counts(String ageBand, String gender, long visits, long urgentFlags, long followUpRequests, long reviewed) {
	}

	/**
	 * Visits opened in {@code month} (UTC), grouped by age band and gender.
	 *
	 * @param dataOrigin {@code SYNTHETIC} or {@code PARTICIPANT}; reports never mix the two
	 */
	List<Counts> monthly(YearMonth month, String dataOrigin);

}
