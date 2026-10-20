package org.mentalwellness.api.cases.internal;

import java.sql.Timestamp;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import org.mentalwellness.api.cases.CaseStatistics;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class CaseStatisticsQuery implements CaseStatistics {

	private final JdbcClient jdbc;

	CaseStatisticsQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Counts> monthly(YearMonth month, String dataOrigin) {
		return this.jdbc.sql("""
				SELECT c.age_band, c.gender, count(*),
				  count(*) FILTER (WHERE c.urgent_review_flag),
				  count(*) FILTER (WHERE c.follow_up_requested),
				  count(*) FILTER (WHERE EXISTS (SELECT 1 FROM cases.case_review r WHERE r.case_id = c.case_id))
				FROM cases.assessment_case c
				WHERE c.opened_at >= ? AND c.opened_at < ? AND c.data_origin = ?
				GROUP BY c.age_band, c.gender
				ORDER BY c.age_band, c.gender
				""")
			.params(Timestamp.from(month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()),
					Timestamp.from(month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()), dataOrigin)
			.query((rs, n) -> new Counts(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
					rs.getLong(6)))
			.list();
	}

}
