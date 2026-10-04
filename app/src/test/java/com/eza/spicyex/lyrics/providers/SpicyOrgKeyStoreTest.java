package com.eza.spicyex.lyrics.providers;

import com.eza.spicyex.lyrics.ai.AiCredentialStore;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpicyOrgKeyStoreTest {
    @Test public void onlyClientKeysAreAccepted() {
        assertTrue(SpicyOrgKeyStore.valid("sl_pk_client-key_123"));
        assertFalse(SpicyOrgKeyStore.valid("sl_sk_server-secret"));
        assertFalse(SpicyOrgKeyStore.valid("spotify-token"));
        assertFalse(SpicyOrgKeyStore.valid("sl_pk_"));
        assertFalse(SpicyOrgKeyStore.valid("sl_pk_test\nsl_pk_other"));
        assertFalse(SpicyOrgKeyStore.valid(null));
        assertFalse(SpicyOrgKeyStore.valid("sl_pk_" + new String(new char[512]).replace('\0', 'x')));
    }
    @Test public void failedSavePreservesKeyAndRevisionAndDeleteAdvancesRevision() {
        FakeAndroidContext context = new FakeAndroidContext();
        AiCredentialStore store = new AiCredentialStore(context, new AiCredentialStore.Cipher() {
            public String encrypt(String value) { return "encrypted:" + value; }
            public String decrypt(String value) { return value.substring(10); }
            public void clear() { }
        });
        assertTrue(SpicyOrgKeyStore.save(context, "sl_pk_test-client-123", store));
        long first = SpicyOrgKeyStore.epoch(context);
        assertTrue(first > 0L);
        assertFalse(SpicyOrgKeyStore.save(context, "sl_sk_server", store));
        assertEquals(first, SpicyOrgKeyStore.epoch(context));
        assertEquals("sl_pk_test-client-123", store.load("spicy_org_client_key"));
        assertEquals("", store.load("spicy_desktop_spotify_token"));
        SpicyOrgKeyStore.delete(context);
        assertTrue(SpicyOrgKeyStore.epoch(context) > first);
        assertEquals("", store.load("spicy_org_client_key"));
    }
}
