package org.mentalwellness.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.mentalwellness.api.support.TestOnlyController;
import org.mentalwellness.api.support.TestSecurityConfiguration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Full application context against a real PostgreSQL (Testcontainers), with the production security
 * chain. Every integration test uses exactly this setup, so they share one context and one container.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = { "spring.flyway.locations=classpath:db/migration,classpath:db/local-seed",
		"wellness.pseudonyms.key=test-only-pseudonym-key-0123456789abcdef" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, TestSecurityConfiguration.class, TestOnlyController.class })
public @interface IntegrationTest {
}
