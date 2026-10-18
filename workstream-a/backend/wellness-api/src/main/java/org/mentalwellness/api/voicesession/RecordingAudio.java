package org.mentalwellness.api.voicesession;

import java.util.Optional;

/** Read access to stored recordings, for the processing module (worker audio fetch, slice S6). */
public interface RecordingAudio {

	/** The recording, if its audio is currently stored and its session is not cancelled. */
	Optional<StoredRecording> findStored(String recordingId);

	/** The audio bytes of a stored recording, verified against its registered SHA-256. */
	byte[] read(String recordingId);

}
