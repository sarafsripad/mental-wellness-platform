package org.mentalwellness.api.encounters.internal;

import org.mentalwellness.api.shared.security.FieldCipher;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Clinical notes switch and key (ADR-016). Off by default: free text is excluded until the privacy
 * lead approves a sensitive-text policy (consent-and-data-lifecycle.md §5). When on,
 * {@code WELLNESS_NOTES_KEY} (64 hex characters, its own key) is required at startup.
 */
@Component
class ClinicalNotes {

	static final int KEY_VERSION = 1;

	private final boolean enabled;

	private final FieldCipher cipher;

	ClinicalNotes(@Value("${wellness.clinical-notes.enabled:false}") boolean enabled,
			@Value("${wellness.clinical-notes.key:}") String key) {
		this.enabled = enabled;
		this.cipher = enabled ? new FieldCipher(key) : null;
	}

	boolean enabled() {
		return this.enabled;
	}

	byte[] seal(String noteId, String encounterId, String text) {
		return this.cipher.encrypt(aad(noteId, encounterId), text);
	}

	String open(String noteId, String encounterId, byte[] sealed) {
		return this.cipher.decrypt(aad(noteId, encounterId), sealed);
	}

	private static String aad(String noteId, String encounterId) {
		return "note:v" + KEY_VERSION + ":" + noteId + ":" + encounterId;
	}

}
