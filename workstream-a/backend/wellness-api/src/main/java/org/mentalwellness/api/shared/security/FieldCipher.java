package org.mentalwellness.api.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM field encryption with associated data (for example "note:v1:&lt;note id&gt;"), so a
 * ciphertext moved to another row or column fails to decrypt. Output: 12-byte random nonce, then
 * ciphertext and tag. Each use gets its own key (clinical notes: {@code WELLNESS_NOTES_KEY}).
 */
public final class FieldCipher {

	private static final int NONCE_BYTES = 12;

	private static final int TAG_BITS = 128;

	private final SecretKeySpec key;

	private final SecureRandom random = new SecureRandom();

	public FieldCipher(String hexKey) {
		if (hexKey == null || !hexKey.matches("^[0-9a-fA-F]{64}$")) {
			throw new IllegalArgumentException("field encryption key must be 64 hex characters (256 bits)");
		}
		this.key = new SecretKeySpec(HexFormat.of().parseHex(hexKey), "AES");
	}

	public byte[] encrypt(String associatedData, String value) {
		byte[] nonce = new byte[NONCE_BYTES];
		this.random.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, this.key, new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
			byte[] sealed = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
			byte[] out = Arrays.copyOf(nonce, NONCE_BYTES + sealed.length);
			System.arraycopy(sealed, 0, out, NONCE_BYTES, sealed.length);
			return out;
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("field encryption failed", ex);
		}
	}

	public String decrypt(String associatedData, byte[] sealed) {
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, this.key, new GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES));
			cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
			return new String(cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("field decryption failed", ex);
		}
	}

}
