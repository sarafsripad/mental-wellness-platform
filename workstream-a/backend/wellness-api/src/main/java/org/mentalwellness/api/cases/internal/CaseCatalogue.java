package org.mentalwellness.api.cases.internal;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mentalwellness.api.cases.internal.CaseApi.ObservationCode;
import org.mentalwellness.api.shared.web.DomainException;

/**
 * The coded catalogues of API 1.1, fixed in code so they change only with a reviewed release.
 *
 * <p>{@code illustrative-wellness} 0.1.0 comes from the ASHA prototype. It is NOT VALIDATED and not
 * licensed for screening, so cases accept SYNTHETIC data only (ADR-013). Nothing here scores
 * answers: the only rule is that a self-harm answer of SOMETIMES or OFTEN asks for human follow-up.
 */
final class CaseCatalogue {

	private CaseCatalogue() {
	}

	static final String OBSERVATIONS_ID = "asha-observations";
	static final String OBSERVATIONS_VERSION = "0.1.0";
	static final String INSTRUMENT_ID = "illustrative-wellness";
	static final String INSTRUMENT_VERSION = "0.1.0";

	static final String SAFETY_ITEM = "SELF_HARM_THOUGHTS";
	static final Set<String> URGENT_ANSWERS = Set.of("SOMETIMES", "OFTEN");

	private static final Map<ObservationCode, Set<String>> OBSERVATION_VALUES = Map.of(
			ObservationCode.HEALTH_CONDITION, Set.of("HYPERTENSION", "DIABETES", "HEART_DISEASE", "BREATHING_PROBLEM",
					"OTHER_LONG_TERM_ILLNESS", "NONE_REPORTED", "DECLINED"),
			ObservationCode.TOBACCO_USE, Set.of("NOT_REPORTED", "REPORTED", "DECLINED"),
			ObservationCode.ALCOHOL_USE, Set.of("NOT_REPORTED", "REPORTED", "DECLINED"),
			ObservationCode.SLEEP_QUALITY, Set.of("GOOD", "SOME_DIFFICULTY", "VERY_POOR", "DECLINED"));

	/** HEALTH_CONDITION values that must stand alone. */
	private static final Set<String> EXCLUSIVE_HEALTH = Set.of("NONE_REPORTED", "DECLINED");

	private static final Set<String> FREQUENCY = Set.of("NOT_AT_ALL", "SEVERAL_DAYS", "MORE_THAN_HALF_THE_DAYS",
			"NEARLY_EVERY_DAY", "DECLINED");

	private static final Map<String, Set<String>> ITEMS = Map.of(
			"ANXIETY", FREQUENCY,
			"LOW_MOOD", FREQUENCY,
			"INTEREST", Set.of("AS_USUAL", "LESS_THAN_BEFORE", "NOT_AT_ALL", "DECLINED"),
			SAFETY_ITEM, Set.of("NO", "SOMETIMES", "OFTEN", "DECLINED"));

	/** @throws DomainException 400 with the offending pointer; values are never echoed */
	static void checkObservations(CaseApi.ObservationSetCreate request) {
		if (!OBSERVATIONS_ID.equals(request.catalogue().catalogueId())
				|| !OBSERVATIONS_VERSION.equals(request.catalogue().catalogueVersion())) {
			throw DomainException.invalid("/catalogue", "unknownCatalogue");
		}
		List<CaseApi.Observation> observations = request.observations();
		Set<String> seen = new HashSet<>();
		Set<ObservationCode> singles = new HashSet<>();
		Set<String> health = new HashSet<>();
		for (int i = 0; i < observations.size(); i++) {
			CaseApi.Observation o = observations.get(i);
			if (!OBSERVATION_VALUES.get(o.code()).contains(o.valueCode())) {
				throw DomainException.invalid("/observations/" + i + "/value_code", "enum");
			}
			if (!seen.add(o.code() + "=" + o.valueCode())) {
				throw DomainException.invalid("/observations", "uniqueItems");
			}
			if (o.code() == ObservationCode.HEALTH_CONDITION) {
				health.add(o.valueCode());
			}
			else if (!singles.add(o.code())) {
				throw DomainException.invalid("/observations/" + i + "/code", "singleValue");
			}
		}
		if (health.size() > 1 && health.stream().anyMatch(EXCLUSIVE_HEALTH::contains)) {
			throw DomainException.invalid("/observations", "exclusiveValue");
		}
	}

	/** @throws DomainException 400 with the offending pointer; values are never echoed */
	static void checkAnswers(CaseApi.QuestionnaireResponseCreate request) {
		if (!INSTRUMENT_ID.equals(request.instrument().instrumentId())
				|| !INSTRUMENT_VERSION.equals(request.instrument().instrumentVersion())) {
			throw DomainException.invalid("/instrument", "unknownInstrument");
		}
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < request.items().size(); i++) {
			CaseApi.Answer a = request.items().get(i);
			Set<String> answers = ITEMS.get(a.itemId());
			if (answers == null) {
				throw DomainException.invalid("/items/" + i + "/item_id", "enum");
			}
			if (!answers.contains(a.answerCode())) {
				throw DomainException.invalid("/items/" + i + "/answer_code", "enum");
			}
			if (!seen.add(a.itemId())) {
				throw DomainException.invalid("/items", "uniqueItemIds");
			}
		}
		if (!seen.contains(SAFETY_ITEM)) {
			throw DomainException.invalid("/items", "safetyItemRequired");
		}
	}

	/** No instrument in this release is validated; reviewers are shown this alongside the answers. */
	static boolean validated(String instrumentId, String instrumentVersion) {
		return false;
	}

	/** Timeframes that fit each decision (also a database check constraint). */
	static boolean consistent(CaseApi.ReviewDecision decision, CaseApi.FollowUpWithin within) {
		return switch (decision) {
			case NO_FURTHER_ACTION -> within == CaseApi.FollowUpWithin.NONE;
			case MONITOR -> within == CaseApi.FollowUpWithin.WITHIN_7_DAYS || within == CaseApi.FollowUpWithin.WITHIN_30_DAYS;
			case REFER -> within != CaseApi.FollowUpWithin.NONE;
			case URGENT_REFER -> within == CaseApi.FollowUpWithin.WITHIN_24_HOURS;
		};
	}

	/** True when the answers ask for prompt human follow-up. Not a score and not a risk level. */
	static boolean needsUrgentReview(List<CaseApi.Answer> answers) {
		return answers.stream().anyMatch(a -> SAFETY_ITEM.equals(a.itemId()) && URGENT_ANSWERS.contains(a.answerCode()));
	}

}
