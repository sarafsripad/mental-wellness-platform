package org.mentalwellness.api.shared.web;

/** {@code LanguageTag} of wellness-api.v1.yaml (1.1): consent forms and cases. */
public final class LanguageTags {

	/**
	 * The 12 supported tags. Every tag except en-IN uses unreviewed prototype translations
	 * (consent-and-data-lifecycle.md §14). Voice sessions accept a narrower set ({@code VoiceLanguageTag}).
	 */
	public static final String PATTERN = "^(en-IN|te-IN|hi-IN|ta-IN|bn-IN|mr-IN|kn-IN|ml-IN|gu-IN|pa-IN|ur-IN|or-IN)$";

	private LanguageTags() {
	}

}
