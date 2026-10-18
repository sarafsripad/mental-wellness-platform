package org.mentalwellness.api.consent.internal;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.mentalwellness.api.consent.ConsentScope;
import org.mentalwellness.api.consent.internal.ConsentApi.EventStatus;

/** Pure consent rules (ADR-011), kept free of I/O so they can be tested exhaustively. */
final class ConsentRules {

	private ConsentRules() {
	}

	/**
	 * Decisions must cover exactly the scopes the form version offers, each once, and every granted
	 * scope's dependency must also be granted.
	 *
	 * @return true if the decisions are complete and consistent
	 */
	static boolean decisionsValid(Set<ConsentScope> offered, List<ConsentApi.ScopeDecision> decisions) {
		Set<ConsentScope> decided = decisions.stream().map(ConsentApi.ScopeDecision::scope).collect(Collectors.toSet());
		if (decided.size() != decisions.size() || !decided.equals(offered)) {
			return false;
		}
		Set<ConsentScope> granted = granted(decisions);
		return granted.stream().allMatch(s -> s.requires() == null || granted.contains(s.requires()));
	}

	static Set<ConsentScope> granted(List<ConsentApi.ScopeDecision> decisions) {
		EnumSet<ConsentScope> granted = EnumSet.noneOf(ConsentScope.class);
		decisions.stream().filter(ConsentApi.ScopeDecision::granted).forEach(d -> granted.add(d.scope()));
		return granted;
	}

	static Set<ConsentScope> effective(Set<ConsentScope> granted, Set<ConsentScope> withdrawn) {
		EnumSet<ConsentScope> effective = granted.isEmpty() ? EnumSet.noneOf(ConsentScope.class) : EnumSet.copyOf(granted);
		effective.removeAll(withdrawn);
		return effective;
	}

	/** ACTIVE: nothing withdrawn; WITHDRAWN: every granted scope withdrawn; otherwise PARTIALLY_WITHDRAWN. */
	static EventStatus status(Set<ConsentScope> granted, Set<ConsentScope> withdrawn) {
		if (withdrawn.isEmpty()) {
			return EventStatus.ACTIVE;
		}
		return effective(granted, withdrawn).isEmpty() ? EventStatus.WITHDRAWN : EventStatus.PARTIALLY_WITHDRAWN;
	}

	/**
	 * Scopes a withdrawal actually removes: the requested scopes (all granted scopes if none are
	 * named) plus their dependents, limited to what is currently in effect.
	 */
	static Set<ConsentScope> toWithdraw(Set<ConsentScope> requested, Set<ConsentScope> effective) {
		Set<ConsentScope> base = requested == null || requested.isEmpty() ? EnumSet.allOf(ConsentScope.class) : requested;
		EnumSet<ConsentScope> result = EnumSet.noneOf(ConsentScope.class);
		result.addAll(ConsentScope.withDependents(base));
		result.retainAll(effective);
		return result;
	}

}
