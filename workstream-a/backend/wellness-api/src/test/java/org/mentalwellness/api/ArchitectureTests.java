package org.mentalwellness.api;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.USE_JAVA_UTIL_LOGGING;

/** Project rules that complement the module boundaries checked in {@link ModularityTests}. */
class ArchitectureTests {

	static final JavaClasses MAIN = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
		.importPackages("org.mentalwellness.api");

	@Test
	void noPrintingToStandardStreams() {
		noClasses().should(ACCESS_STANDARD_STREAMS).because("logs must be structured and free of request data").check(MAIN);
	}

	@Test
	void noJavaUtilLogging() {
		noClasses().should(USE_JAVA_UTIL_LOGGING).check(MAIN);
	}

	@Test
	void noFieldInjection() {
		noFields().should()
			.beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
			.because("constructor injection keeps dependencies explicit and testable")
			.allowEmptyShould(true)
			.check(MAIN);
	}

	@Test
	void onlyTheIdentityModuleTouchesIdentity() {
		noClasses().that()
			.resideOutsideOfPackage("org.mentalwellness.api.identity..")
			.should()
			.dependOnClassesThat()
			.resideInAPackage("org.mentalwellness.api.identity..")
			.because("direct identifiers stay in the identity module (ADR-010, CLAUDE.md rule 8)")
			.allowEmptyShould(true)
			.check(MAIN);
	}

	@Test
	void researchExportNeverReachesIdentity() {
		noClasses().that()
			.resideInAPackage("org.mentalwellness.api.researchexport..")
			.should()
			.dependOnClassesThat()
			.resideInAPackage("org.mentalwellness.api.identity..")
			.allowEmptyShould(true)
			.check(MAIN);
	}

}
