package com.eza.spicyex.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Packet M3: inner remote 401 detection, retry-once-only guard, and token-privacy of the
 * {@link LyricsRepository.Authorization} snapshot. All seams are pure JVM statics.
 */
public class LyricsRepositoryAuthRejectionTest {

    // ---------- inner auth-rejection detection (raw body scan) ----------

    @Test
    public void innerResultStatus401IsDetectedInRawBody() {
        String raw = "{\"queries\":[{\"operationId\":\"0\",\"result\":{\"status\":401,\"message\":\"Unauthorized\"}}]}";
        assertEquals(Integer.valueOf(401), LyricsRepository.innerRemoteAuthRejectionStatus(raw));
    }

    @Test
    public void innerResultStatus403DoesNotRefreshToken() {
        String raw = "{\"queries\":[{\"operationId\":\"0\",\"result\":{\"status\":403}}]}";
        assertNull(LyricsRepository.innerRemoteAuthRejectionStatus(raw));
    }

    @Test
    public void nonAuthInnerStatusIsNotAuthRejection() {
        String raw = "{\"queries\":[{\"operationId\":\"0\",\"result\":{\"status\":404}}]}";
        assertNull(LyricsRepository.innerRemoteAuthRejectionStatus(raw));
    }

    @Test
    public void statusOutsideResultObjectIsNotAuthRejection() {
        // A top-level status must not trigger scoped invalidation; only the inner query result counts.
        String raw = "{\"status\":401,\"queries\":[{\"operationId\":\"0\",\"result\":{\"status\":200}}]}";
        assertNull(LyricsRepository.innerRemoteAuthRejectionStatus(raw));
    }

    @Test
    public void malformedOrEmptyBodiesAreNotAuthRejections() {
        assertNull(LyricsRepository.innerRemoteAuthRejectionStatus(null));
        assertNull(LyricsRepository.innerRemoteAuthRejectionStatus("   "));
        assertNull(LyricsRepository.innerRemoteAuthRejectionStatus("not-json {"));
        assertNull(LyricsRepository.innerRemoteAuthRejectionStatus("[]"));
        assertNull(LyricsRepository.innerRemoteAuthRejectionStatus("{}"));
    }

    @Test
    public void otherOperationRejectionDoesNotRejectRequestedOperation() {
        String raw = "{\"queries\":[{\"operationId\":\"0\",\"result\":{\"status\":200}},{\"operationId\":\"1\",\"result\":{\"status\":401}}]}";
        assertNull(LyricsRepository.innerRemoteAuthRejectionStatus(raw));
    }

    // ---------- inner auth-rejection detection (parsed document) ----------

    @Test
    public void parsedDocumentWithAuthRejectionStatusIsDetected() {
        LyricsDocument doc = new LyricsDocument();
        doc.spicyQueryStatus = 401;
        assertTrue(LyricsRepository.isInnerRemoteAuthRejection(doc));
    }

    @Test
    public void parsedDocumentWithOtherStatusIsNotAuthRejection() {
        LyricsDocument doc = new LyricsDocument();
        doc.spicyQueryStatus = 200;
        assertFalse(LyricsRepository.isInnerRemoteAuthRejection(doc));
        doc.spicyQueryStatus = 403;
        assertFalse(LyricsRepository.isInnerRemoteAuthRejection(doc));
        doc.spicyQueryStatus = 404;
        assertFalse(LyricsRepository.isInnerRemoteAuthRejection(doc));
    }

    @Test
    public void nullDocumentIsNotAuthRejection() {
        assertFalse(LyricsRepository.isInnerRemoteAuthRejection(null));
    }

    // ---------- retry-once guard ----------

    @Test
    public void retryHappensOnceAgainstNewerGeneration() {
        LyricsRepository.Authorization newer = LyricsRepository.Authorization.of("replacement-token", 7);
        assertTrue(LyricsRepository.shouldRetryWithNewerGeneration(6, false, newer));
    }

    @Test
    public void retryNeverLoops() {
        LyricsRepository.Authorization newer = LyricsRepository.Authorization.of("replacement-token", 8);
        // Second attempt (authRetryUsed=true) must go to fallback, never retry again.
        assertFalse(LyricsRepository.shouldRetryWithNewerGeneration(7, true, newer));
    }

    @Test
    public void retryRequiresGenuinelyDifferentGeneration() {
        LyricsRepository.Authorization sameGeneration = LyricsRepository.Authorization.of("same-token", 6);
        assertFalse(LyricsRepository.shouldRetryWithNewerGeneration(6, false, sameGeneration));
    }

    @Test
    public void retryWithoutNewerAuthorizationGoesToFallback() {
        // No newer generation exists: no retry, straight to native/LRCLIB.
        assertFalse(LyricsRepository.shouldRetryWithNewerGeneration(6, false, null));
    }

    @Test
    public void sameTokenOrOlderGenerationCannotBeRetried() {
        assertFalse(LyricsRepository.shouldRetryAuthorization("same", 6, false,
                LyricsRepository.Authorization.of("same", 7)));
        assertFalse(LyricsRepository.shouldRetryAuthorization("old", 6, false,
                LyricsRepository.Authorization.of("new", 5)));
        assertFalse(LyricsRepository.shouldRetryAuthorization("old", 6, false,
                LyricsRepository.Authorization.of("0", 7)));
        assertTrue(LyricsRepository.shouldRetryAuthorization("old", 6, false,
                LyricsRepository.Authorization.of("new", 7)));
    }

    @Test
    public void refreshFailurePreservesRejectionAndCancellationRemainsCancellation() {
        assertNull(LyricsRepository.resolveAfterAuthRejection(generation -> {
            assertEquals(6, generation);
            throw new IllegalStateException("refresh failed");
        }, 6));
        org.junit.Assert.assertThrows(java.util.concurrent.CancellationException.class,
                () -> LyricsRepository.resolveAfterAuthRejection(generation -> {
                    throw new java.util.concurrent.CancellationException();
                }, 6));
    }

    // ---------- authorization snapshot privacy ----------

    @Test
    public void authorizationCarriesTokenAndGenerationWithoutLeakingTokenText() {
        LyricsRepository.Authorization auth = LyricsRepository.Authorization.of("secret-token-value", 9);
        assertEquals(9, auth.generation());
        assertEquals("secret-token-value", auth.token());
        assertFalse(auth.toString().contains("secret-token-value"));
        assertTrue(auth.toString().contains("generation=9"));
    }

    @Test
    public void authorizationToleratesNullTokenText() {
        LyricsRepository.Authorization auth = LyricsRepository.Authorization.of(null, 4);
        assertNotNull(auth);
        assertEquals("", auth.token());
        assertEquals(4, auth.generation());
    }
}
