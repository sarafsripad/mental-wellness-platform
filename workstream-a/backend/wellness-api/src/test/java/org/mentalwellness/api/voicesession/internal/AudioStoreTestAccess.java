package org.mentalwellness.api.voicesession.internal;

import org.springframework.boot.test.context.TestComponent;

/** Test-only view of the (package-private) audio store, for checking objects directly in storage. */
@TestComponent
public class AudioStoreTestAccess {

	private final AudioStore store;

	AudioStoreTestAccess(AudioStore store) {
		this.store = store;
	}

	public boolean exists(String key) {
		return this.store.exists(key);
	}

	public String encryption(String key) {
		return this.store.encryption(key);
	}

}
