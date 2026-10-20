package org.mentalwellness.api.programme.internal;

import java.time.LocalDate;
import java.util.List;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /v1/programme/support-contacts} of wellness-api.v1.yaml (1.3). No participant data. */
@RestController
@EnableConfigurationProperties(SupportContactProperties.class)
class SupportContactController {

	private final SupportContactList list;

	SupportContactController(SupportContactProperties properties) {
		this.list = new SupportContactList(properties.supportContacts()
			.stream()
			.map(c -> new SupportContact(c.id(), c.name(), c.phone(), "tel:" + c.phone().replaceAll("[ -]", ""), c.hours(),
					c.coverage(), c.verified(), c.verified() ? c.verifiedBy() : null, c.verifiedOn()))
			.toList());
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record SupportContact(String contactId, String serviceName, String dialNumber, String telUri, String hours,
			SupportContactProperties.Coverage coverage, boolean verified, String verifiedBy, LocalDate verifiedOn) {
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	record SupportContactList(List<SupportContact> contacts) {
	}

	@GetMapping("/v1/programme/support-contacts")
	@PreAuthorize("hasAnyRole('ASHA_WORKER', 'CLINICIAN') and (hasAuthority('SCOPE_cases:read') or hasAuthority('SCOPE_review:read'))")
	SupportContactList contacts() {
		return this.list;
	}

}
