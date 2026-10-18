package org.mentalwellness.api.voicesession.internal;

import java.util.Set;

import org.mentalwellness.api.consent.ConsentScope;
import org.mentalwellness.api.consent.WithdrawalAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@code DELETE_RETAINED_AUDIO} on withdrawal (consent-and-data-lifecycle.md section 4).
 *
 * <p>Withdrawing {@code AUDIO_RECORDING} cancels the event's sessions and queues their stored audio
 * for deletion. Objects are deleted after the withdrawal commits, so a storage outage can never
 * block or undo a withdrawal; anything not deleted is retried by {@link #sweep()}.
 *
 * <p>Withdrawing only {@code AUDIO_RETENTION} is {@code NOT_APPLICABLE} in v1: no audio is kept
 * beyond the processing window (the storage lifecycle rule expires it), so nothing extra is retained.
 */
@Component
class AudioDeletionOnWithdrawal implements WithdrawalAction {

	private static final Logger log = LoggerFactory.getLogger(AudioDeletionOnWithdrawal.class);

	private final VoiceSessionRepository repository;

	private final AudioStore store;

	AudioDeletionOnWithdrawal(VoiceSessionRepository repository, AudioStore store) {
		this.repository = repository;
		this.store = store;
	}

	@Override
	public Type type() {
		return Type.DELETE_RETAINED_AUDIO;
	}

	@Override
	public Status apply(String consentEventId, String participantRef, Set<ConsentScope> withdrawnScopes) {
		if (!withdrawnScopes.contains(ConsentScope.AUDIO_RECORDING)) {
			return Status.NOT_APPLICABLE;
		}
		int cancelled = this.repository.cancelSessions(consentEventId);
		int[] marked = this.repository.markForDeletion(consentEventId);
		if (marked[0] > 0) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					sweep();
				}
			});
			return Status.SCHEDULED;
		}
		return cancelled > 0 || marked[1] > 0 ? Status.COMPLETED : Status.NOT_APPLICABLE;
	}

	/** Deletes queued audio. Runs after each withdrawal and every minute as a retry. */
	@Scheduled(fixedDelayString = "${wellness.storage.deletion-sweep-ms:60000}")
	@Transactional
	public void sweep() {
		for (VoiceSessionRepository.PendingDeletion pending : this.repository.pendingDeletions(100)) {
			try {
				this.store.delete(pending.objectKey());
				this.repository.markDeleted(pending.recordingId());
			}
			catch (RuntimeException ex) {
				// Left pending for the next sweep; the reason is logged without message content.
				log.warn("Audio deletion deferred for {} ({})", pending.recordingId(), ex.getClass().getSimpleName());
			}
		}
	}

}
