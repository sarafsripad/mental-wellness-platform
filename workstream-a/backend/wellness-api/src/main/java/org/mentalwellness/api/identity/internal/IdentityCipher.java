package org.mentalwellness.api.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM field encryption for identity values, on top of the database's own storage. The
 * participant reference and field name are bound in as associated data, so a ciphertext copied to
 * another row or column fails to decrypt. Output: 12-byte random nonce, then ciphertext and tag.
 */
final class IdentityCipher {

	static final int KEY_VERSION = 1;

	private static final int NONCE_BYTES = 12;

	private static final int TAG_BITS = 128;

	private final SecretKeySpec key;

	private final SecureRandom random = new SecureRandom();

	IdentityCipher(byte[] key) {
		if (key.length != 32) {
			throw new IllegalArgumentException("identity key must be 256 bits");
		}
		this.key = new SecretKeySpec(key, "AES");
	}

	byte[] encrypt(String participantRef, String field, String value) {
		byte[] nonce = new byte[NONCE_BYTES];
		this.random.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, this.key, new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(aad(participantRef, field));
			byte[] sealed = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
			byte[] out = Arrays.copyOf(nonce, NONCE_BYTES + sealed.length);
			System.arraycopy(sealed, 0, out, NONCE_BYTES, sealed.length);
			return out;
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("identity encryption failed", ex);
		}
	}

	/** Only for tests and the future audited lookup; no API operation calls it. */
	String decrypt(String participantRef, String field, byte[] sealed) {
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, this.key, new GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES));
			cipher.updateAAD(aad(participantRef, field));
			return new String(cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("identity decryption failed", ex);
		}
	}

	private static byte[] aad(String participantRef, String field) {
		return ("identity:v" + KEY_VERSION + ":" + participantRef + ":" + field).getBytes(StandardCharsets.UTF_8);
	}

}
