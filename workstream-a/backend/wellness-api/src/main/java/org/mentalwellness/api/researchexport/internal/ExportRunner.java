package org.mentalwellness.api.researchexport.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The only trigger for an export: a person runs {@code run-export.sh}, which starts the application
 * with {@code wellness.export.run-once=true} and no web server. There is no API endpoint and no
 * schedule, so nothing exports data automatically (CLAUDE.md rule 5).
 */
@Component
@ConditionalOnBooleanProperty("wellness.export.run-once")
class ExportRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(ExportRunner.class);

	private final ExportService service;

	private final ConfigurableApplicationContext context;

	ExportRunner(ExportService service, ConfigurableApplicationContext context) {
		this.service = service;
		this.context = context;
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
		int code = 0;
		try {
			ExportService.Result result = this.service.export("export-runner");
			log.info("research export {} written: {} records, {} withdrawals announced", result.exportId(),
					result.recordCount(), result.withdrawnCount());
		}
		catch (RuntimeException ex) {
			log.error("research export failed ({})", ex.getClass().getSimpleName());
			code = 1;
		}
		int exit = code;
		System.exit(SpringApplication.exit(this.context, () -> exit));
	}

}
