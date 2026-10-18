package org.mentalwellness.api.shared.web;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps exceptions to contract problem responses. Messages from exceptions are never sent to the
 * client, and are not logged either, because they can echo request content.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(BindException.class) // includes MethodArgumentNotValidException
	ResponseEntity<ApiProblem> invalidBody(BindException ex) {
		Class<?> target = ex.getTarget() == null ? null : ex.getTarget().getClass();
		List<ApiProblem.FieldError> errors = ex.getFieldErrors()
			.stream()
			.map(e -> new ApiProblem.FieldError(pointer(target, e.getField()), rule(e.getCode())))
			.toList();
		return problem(ProblemCode.VALIDATION_FAILED, errors);
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	ResponseEntity<ApiProblem> invalidParameters(HandlerMethodValidationException ex) {
		List<ApiProblem.FieldError> errors = ex.getParameterValidationResults()
			.stream()
			.flatMap(r -> r.getResolvableErrors()
				.stream()
				.map(e -> new ApiProblem.FieldError("/" + r.getMethodParameter().getParameterName(),
						rule(e.getCodes() == null ? null : e.getCodes()[e.getCodes().length - 1]))))
			.toList();
		return problem(ProblemCode.VALIDATION_FAILED, errors);
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
			MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class })
	ResponseEntity<ApiProblem> unreadable(Exception ex) {
		return problem(ProblemCode.VALIDATION_FAILED, List.of());
	}

	@ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
	ResponseEntity<ApiProblem> notFound(Exception ex) {
		return problem(ProblemCode.NOT_FOUND, List.of());
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ApiProblem> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
		return problem(ProblemCode.METHOD_NOT_ALLOWED, List.of());
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ApiProblem> unsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
		return problem(ProblemCode.UNSUPPORTED_MEDIA_TYPE, List.of());
	}

	@ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
	ResponseEntity<ApiProblem> notAcceptable(HttpMediaTypeNotAcceptableException ex) {
		return problem(ProblemCode.NOT_ACCEPTABLE, List.of());
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	ResponseEntity<ApiProblem> tooLarge(MaxUploadSizeExceededException ex) {
		return problem(ProblemCode.PAYLOAD_TOO_LARGE, List.of());
	}

	/** Let Spring Security's filters produce 401/403 (with WWW-Authenticate and auditing). */
	@ExceptionHandler({ AccessDeniedException.class, AuthenticationException.class })
	void rethrowSecurity(RuntimeException ex) {
		throw ex;
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiProblem> unexpected(Exception ex) {
		log.error("Unhandled exception", withoutMessage(ex));
		return problem(ProblemCode.INTERNAL_ERROR, List.of());
	}

	private static ResponseEntity<ApiProblem> problem(ProblemCode code, List<ApiProblem.FieldError> errors) {
		UUID correlationId = CorrelationId.current().orElseGet(UUID::randomUUID);
		return ResponseEntity.status(code.status())
			.contentType(ProblemResponses.PROBLEM_JSON)
			.body(ApiProblem.of(code, correlationId, errors));
	}

	/** JSON Pointer using the JSON property names clients send (from {@code @JsonProperty}), not Java names. */
	static String pointer(Class<?> type, String fieldPath) {
		StringBuilder pointer = new StringBuilder();
		Class<?> current = type;
		for (String segment : fieldPath.split("\\.")) {
			String name = segment;
			Field field = current == null ? null : findField(current, segment.replaceAll("\\[.*", ""));
			if (field != null) {
				JsonProperty property = field.getAnnotation(JsonProperty.class);
				if (property != null && !property.value().isEmpty()) {
					name = property.value() + segment.substring(segment.replaceAll("\\[.*", "").length());
				}
				current = field.getType();
			}
			else {
				current = null;
			}
			pointer.append('/').append(name.replace("[", "/").replace("]", ""));
		}
		return pointer.toString();
	}

	private static Field findField(Class<?> type, String name) {
		for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField(name);
			}
			catch (NoSuchFieldException ex) {
				// try the superclass
			}
		}
		return null;
	}

	private static String rule(String code) {
		return code == null ? "invalid" : Character.toLowerCase(code.charAt(0)) + code.substring(1);
	}

	/** Same class and stack trace, but without messages, which may contain request data. */
	static Throwable withoutMessage(Throwable ex) {
		Throwable copy = new RedactedException(ex.getClass().getName());
		copy.setStackTrace(ex.getStackTrace());
		if (ex.getCause() != null && ex.getCause() != ex) {
			copy.initCause(withoutMessage(ex.getCause()));
		}
		return copy;
	}

	static final class RedactedException extends RuntimeException {

		RedactedException(String originalClassName) {
			super(originalClassName + " (message redacted)");
		}

	}

}
