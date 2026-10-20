package org.mentalwellness.api.integration.internal;

import java.nio.file.Path;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The only trigger for an HMIS report: a person runs {@code run-hmis-report.sh} (no endpoint, no
 * schedule). Reports the month in {@code wellness.hmis.month} (yyyy-MM), or the current UTC month.
 */
@Component
@ConditionalOnBooleanProperty("wellness.hmis.run-once")
class HmisReportRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(HmisReportRunner.class);

	private final HmisReports reports;

	private final ConfigurableApplicationContext context;

	private final String month;

	private final String dir;

	private final Clock clock;

	HmisReportRunner(HmisReports reports, ConfigurableApplicationContext context, @Value("${wellness.hmis.month:}") String month,
			@Value("${wellness.hmis.dir}") String dir, Clock clock) {
		this.reports = reports;
		this.context = context;
		this.month = month;
		this.dir = dir;
		this.clock = clock;
	}

	@Override
	public void run(ApplicationArguments args) {
		int code = 0;
		try {
			YearMonth period = this.month.isBlank() ? YearMonth.now(this.clock.withZone(ZoneOffset.UTC)) : YearMonth.parse(this.month);
			HmisReports.Result result = this.reports.run(period, Path.of(this.dir));
			log.info("HMIS report {} for {} written to {} (simulated HMIS delivery: {})", result.reportId(), period,
					result.file().getFileName(), result.delivery());
		}
		catch (Exception ex) {
			log.error("HMIS report failed ({})", ex.getClass().getSimpleName());
			code = 1;
		}
		int exit = code;
		System.exit(SpringApplication.exit(this.context, () -> exit));
	}

}
