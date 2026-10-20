package org.mentalwellness.api.encounters.internal;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.mentalwellness.api.shared.web.DomainException;

/**
 * Validated instruments with their published scoring rules (ADR-016). PHQ-9 and GAD-7 are free to
 * use; their translations are not yet validated for this programme, so {@code translationValidated}
 * is false and the platform accepts synthetic data only. A score is computed only when every item is
 * answered, and is shown as an instrument score, never as a diagnosis.
 */
final class InstrumentCatalogue {

	private InstrumentCatalogue() {
	}

	/** Frequency answers shared by PHQ-9 and GAD-7, with their item values. */
	static final Map<String, Integer> FREQUENCY = Map.of("NOT_AT_ALL", 0, "SEVERAL_DAYS", 1, "MORE_THAN_HALF_THE_DAYS", 2,
			"NEARLY_EVERY_DAY", 3);

	static final String DECLINED = "DECLINED";

	record Band(int from, int to, String code) {
	}

	record Instrument(String id, String version, List<String> items, List<Band> bands, String safetyItem) {

		Optional<String> band(int score) {
			return this.bands.stream().filter(b -> score >= b.from() && score <= b.to()).map(Band::code).findFirst();
		}

	}

	static final Instrument PHQ9 = new Instrument("phq-9", "1.0.0",
			List.of("PHQ9_1", "PHQ9_2", "PHQ9_3", "PHQ9_4", "PHQ9_5", "PHQ9_6", "PHQ9_7", "PHQ9_8", "PHQ9_9"),
			List.of(new Band(0, 4, "MINIMAL"), new Band(5, 9, "MILD"), new Band(10, 14, "MODERATE"),
					new Band(15, 19, "MODERATELY_SEVERE"), new Band(20, 27, "SEVERE")),
			"PHQ9_9");

	static final Instrument GAD7 = new Instrument("gad-7", "1.0.0",
			List.of("GAD7_1", "GAD7_2", "GAD7_3", "GAD7_4", "GAD7_5", "GAD7_6", "GAD7_7"),
			List.of(new Band(0, 4, "MINIMAL"), new Band(5, 9, "MILD"), new Band(10, 14, "MODERATE"), new Band(15, 21, "SEVERE")),
			null);

	private static final Map<String, Instrument> ALL = Map.of(PHQ9.id() + "@" + PHQ9.version(), PHQ9,
			GAD7.id() + "@" + GAD7.version(), GAD7);

	static boolean translationValidated() {
		return false;
	}

	record Scored(Integer totalScore, String severityBand, boolean complete, boolean urgent) {
	}

	/** Validates the answers and scores them by the instrument's rule. */
	static Scored score(String instrumentId, String version, List<EncounterApi.Answer> answers) {
		Instrument instrument = Optional.ofNullable(ALL.get(instrumentId + "@" + version))
			.orElseThrow(() -> DomainException.invalid("/instrument", "unknownInstrument"));
		Map<String, String> byItem = new LinkedHashMap<>();
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < answers.size(); i++) {
			EncounterApi.Answer a = answers.get(i);
			if (!instrument.items().contains(a.itemId())) {
				throw DomainException.invalid("/items/" + i + "/item_id", "enum");
			}
			if (!FREQUENCY.containsKey(a.answerCode()) && !DECLINED.equals(a.answerCode())) {
				throw DomainException.invalid("/items/" + i + "/answer_code", "enum");
			}
			if (!seen.add(a.itemId())) {
				throw DomainException.invalid("/items", "uniqueItemIds");
			}
			byItem.put(a.itemId(), a.answerCode());
		}
		boolean complete = byItem.keySet().containsAll(instrument.items()) && !byItem.containsValue(DECLINED);
		Integer total = complete ? byItem.values().stream().mapToInt(FREQUENCY::get).sum() : null;
		String band = total == null ? null : instrument.band(total).orElseThrow();
		// Any answer above "not at all" on the self-harm item asks for prompt human follow-up.
		String safety = instrument.safetyItem() == null ? null : byItem.get(instrument.safetyItem());
		boolean urgent = safety != null && FREQUENCY.getOrDefault(safety, 0) > 0;
		return new Scored(total, band, complete, urgent);
	}

}
