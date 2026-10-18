package org.mentalwellness.api.processing.internal;

import java.util.List;

import org.mentalwellness.api.processing.FeatureRecords;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class FeatureRecordQueries implements FeatureRecords {

	private final JobRepository jobs;

	FeatureRecordQueries(JobRepository jobs) {
		this.jobs = jobs;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Stored> all() {
		return this.jobs.featureRecords("");
	}

	@Override
	@Transactional(readOnly = true)
	public List<Stored> byIds(List<String> recordIds) {
		if (recordIds.isEmpty()) {
			return List.of();
		}
		return this.jobs.featureRecords("WHERE f.record_id = ANY(?)", (Object) recordIds.toArray(String[]::new));
	}

	@Override
	@Transactional(readOnly = true)
	public boolean existForConsentEvent(String consentEventId) {
		return this.jobs.hasFeatureRecords(consentEventId);
	}

}
