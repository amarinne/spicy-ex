package com.eza.spicyex.lyrics.ai;

import android.content.Context;
import android.content.SharedPreferences;

import com.eza.spicyex.Diagnostics;

import java.nio.charset.StandardCharsets;

/**
 * The provider key at rest.
 *
 * <p>Kept in its own preferences file, encrypted with a key the app cannot export, and never
 * written anywhere else. What that does and does not buy is worth stating plainly, because
 * overstating it would be worse than the limit itself:
 *
 * <ul>
 *   <li><b>It resists</b> backup extraction, file-level snooping, and anyone reading the data
 *       directory offline. The ciphertext is useless without a key that never leaves the Keystore.
 *   <li><b>It does not resist</b> Spotify itself, or another module in the same process. We run
 *       inside Spotify, so the Keystore key belongs to Spotify's UID and anything else running
 *       there can ask for the plaintext exactly as we do. The real blast-radius control is a
 *       dedicated provider key with a spend cap set in the provider's own console.
 *   <li><b>Uninstalling Spicy EX does not remove it</b>, because these preferences belong to
 *       Spotify. That is why delete is an explicit action rather than something left to cleanup.
 * </ul>
 *
 * <p>The cipher is injected so the storage logic — the cap, the delete, the empty cases — is
 * testable off-device, where there is no Keystore at all.
 */
public final class AiCredentialStore {

    /** Encrypts and decrypts a secret. The Keystore implementation is the only real one. */
    public interface Cipher {
        /** @return ciphertext safe to persist, or null when it could not be encrypted */
        String encrypt(String plaintext);

        /** @return the secret, or null when it cannot be recovered */
        String decrypt(String ciphertext);

        /** Drops the underlying key material, if any. */
        void clear();
    }

    static final String PREFS = "SpotifyPlusAiCredentials";
    private static final String KEY_PREFIX = "secret_";

    private final SharedPreferences prefs;
    private final Cipher cipher;

    public AiCredentialStore(Context context, Cipher cipher) {
        this.prefs = context == null ? null : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.cipher = cipher;
    }

    /** The Keystore-backed store, as used at runtime. */
    public static AiCredentialStore create(Context context) {
        return new AiCredentialStore(context, new AiKeystoreCipher());
    }

    /**
     * Stores {@code secret} for {@code providerId}.
     *
     * @return false when the secret is empty, too large, or could not be encrypted. A failure
     *         leaves any existing secret untouched rather than half-replacing it.
     */
    public boolean save(String providerId, String secret) {
        if (prefs == null || cipher == null) return false;
        String id = AiText.nz(providerId);
        String value = AiText.nz(secret);
        if (id.isEmpty() || value.isEmpty()) return false;
        if (value.getBytes(StandardCharsets.UTF_8).length > AiContract.MAX_CREDENTIAL_BYTES) {
            // A pasted document rather than a key. Refusing is kinder than storing it and failing
            // every request afterwards with an auth error.
            return false;
        }
        String encrypted = cipher.encrypt(value);
        if (encrypted == null || encrypted.isEmpty()) {
            Diagnostics.event("AiCredentialStore", "encrypt_failed");
            return false;
        }
        prefs.edit().putString(KEY_PREFIX + id, encrypted).apply();
        return true;
    }

    /** @return the secret, or empty when there is none or it cannot be decrypted */
    public String load(String providerId) {
        if (prefs == null || cipher == null) return "";
        String stored = prefs.getString(KEY_PREFIX + AiText.nz(providerId), "");
        if (AiText.nz(stored).isEmpty()) return "";
        String secret = cipher.decrypt(stored);
        if (secret == null) {
            // Usually the Keystore key was invalidated — a device credential change, or a restore
            // onto different hardware. The stored bytes will never decrypt again, so treat the
            // credential as absent and let the owner re-enter it.
            Diagnostics.event("AiCredentialStore", "decrypt_failed");
            return "";
        }
        return secret;
    }

    public boolean has(String providerId) {
        return !load(providerId).isEmpty();
    }

    /** Removes the stored secret. The only way one leaves the device short of a factory reset. */
    public void delete(String providerId) {
        if (prefs == null) return;
        prefs.edit().remove(KEY_PREFIX + AiText.nz(providerId)).apply();
    }

    /** Removes every stored secret and the key material behind them. */
    public void deleteAll() {
        if (prefs != null) prefs.edit().clear().apply();
        if (cipher != null) cipher.clear();
    }

    /**
     * A masked form for display: enough to recognise which key is stored, never enough to use it.
     *
     * <p>Only the last four characters, and only for a key long enough that four characters do not
     * meaningfully narrow it down.
     */
    public static String mask(String secret) {
        String value = AiText.nz(secret);
        if (value.isEmpty()) return "";
        if (value.length() <= 8) return "••••";
        return "••••" + value.substring(value.length() - 4);
    }
}
