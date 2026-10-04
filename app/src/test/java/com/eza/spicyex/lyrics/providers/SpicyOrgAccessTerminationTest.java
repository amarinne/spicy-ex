package com.eza.spicyex.lyrics.providers;

import com.eza.spicyex.lyrics.ai.AiCredentialStore;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpicyOrgAccessTerminationTest {
    private String envelope(int status, String code) {
        return "{\"Status\":" + status + ",\"Type\":\"object\",\"Body\":{\"error\":\""
                + code + "\",\"message\":\"untrusted message\"}}";
    }

    private void rotate(FakeAndroidContext context) {
        AiCredentialStore store = new AiCredentialStore(context, new AiCredentialStore.Cipher() {
            public String encrypt(String value) { return "encrypted:" + value; }
            public String decrypt(String value) { return value.substring(10); }
            public void clear() { }
        });
        assertTrue(SpicyOrgKeyStore.save(context, "sl_pk_local-test", store));
    }

    @Test public void documentedStableCodesConfirmTerminationWithoutReadingMessage() {
        for (String code : new String[]{"key_revoked", "application_suspended", "user_suspended",
                "application_deleted"}) {
            for (int status : new int[]{401, 403}) {
                assertEquals(code, SpicyOrgProtocol.terminationCode(status, envelope(status, code)));
            }
        }
    }

    @Test public void ambiguousErrorsAndMalformedEnvelopesCannotTerminateAccess() {
        for (String code : new String[]{"key_not_found", "key_malformed", "key_disabled_by_admin",
                "application_paused", "missing_scope", "origin_not_allowed", "origins_not_configured",
                "verification_unavailable", "rate_limited", "invalid_track_id", "unknown"}) {
            assertEquals("", SpicyOrgProtocol.terminationCode(403, envelope(403, code)));
        }
        for (int status : new int[]{200, 400, 404, 429, 500, 503}) {
            assertEquals("", SpicyOrgProtocol.terminationCode(status, envelope(status, "key_revoked")));
        }
        for (String raw : new String[]{null, "", "not json", "{}", "[]",
                "{\"error\":\"key_revoked\"}",
                "{\"Status\":403,\"Type\":\"object\",\"Body\":{\"message\":\"key_revoked\"}}",
                envelope(401, "key_revoked"),
                envelope(403, "key_revoked").replace("403", "403.5"),
                envelope(403, "key_revoked").replace("403", "\"403\""),
                envelope(403, "key_revoked").replace("\"object\"", "\"error\""),
                envelope(403, "key_revoked").replace("\"key_revoked\"", "[\"key_revoked\"]")}) {
            assertEquals("", SpicyOrgProtocol.terminationCode(403, raw));
        }
    }

    @Test public void ordinaryFailuresPreserveAccessAndTerminationPersistsAcrossNewRequests() {
        FakeAndroidContext context = new FakeAndroidContext();
        SpicyOrgAccessState.RequestTicket ticket = SpicyOrgAccessState.beginRequest(context, false);
        assertNotNull(ticket);
        assertFalse(SpicyOrgAccessState.terminate(context, ticket, "rate_limited"));
        assertFalse(SpicyOrgAccessState.isTerminated(context));
        assertTrue(SpicyOrgAccessState.terminate(context, ticket, "key_revoked"));
        assertTrue(SpicyOrgAccessState.isTerminated(context));
        assertNull(SpicyOrgAccessState.beginRequest(context, false));
        assertNotNull(SpicyOrgAccessState.beginRequest(context, true));
        assertFalse(SpicyOrgAccessState.acceptValidatedResponse(context, ticket));
        assertTrue(context.file("SpotifyPlusSpicyOrgAccessState").getBoolean("terminated", false));
    }

    @Test public void rotatingAKeyAllowsAcquisitionButOnlyValidatedAccessRestoresCache() {
        FakeAndroidContext context = new FakeAndroidContext();
        rotate(context);
        SpicyOrgAccessState.RequestTicket old = SpicyOrgAccessState.beginRequest(context, false);
        assertTrue(SpicyOrgAccessState.terminate(context, old, "application_suspended"));
        long deniedRevision = SpicyOrgAccessState.revision(context);
        rotate(context);
        assertTrue(SpicyOrgAccessState.isTerminated(context));
        assertFalse(SpicyOrgAccessState.terminate(context, old, "key_revoked"));
        assertFalse(SpicyOrgAccessState.acceptValidatedResponse(context, old));
        assertEquals(deniedRevision, SpicyOrgAccessState.revision(context));
        SpicyOrgAccessState.RequestTicket current = SpicyOrgAccessState.beginRequest(context, false);
        assertNotNull(current);
        assertTrue(SpicyOrgAccessState.acceptValidatedResponse(context, current));
        assertFalse(SpicyOrgAccessState.isTerminated(context));
        assertTrue(SpicyOrgAccessState.revision(context) > deniedRevision);
    }

    @Test public void anOldEpochCannotTerminateOrPublishAfterRotationWithoutPriorDenial() {
        FakeAndroidContext context = new FakeAndroidContext();
        rotate(context);
        SpicyOrgAccessState.RequestTicket old = SpicyOrgAccessState.beginRequest(context, false);
        rotate(context);
        assertFalse(SpicyOrgAccessState.terminate(context, old, "key_revoked"));
        assertFalse(SpicyOrgAccessState.acceptValidatedResponse(context, old));
        assertFalse(SpicyOrgAccessState.isTerminated(context));
        assertEquals(0L, SpicyOrgAccessState.revision(context));
        assertTrue(SpicyOrgAccessState.acceptValidatedResponse(context,
                SpicyOrgAccessState.beginRequest(context, false)));
    }

    @Test public void concurrentOldSuccessCannotReviveAccessAndOldDenialCannotUndoRecovery()
            throws Exception {
        FakeAndroidContext context = new FakeAndroidContext();
        SpicyOrgAccessState.RequestTicket pending = SpicyOrgAccessState.beginRequest(context, false);
        CountDownLatch denied = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        Thread success = new Thread(() -> {
            try { denied.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
            if (SpicyOrgAccessState.acceptValidatedResponse(context, pending)) accepted.incrementAndGet();
        });
        success.start();
        assertTrue(SpicyOrgAccessState.terminate(context, pending, "user_suspended"));
        denied.countDown();
        success.join(5000);
        assertFalse(success.isAlive());
        assertEquals(0, accepted.get());
        SpicyOrgAccessState.RequestTicket recovery = SpicyOrgAccessState.beginRequest(context, true);
        SpicyOrgAccessState.RequestTicket otherRecovery = SpicyOrgAccessState.beginRequest(context, true);
        assertTrue(SpicyOrgAccessState.acceptValidatedResponse(context, recovery));
        assertFalse(SpicyOrgAccessState.terminate(context, otherRecovery, "application_deleted"));
        assertFalse(SpicyOrgAccessState.terminate(context, pending, "key_revoked"));
        assertFalse(SpicyOrgAccessState.isTerminated(context));
    }

    @Test public void newTerminationRejectsARecoveryResponseIssuedBeforeIt() {
        FakeAndroidContext context = new FakeAndroidContext();
        SpicyOrgAccessState.RequestTicket initial = SpicyOrgAccessState.beginRequest(context, false);
        assertTrue(SpicyOrgAccessState.terminate(context, initial, "key_revoked"));
        SpicyOrgAccessState.RequestTicket first = SpicyOrgAccessState.beginRequest(context, true);
        SpicyOrgAccessState.RequestTicket second = SpicyOrgAccessState.beginRequest(context, true);
        assertTrue(SpicyOrgAccessState.terminate(context, first, "user_suspended"));
        assertFalse(SpicyOrgAccessState.acceptValidatedResponse(context, second));
        assertTrue(SpicyOrgAccessState.isTerminated(context));
    }

    @Test public void listenersObserveCommittedStateWithoutBreakingProviderCompletion() {
        FakeAndroidContext context = new FakeAndroidContext();
        AtomicInteger notices = new AtomicInteger();
        Runnable listener = () -> { assertTrue(SpicyOrgAccessState.isTerminated(context)); notices.incrementAndGet(); };
        Runnable broken = () -> { throw new IllegalStateException("consumer failure"); };
        SpicyOrgAccessState.addListener(broken);
        SpicyOrgAccessState.addListener(listener);
        try {
            assertTrue(SpicyOrgAccessState.terminate(context,
                    SpicyOrgAccessState.beginRequest(context, false), "key_revoked"));
            assertEquals(1, notices.get());
        } finally {
            SpicyOrgAccessState.removeListener(listener);
            SpicyOrgAccessState.removeListener(broken);
        }
    }
}
