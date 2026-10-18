package org.mentalwellness.api.support;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Signal-generated test audio: a 16 kHz mono 16-bit PCM WAV with a pitch-varying tone. Not a
 * recording of anyone. An ASCII marker is embedded in a trailing chunk so tests can check the bytes
 * never appear in logs or plaintext storage.
 */
public final class SyntheticAudio {

	public static final String MARKER = "SYNTHETIC-AUDIO-MARKER-3c9e";

	private SyntheticAudio() {
	}

	public static byte[] wav(double seconds) {
		int rate = 16_000;
		int samples = (int) (rate * seconds);
		byte[] marker = MARKER.getBytes(StandardCharsets.US_ASCII);
		int dataBytes = samples * 2;
		int markerChunk = 8 + marker.length;
		ByteBuffer b = ByteBuffer.allocate(44 + dataBytes + markerChunk).order(ByteOrder.LITTLE_ENDIAN);
		b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataBytes + markerChunk)
			.put("WAVE".getBytes(StandardCharsets.US_ASCII));
		b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
			.putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
		b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataBytes);
		double phase = 0;
		for (int i = 0; i < samples; i++) {
			double freq = 140 + 20 * Math.sin(2 * Math.PI * 0.5 * i / rate);
			phase += 2 * Math.PI * freq / rate;
			b.putShort((short) (Math.sin(phase) * 8000));
		}
		b.put("note".getBytes(StandardCharsets.US_ASCII)).putInt(marker.length).put(marker);
		return b.array();
	}

}
