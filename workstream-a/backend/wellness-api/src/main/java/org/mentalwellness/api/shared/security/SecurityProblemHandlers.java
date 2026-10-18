package org.mentalwellness.api.shared.security;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.mentalwellness.api.audit.AuditEvent;
import org.mentalwellness.api.audit.AuditLog;
import org.mentalwellness.api.audit.AuditOutcome;
import org.mentalwellness.api.shared.web.CorrelationId;
import org.mentalwellness.api.shared.web.ProblemCode;
import org.mentalwellness.api.shared.web.ProblemResponses;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 401 and 403 responses: the standard bearer-token handlers set the status and
 * {@code WWW-Authenticate} header, then a contract problem body is written. Access denials by an
 * authenticated caller are audited; unauthenticated requests are not, since there is no actor.
 */
class SecurityProblemHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();

	private final BearerTokenAccessDeniedHandler bearerDenied = new BearerTokenAccessDeniedHandler();

	private final ProblemResponses problems;

	private final AuditLog auditLog;

	SecurityProblemHandlers(ProblemResponses problems, AuditLog auditLog) {
		this.problems = problems;
		this.auditLog = auditLog;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
			throws IOException {
		this.bearerEntryPoint.commence(request, response, ex);
		this.problems.write(response, ProblemCode.UNAUTHENTICATED);
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
			throws IOException {
		this.bearerDenied.handle(request, response, ex);
		audit(SecurityContextHolder.getContext().getAuthentication());
		this.problems.write(response, ProblemCode.FORBIDDEN);
	}

	private void audit(Authentication auth) {
		if (auth == null || auth.getName() == null) {
			return;
		}
		Set<String> roles = auth.getAuthorities()
			.stream()
			.map(GrantedAuthority::getAuthority)
			.filter(a -> a.startsWith("ROLE_"))
			.map(a -> a.substring("ROLE_".length()))
			.collect(Collectors.toSet());
		String actor = auth.getName().matches("[A-Za-z0-9_-]{1,64}") ? auth.getName() : "unknown";
		this.auditLog.record(new AuditEvent("ACCESS_DENIED", AuditOutcome.DENIED, actor, roles, null, null, null,
				CorrelationId.current().orElseGet(UUID::randomUUID)));
	}

}
