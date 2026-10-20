package org.mentalwellness.api.programme.internal;

import java.time.LocalDate;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Support contacts from programme configuration ({@code wellness.programme.support-contacts}).
 * consent-and-data-lifecycle.md §13: the clinical lead must verify each contact for the programme
 * area. Until then {@code verified-by} is {@code PENDING_CLINICAL_LEAD} and the apps label it.
 */
@ConfigurationProperties("wellness.programme")
record SupportContactProperties(List<Contact> supportContacts) {

	static final String PENDING = "PENDING_CLINICAL_LEAD";

	SupportContactProperties {
		supportContacts = supportContacts == null ? List.of() : List.copyOf(supportContacts);
	}

	enum Coverage { NATIONAL, STATE, DISTRICT }

	/**
	 * @param id         short stable code, e.g. {@code tele-manas}
	 * @param name       display name
	 * @param phone      number as dialled, digits with optional leading +, spaces or hyphens
	 * @param hours      when it answers, e.g. {@code 24x7}
	 * @param coverage   area it serves
	 * @param verifiedBy who verified it for this programme area, or {@code PENDING_CLINICAL_LEAD}
	 * @param verifiedOn when; null while pending
	 */
	record Contact(String id, String name, String phone, String hours, Coverage coverage, String verifiedBy,
			LocalDate verifiedOn) {

		Contact {
			if (id == null || !id.matches("^[a-z0-9-]{2,40}$")) {
				throw new IllegalArgumentException("support contact id must match ^[a-z0-9-]{2,40}$");
			}
			if (name == null || name.isBlank() || name.length() > 80) {
				throw new IllegalArgumentException("support contact " + id + ": name is required (max 80)");
			}
			if (phone == null || !phone.matches("^\\+?[0-9][0-9 -]{2,19}$")) {
				throw new IllegalArgumentException("support contact " + id + ": phone must be digits, spaces or hyphens");
			}
			if (coverage == null) {
				throw new IllegalArgumentException("support contact " + id + ": coverage is required");
			}
			if (verifiedBy == null || verifiedBy.isBlank()) {
				verifiedBy = PENDING;
			}
			if (!PENDING.equals(verifiedBy) && verifiedOn == null) {
				throw new IllegalArgumentException("support contact " + id + ": verified-on is required once verified");
			}
		}

		boolean verified() {
			return !PENDING.equals(this.verifiedBy);
		}

	}

}
