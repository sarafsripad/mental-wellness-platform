package org.mentalwellness.api.consent.internal;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mentalwellness.api.consent.ConsentScope;
import org.mentalwellness.api.consent.WithdrawalAction;
import org.mentalwellness.api.consent.internal.ConsentApi.EventStatus;
import org.mentalwellness.api.consent.internal.ConsentApi.ScopeDecision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mentalwellness.api.consent.ConsentScope.AUDIO_RECORDING;
import static org.mentalwellness.api.consent.ConsentScope.AUDIO_RETENTION;
import static org.mentalwellness.api.consent.ConsentScope.MODEL_TRAINING;
import static org.mentalwellness.api.consent.ConsentScope.QUALITY_REVIEW;
import static org.mentalwellness.api.consent.ConsentScope.RESEARCH_FEATURE_REUSE;
import static org.mentalwellness.api.consent.ConsentScope.SCREENING_PARTICIPATION;

class ConsentRulesTests {

	private static final Set<ConsentScope> ALL = EnumSet.allOf(ConsentScope.class);

	private static List<ScopeDecision> decide(Set<ConsentScope> offered, Set<ConsentScope> granted) {
		return offered.stream().map(s -> new ScopeDecision(s, granted.contains(s))).toList();
	}

	@Test
	void dependencyGraphMatchesTheApprovedModel() {
		assertThat(SCREENING_PARTICIPATION.requires()).isNull();
		assertThat(AUDIO_RECORDING.requires()).isEqualTo(SCREENING_PARTICIPATION);
		assertThat(AUDIO_RETENTION.requires()).isEqualTo(AUDIO_RECORDING);
		assertThat(RESEARCH_FEATURE_REUSE.requires()).isEqualTo(AUDIO_RECORDING);
		assertThat(QUALITY_REVIEW.requires()).isEqualTo(AUDIO_RECORDING);
		assertThat(MODEL_TRAINING.requires()).isEqualTo(RESEARCH_FEATURE_REUSE);
	}

	@Test
	void completeConsistentDecisionsAreValid() {
		assertThat(ConsentRules.decisionsValid(ALL, decide(ALL, ALL))).isTrue();
		assertThat(ConsentRules.decisionsValid(ALL, decide(ALL, Set.of()))).as("declining everything is a valid record").isTrue();
		assertThat(ConsentRules.decisionsValid(ALL, decide(ALL, Set.of(SCREENING_PARTICIPATION, AUDIO_RECORDING)))).isTrue();
	}

	@Test
	void missingExtraOrDuplicateDecisionsAreInvalid() {
		Set<ConsentScope> basic = EnumSet.of(SCREENING_PARTICIPATION, AUDIO_RECORDING);
		assertThat(ConsentRules.decisionsValid(ALL, decide(basic, basic))).as("missing decisions").isFalse();
		assertThat(ConsentRules.decisionsValid(basic, decide(ALL, basic))).as("scopes the form doesn't offer").isFalse();
		var duplicated = new java.util.ArrayList<>(decide(basic, basic));
		duplicated.add(new ScopeDecision(AUDIO_RECORDING, false));
		assertThat(ConsentRules.decisionsValid(basic, duplicated)).as("duplicate scope").isFalse();
	}

	@ParameterizedTest
	@EnumSource(value = ConsentScope.class, names = { "SCREENING_PARTICIPATION", "CLINICAL_CARE" }, mode = EnumSource.Mode.EXCLUDE)
	void grantingAScopeWithoutItsDependencyIsInvalid(ConsentScope scope) {
		Set<ConsentScope> granted = EnumSet.copyOf(ALL);
		granted.remove(scope.requires());
		assertThat(ConsentRules.decisionsValid(ALL, decide(ALL, granted))).isFalse();
	}

	@Test
	void withdrawalCascadesToDependents() {
		assertThat(ConsentRules.toWithdraw(Set.of(AUDIO_RECORDING), ALL))
			.containsExactlyInAnyOrder(AUDIO_RECORDING, AUDIO_RETENTION, RESEARCH_FEATURE_REUSE, MODEL_TRAINING, QUALITY_REVIEW);
		assertThat(ConsentRules.toWithdraw(Set.of(RESEARCH_FEATURE_REUSE), ALL))
			.containsExactlyInAnyOrder(RESEARCH_FEATURE_REUSE, MODEL_TRAINING);
		// Everything that depends on screening; clinical care is a separate consent (ADR-016).
		Set<ConsentScope> dependents = EnumSet.copyOf(ALL);
		dependents.remove(ConsentScope.CLINICAL_CARE);
		assertThat(ConsentRules.toWithdraw(Set.of(SCREENING_PARTICIPATION), ALL)).isEqualTo(dependents);
		assertThat(ConsentRules.toWithdraw(null, ALL)).as("no scopes named = withdraw all").isEqualTo(ALL);
		assertThat(ConsentRules.toWithdraw(Set.of(MODEL_TRAINING), ALL)).containsExactly(MODEL_TRAINING);
	}

	@Test
	void withdrawalOnlyRemovesWhatIsInEffect() {
		Set<ConsentScope> effective = EnumSet.of(SCREENING_PARTICIPATION, AUDIO_RECORDING);
		assertThat(ConsentRules.toWithdraw(Set.of(AUDIO_RECORDING), effective)).containsExactly(AUDIO_RECORDING);
		assertThat(ConsentRules.toWithdraw(Set.of(MODEL_TRAINING), effective)).as("never granted: no-op").isEmpty();
	}

	@Test
	void statusFollowsHistory() {
		Set<ConsentScope> granted = EnumSet.of(SCREENING_PARTICIPATION, AUDIO_RECORDING);
		assertThat(ConsentRules.status(granted, Set.of())).isEqualTo(EventStatus.ACTIVE);
		assertThat(ConsentRules.status(granted, Set.of(AUDIO_RECORDING))).isEqualTo(EventStatus.PARTIALLY_WITHDRAWN);
		assertThat(ConsentRules.status(granted, granted)).isEqualTo(EventStatus.WITHDRAWN);
	}

	@Test
	void downstreamActionsFollowTheLifecycleDocument() {
		assertThat(WithdrawalAction.requiredFor(Set.of(AUDIO_RECORDING))).containsExactlyInAnyOrder(
				WithdrawalAction.Type.CANCEL_QUEUED_PROCESSING, WithdrawalAction.Type.FLAG_DERIVED_FEATURES,
				WithdrawalAction.Type.DELETE_RETAINED_AUDIO);
		assertThat(WithdrawalAction.requiredFor(Set.of(AUDIO_RETENTION))).containsExactly(WithdrawalAction.Type.DELETE_RETAINED_AUDIO);
		assertThat(WithdrawalAction.requiredFor(Set.of(RESEARCH_FEATURE_REUSE, MODEL_TRAINING)))
			.containsExactly(WithdrawalAction.Type.EXCLUDE_FROM_RESEARCH_EXPORT);
		assertThat(WithdrawalAction.requiredFor(Set.of())).isEmpty();
	}

}
