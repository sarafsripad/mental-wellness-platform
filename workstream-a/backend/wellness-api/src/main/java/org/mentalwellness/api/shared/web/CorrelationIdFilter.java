package org.mentalwellness.api.shared.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds a correlation ID to every request before security runs, so 401 and 403 responses carry one
 * too. A valid client UUID is reused; a missing or malformed value is replaced by a new UUID rather
 * than reflected. The ID is echoed in the response header and put in the logging MDC.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		// On an error dispatch, reuse the ID already assigned to the original request.
		UUID id = request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE) instanceof UUID existing ? existing
				: CorrelationId.parse(request.getHeader(CorrelationId.HEADER)).orElseGet(UUID::randomUUID);
		request.setAttribute(CorrelationId.REQUEST_ATTRIBUTE, id);
		MDC.put(CorrelationId.MDC_KEY, id.toString());
		response.setHeader(CorrelationId.HEADER, id.toString());
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(CorrelationId.MDC_KEY);
		}
	}

	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		// Also run when the container forwards to /error, so the error body carries the same ID.
		return false;
	}

}
