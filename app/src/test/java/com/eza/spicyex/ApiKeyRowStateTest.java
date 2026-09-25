package com.eza.spicyex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.eza.spicyex.lyrics.ai.AiCredentialStore;
import com.eza.spicyex.lyrics.ai.AiSettings;
import com.eza.spicyex.testsupport.FakeAndroidContext;

import org.junit.Before;
import org.junit.Test;

/** The API key row shows a mask; only the secure dialog ever holds the plaintext. */
public final class ApiKeyRowStateTest {

    private static final String SECRET = "sk-live-1234567890abcd";

    private FakeAndroidContext context;
    private AiCredentialStore credentials;
    private AiSettings settings;

    @Before public void setUp() {
        context = new FakeAndroidContext();
        credentials = new AiCredentialStore(context, new PlainCipher());
        settings = new AiSettings(new SettingsStore(context), credentials);
        settings.setModelName("gpt-4o-mini");
    }

    @Test public void aStoredKeyIsShownAsAMaskOnly() {
        assertTrue(credentials.save(settings.credentialScope(), SECRET));
        ApiKeyRowState row = new ApiKeyRowState(settings);

        String displayed = row.displayed();
        assertEquals("••••abcd", displayed);
        assertFalse("the row must never carry plaintext", displayed.contains(SECRET));
    }

    @Test public void revealingDoesNotChangeWhatTheRowHolds() {
        assertTrue(credentials.save(settings.credentialScope(), SECRET));
        ApiKeyRowState row = new ApiKeyRowState(settings);
        String before = row.displayed();

        assertEquals(SECRET, row.revealedSecret());

        assertEquals("a reveal is transient", before, row.displayed());
    }

    @Test public void anAbsentKeyHasNothingToShowOrReveal() {
        ApiKeyRowState row = new ApiKeyRowState(settings);

        assertFalse(row.hasCredential());
        assertEquals("", row.displayed());
        assertEquals("", row.revealedSecret());
    }

    static final class PlainCipher implements AiCredentialStore.Cipher {
        @Override public String encrypt(String plaintext) {
            return plaintext;
        }

        @Override public String decrypt(String ciphertext) {
            return ciphertext;
        }

        @Override public void clear() {
        }
    }
}
