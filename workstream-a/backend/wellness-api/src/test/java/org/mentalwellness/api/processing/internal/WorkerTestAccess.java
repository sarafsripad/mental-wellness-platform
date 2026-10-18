package org.mentalwellness.api.processing.internal;

import org.springframework.boot.test.context.TestComponent;

/** Test-only handle on the lease sweeper, so tests needn't wait for the schedule. */
@TestComponent
public class WorkerTestAccess {

	private final WorkerService service;

	WorkerTestAccess(WorkerService service) {
		this.service = service;
	}

	public void expireLeases() {
		this.service.expireLeases();
	}

}
