package org.mentalwellness.api.voicesession;

import java.util.Optional;

/**
 * Lets the processing module report a session's processing state ({@code PROCESSING},
 * {@code PROCESSED}, {@code PROCESSING_FAILED}) without the voice-session module depending on it.
 */
public interface SessionStatusContributor {

	/** A processing status for the session once all its recordings are stored, or empty if none applies. */
	Optional<String> processingStatus(String sessionId);

}
