package org.mentalwellness.api.shared.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.mentalwellness.api.shared.web.DomainException;
import org.mentalwellness.api.shared.web.ProblemCode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent create operations (contract: {@code Idempotency-Key}). Keys are scoped to (caller,
 * operation). The key row is written in the caller's transaction, so if the operation fails the key
 * is not consumed, and a concurrent duplicate waits on the unique index and then replays the result.
 *
 * <ul>
 * <li>same key, same request: the stored result is returned ({@link Result#replayed()} is true)</li>
 * <li>same key, different request: 409 {@code IDEMPOTENCY_CONFLICT}</li>
 * </ul>
 */
@Service
public class IdempotencyService {

	private final JdbcClient jdbc;

	private final ObjectMapper canonical;

	private final ObjectMapper json;

	public IdempotencyService(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
		this.canonical = json.rebuild()
			.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
			.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
			.build();
	}

	public record Result<T>(T body, boolean replayed) {
	}

	/**
	 * Runs {@code action} once per (principal, operation, key). Must be called inside a transaction
	 * that also contains the operation's writes.
	 *
	 * @param request everything that defines the request (body and path IDs), hashed for comparison
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public <T> Result<T> execute(String principal, String operation, UUID key, Object request, Class<T> responseType,
			Supplier<T> action) {
		String hash = hash(operation, request);
		int inserted = this.jdbc.sql("""
				INSERT INTO idempotency.request_key (principal, operation, idempotency_key, request_hash)
				VALUES (:principal, :operation, :key, :hash)
				ON CONFLICT DO NOTHING
				""")
			.param("principal", principal)
			.param("operation", operation)
			.param("key", key)
			.param("hash", hash)
			.update();
		if (inserted == 0) {
			return replay(principal, operation, key, hash, responseType);
		}
		T body = action.get();
		this.jdbc.sql("""
				UPDATE idempotency.request_key SET response_body = :body, completed_at = now()
				WHERE principal = :principal AND operation = :operation AND idempotency_key = :key
				""")
			.param("body", this.json.writeValueAsString(body))
			.param("principal", principal)
			.param("operation", operation)
			.param("key", key)
			.update();
		return new Result<>(body, false);
	}

	private <T> Result<T> replay(String principal, String operation, UUID key, String hash, Class<T> responseType) {
		record Stored(String requestHash, String responseBody) {
		}
		Optional<Stored> stored = this.jdbc.sql("""
				SELECT request_hash, response_body FROM idempotency.request_key
				WHERE principal = :principal AND operation = :operation AND idempotency_key = :key
				""")
			.param("principal", principal)
			.param("operation", operation)
			.param("key", key)
			.query(Stored.class)
			.optional();
		if (stored.isEmpty() || !stored.get().requestHash().equals(hash)) {
			throw new DomainException(ProblemCode.IDEMPOTENCY_CONFLICT);
		}
		if (stored.get().responseBody() == null) {
			throw new DomainException(ProblemCode.RESOURCE_CONFLICT); // still in progress elsewhere
		}
		return new Result<>(this.json.readValue(stored.get().responseBody(), responseType), true);
	}

	private String hash(String operation, Object request) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(operation.getBytes(StandardCharsets.UTF_8));
			digest.update((byte) 0);
			digest.update(this.canonical.writeValueAsBytes(request));
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
