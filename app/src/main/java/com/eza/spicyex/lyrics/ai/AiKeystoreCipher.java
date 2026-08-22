package com.eza.spicyex.lyrics.ai;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.eza.spicyex.Diagnostics;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-GCM through the Android Keystore.
 *
 * <p>The key is generated inside the Keystore and never leaves it — this class only ever holds a
 * handle. That is what makes the stored ciphertext worthless to anyone reading the data directory,
 * a backup, or a device image.
 *
 * <p>The IV is generated per encryption and stored alongside the ciphertext, which is required
 * rather than optional: GCM catastrophically loses confidentiality if an IV is reused under the
 * same key, so it must never be fixed or derived from the plaintext.
 */
final class AiKeystoreCipher implements AiCredentialStore.Cipher {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "spicyex_ai_credential_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final String SEPARATOR = ":";

    @Override
    public String encrypt(String plaintext) {
        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION);
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return encode(iv) + SEPARATOR + encode(encrypted);
        } catch (Throwable failure) {
            // Never log the exception message: a crypto provider can echo input into it.
            Diagnostics.event("AiKeystoreCipher", "encrypt:" + failure.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        try {
            String[] parts = AiText.nz(ciphertext).split(SEPARATOR, 2);
            if (parts.length != 2) return null;
            byte[] iv = decode(parts[0]);
            if (iv.length != IV_BYTES) return null;
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Throwable failure) {
            Diagnostics.event("AiKeystoreCipher", "decrypt:" + failure.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public void clear() {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        } catch (Throwable failure) {
            Diagnostics.event("AiKeystoreCipher", "clear:" + failure.getClass().getSimpleName());
        }
    }

    /** The existing key, or a new one. Generated on first use so no key exists until one is needed. */
    private static SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Deliberately not user-authentication-bound: the key is read on a background
                // thread while lyrics load, with no UI to prompt from and no user present.
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }

    private static String encode(byte[] value) {
        return Base64.encodeToString(value, Base64.NO_WRAP);
    }

    private static byte[] decode(String value) {
        return Base64.decode(value, Base64.NO_WRAP);
    }
}
