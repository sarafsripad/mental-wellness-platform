package org.mentalwellness.api.support;

import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/** Random synthetic pseudonymous IDs in the contract formats. */
public final class Ids {

	private Ids() {
	}

	public static String of(String prefix) {
		byte[] bytes = new byte[16];
		ThreadLocalRandom.current().nextBytes(bytes);
		return prefix + "_" + HexFormat.of().formatHex(bytes);
	}

}
