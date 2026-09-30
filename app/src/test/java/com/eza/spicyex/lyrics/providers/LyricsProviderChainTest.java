package com.eza.spicyex.lyrics.providers;

import org.junit.Test;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class LyricsProviderChainTest {
    @Test
    public void cachedSyncedSuppressesDuplicateEqualNetworkSynced() {
        LyricsProviderChain chain = new LyricsProviderChain(7, "same-raw");
        LyricsDocument cached = doc("Line", "apple_music_cache", "Apple Music", true);

        LyricsProviderChain.Decision cachedDecision = chain.acceptCached(cached);
        LyricsProviderChain.Decision networkDecision = chain.acceptRemoteNetwork(
                doc("Line", "apple_music_lenerd", "Apple Music", true), "same-raw");

        assertEquals(LyricsProviderChain.Action.DELIVER, cachedDecision.action);
        assertEquals(LyricsProviderChain.Action.SUPPRESS, networkDecision.action);
        assertEquals(7, cached.generation);
    }

    @Test
    public void cachedStaticCanBeUpgradedByNativeSyncedLyrics() {
        LyricsProviderChain chain = new LyricsProviderChain(11, "cached-static");
        chain.amllAllowed = false;
        chain.lrclibAllowed = false;
        LyricsDocument cachedStatic = doc("Static", "apple_music_cache", "Apple Music", true);
        LyricsDocument nativeSynced = doc("Word", "spotify_native_model", "Musixmatch", false);

        chain.acceptCached(cachedStatic);
        LyricsProviderChain.Decision decision = chain.acceptNative(nativeSynced);

        assertEquals(LyricsProviderChain.Action.DELIVER, decision.action);
        assertSame(nativeSynced, decision.document);
        assertEquals(11, nativeSynced.generation);
    }

    @Test
    public void appleLineHoldsAndContinuesPastWordThreshold() {
        LyricsProviderChain chain = new LyricsProviderChain(7, null);

        LyricsProviderChain.Decision decision = chain.acceptRemoteNetwork(
                doc("Line", "apple_music_lenerd", "Apple Music", true), "raw-line");

        assertEquals(LyricsProviderChain.Action.CONTINUE, decision.action);
        assertTrue(chain.hasPendingStatic());
        assertSame("Line", chain.pendingStatic().type);
    }

    @Test
    public void appleWordDeliversImmediately() {
        LyricsProviderChain chain = new LyricsProviderChain(7, null);

        LyricsProviderChain.Decision decision = chain.acceptRemoteNetwork(
                doc("Word", "apple_music_lenerd", "Apple Music", true), "raw-word");

        assertEquals(LyricsProviderChain.Action.DELIVER, decision.action);
        assertSame("Word", decision.document.type);
    }

    @Test
    public void nativeLineHoldsWithoutPending() {
        LyricsProviderChain chain = new LyricsProviderChain(11, null);

        LyricsProviderChain.Decision decision = chain.acceptNative(
                doc("Line", "spotify_native_model", "Musixmatch", false));

        assertEquals(LyricsProviderChain.Action.HOLD_STATIC, decision.action);
        assertTrue(chain.hasPendingStatic());
    }

    @Test
    public void nativeWordDeliversWithoutPending() {
        LyricsProviderChain chain = new LyricsProviderChain(11, null);

        LyricsProviderChain.Decision decision = chain.acceptNative(
                doc("Word", "spotify_native_model", "Musixmatch", false));

        assertEquals(LyricsProviderChain.Action.DELIVER, decision.action);
    }

    @Test
    public void appleLineThenNativeWordDeliversNative() {
        LyricsProviderChain chain = new LyricsProviderChain(11, null);

        chain.acceptRemoteNetwork(doc("Line", "apple_music_lenerd", "Apple Music", true), "raw");
        LyricsProviderChain.Decision decision = chain.acceptNative(
                doc("Word", "spotify_native_model", "Musixmatch", false));

        assertEquals(LyricsProviderChain.Action.DELIVER, decision.action);
        assertEquals("Word", decision.document.type);
    }

    @Test
    public void appleLineThenNativeLineContinuesWhenFallbackAllowed() {
        LyricsProviderChain chain = new LyricsProviderChain(11, null);

        chain.acceptRemoteNetwork(doc("Line", "apple_music_lenerd", "Apple Music", true), "raw");
        LyricsProviderChain.Decision decision = chain.acceptNative(
                doc("Line", "spotify_native_model", "Musixmatch", false));

        assertEquals(LyricsProviderChain.Action.CONTINUE, decision.action);
        assertTrue(chain.hasPendingStatic());
        // Apple wins the LINE tie, so the held best stays Apple for AMLL/LRCLIB to beat.
        assertTrue(chain.pendingStatic().fetchSource.contains("apple"));
    }

    @Test
    public void appleLineThenNativeLineDeliversWhenNoFallbackAllowed() {
        LyricsProviderChain chain = new LyricsProviderChain(11, null);
        chain.amllAllowed = false;
        chain.lrclibAllowed = false;

        chain.acceptRemoteNetwork(doc("Line", "apple_music_lenerd", "Apple Music", true), "raw");
        LyricsProviderChain.Decision decision = chain.acceptNative(
                doc("Line", "spotify_native_model", "Musixmatch", false));

        assertEquals(LyricsProviderChain.Action.DELIVER, decision.action);
        assertTrue(decision.document.fetchSource.contains("apple"));
    }

    @Test
    public void amllLineContinuesWhenLrclibAllowed() {
        LyricsProviderChain chain = new LyricsProviderChain(11, null);

        LyricsProviderChain.Decision decision = chain.acceptAmll(
                doc("Line", "amll", "AMLL", false));

        assertEquals(LyricsProviderChain.Action.CONTINUE, decision.action);
        assertTrue(chain.hasPendingStatic());
    }

    @Test
    public void amllLineDeliversWhenLrclibNotAllowed() {
        LyricsProviderChain chain = new LyricsProviderChain(11, null);
        chain.lrclibAllowed = false;

        LyricsProviderChain.Decision decision = chain.acceptAmll(
                doc("Line", "amll", "AMLL", false));

        assertEquals(LyricsProviderChain.Action.DELIVER, decision.action);
    }

    @Test
    public void remoteTransientFailureFallsThroughWithoutDurableNoLyrics() {
        LyricsProviderChain chain = new LyricsProviderChain(3, null);

        LyricsProviderChain.Decision decision = chain.remoteUnavailable("Apple Music network failed: timeout");

        assertEquals(LyricsProviderChain.Action.CONTINUE, decision.action);
        assertFalse(decision.durableNoLyrics);
        assertTrue(decision.result instanceof LyricsProviderChain.TransientFailure);
    }

    @Test
    public void lrclib404MarksDurableNoLyricsWhenNoStaticFallbackExists() {
        LyricsProviderChain chain = new LyricsProviderChain(4, null);

        LyricsProviderChain.Decision decision = chain.acceptLrclibError("Apple Music failed; LRCLIB HTTP 404");

        assertEquals(LyricsProviderChain.Action.ERROR, decision.action);
        assertTrue(decision.durableNoLyrics);
        assertTrue(decision.result instanceof LyricsProviderChain.Empty);
    }

    @Test
    public void nativeStaticDoesNotReplaceRemoteStaticOnSyncTie() {
        LyricsProviderChain chain = new LyricsProviderChain(5, "raw-static");
        chain.amllAllowed = false;
        chain.lrclibAllowed = false;
        LyricsDocument remoteStatic = doc("Static", "apple_music_lenerd", "Apple Music", true);
        LyricsDocument nativeStatic = doc("Static", "spotify_native_model", "Musixmatch", false);

        chain.acceptRemoteNetwork(remoteStatic, "raw-static");
        LyricsProviderChain.Decision decision = chain.acceptNative(nativeStatic);

        assertFalse(LyricQualityRanker.preferAuto(nativeStatic, remoteStatic));
        assertSame(remoteStatic, decision.document);
        assertFalse(decision.cacheDeliveredRaw);
    }

    @Test
    public void staleGenerationPreservedOnDeliveredDocuments() {
        LyricsProviderChain chain = new LyricsProviderChain(42, "cached");
        LyricsDocument cached = doc("Static", "apple_music_cache", "Apple Music", true);
        LyricsDocument lrclib = doc("Line", "lrclib", "LRCLIB", false);

        chain.acceptCached(cached);
        LyricsProviderChain.Decision decision = chain.acceptLrclib(lrclib);

        assertEquals(LyricsProviderChain.Action.DELIVER, decision.action);
        assertSame(lrclib, decision.document);
        assertEquals(42, cached.generation);
        assertEquals(42, lrclib.generation);
    }

    @Test
    public void poisonedRemoteResponseRejectedBeforeCacheOrDelivery() {
        LyricsProviderChain chain = new LyricsProviderChain(9, null);
        LyricsDocument poisoned = doc("Static", "apple_music_lenerd", "Apple Music", false);
        poisoned.spicyQueryStatus = 204;

        LyricsProviderChain.Decision decision = chain.acceptRemoteNetwork(poisoned, "poisoned-raw");

        assertEquals(LyricsProviderChain.Action.CONTINUE, decision.action);
        assertTrue(decision.result instanceof LyricsProviderChain.TransientFailure);
        assertFalse(decision.cacheDeliveredRaw);
        assertTrue(poisoned.spicyPoisoned);
    }

    private static LyricsDocument doc(String type, String fetchSource, String provider, boolean packed) {
        LyricsDocument doc = new LyricsDocument();
        doc.type = type;
        doc.fetchSource = fetchSource;
        doc.provider = provider;
        doc.spicyPackedPayload = packed;
        doc.spicyQueryStatus = 200;
        doc.spicyFormat = "json";
        LyricsLine line = new LyricsLine();
        line.text = "hello";
        doc.lines.add(line);
        return doc;
    }
}
