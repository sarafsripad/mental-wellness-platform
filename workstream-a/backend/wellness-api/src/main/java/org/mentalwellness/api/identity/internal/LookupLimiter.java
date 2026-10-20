package org.mentalwellness.api.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caps identity lookups and searches per staff member (a sliding window, per API instance). A
 * person looking up many patients in a short time is refused with 429 and shows up in the audit
 * trail; the production WAF adds a cluster-wide limit (production document §11).
 */
class LookupLimiter {

	private final int limit;

	private final Duration window;

	private final Clock clock;

	private final Map<String, Deque<Instant>> calls = new ConcurrentHashMap<>();

	LookupLimiter(int limit, Duration window, Clock clock) {
		this.limit = limit;
		this.window = window;
		this.clock = clock;
	}

	/** Records the call and returns false if the subject is over the limit. */
	boolean tryAcquire(String subject) {
		Instant now = this.clock.instant();
		Deque<Instant> q = this.calls.computeIfAbsent(subject, s -> new ArrayDeque<>());
		synchronized (q) {
			while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(this.window))) {
				q.pollFirst();
			}
			if (q.size() >= this.limit) {
				return false;
			}
			q.addLast(now);
			return true;
		}
	}

}
