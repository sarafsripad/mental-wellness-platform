package org.mentalwellness.api.shared.web;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;

/**
 * Access to the correlation ID of the current request (contracts: {@code X-Correlation-ID}).
 * Set by {@link CorrelationIdFilter} for every request, including rejected ones.
 */
public final class CorrelationId {

	public static final String HEADER = "X-Correlation-ID";
	public static final String MDC_KEY = "correlation_id";
	static final String REQUEST_ATTRIBUTE = CorrelationId.class.getName();

	private CorrelationId() {
	}

	/** The current request's correlation ID, if one is bound to this thread. */
	public static Optional<UUID> current() {
		String value = MDC.get(MDC_KEY);
		return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
	}

	/** Parses a client-supplied value; anything that is not a UUID is rejected (never reflected). */
	static Optional<UUID> parse(String value) {
		if (value == null || value.length() != 36) {
			return Optional.empty();
		}
		try {
			UUID uuid = UUID.fromString(value);
			return uuid.toString().equalsIgnoreCase(value) ? Optional.of(uuid) : Optional.empty();
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

}
