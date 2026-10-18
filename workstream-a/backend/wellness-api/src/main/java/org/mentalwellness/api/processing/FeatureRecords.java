package org.mentalwellness.api.processing;

import java.util.List;

/** Read access to stored feature records, for the research-export module (slice S8). */
public interface FeatureRecords {

	/** A stored record with what the export needs to filter and group it. */
	record Stored(String recordId, String consentEventId, String participantRef, String sessionId, boolean flagged,
			String recordJson) {
	}

	/** All stored records, oldest first. */
	List<Stored> all();

	/** These records (by application record ID), in any state. */
	List<Stored> byIds(List<String> recordIds);

	/** Whether any feature record was derived under this consent event. */
	boolean existForConsentEvent(String consentEventId);

}
