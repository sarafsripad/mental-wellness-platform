package org.mentalwellness.api.processing.internal;

import java.util.Set;

import org.mentalwellness.api.consent.ConsentScope;
import org.mentalwellness.api.consent.WithdrawalAction;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Processing effects of withdrawing {@code AUDIO_RECORDING} (consent-and-data-lifecycle.md section 4):
 * queued and running jobs are cancelled, and feature records already derived are flagged, which
 * excludes them from all further use and export. Both happen in the withdrawal's transaction.
 */
@Configuration(proxyBeanMethods = false)
class ProcessingWithdrawalActions {

	@Bean
	WithdrawalAction cancelQueuedProcessing(JobRepository jobs) {
		return new WithdrawalAction() {
			@Override
			public Type type() {
				return Type.CANCEL_QUEUED_PROCESSING;
			}

			@Override
			public Status apply(String consentEventId, String participantRef, Set<ConsentScope> withdrawn) {
				return jobs.cancelForConsentEvent(consentEventId) > 0 ? Status.COMPLETED : Status.NOT_APPLICABLE;
			}
		};
	}

	@Bean
	WithdrawalAction flagDerivedFeatures(JobRepository jobs) {
		return new WithdrawalAction() {
			@Override
			public Type type() {
				return Type.FLAG_DERIVED_FEATURES;
			}

			@Override
			public Status apply(String consentEventId, String participantRef, Set<ConsentScope> withdrawn) {
				return jobs.flagFeatureRecords(consentEventId) > 0 ? Status.COMPLETED : Status.NOT_APPLICABLE;
			}
		};
	}

}
