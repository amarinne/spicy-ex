package com.eza.spicyex.lyrics.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.eza.spicyex.lyrics.BackgroundLine;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;

/** Phase 1: cache-first canonical base — durability, source policy, and replacement identity. */
public class CanonicalSourceTest {

    // --- source policy ------------------------------------------------------

    @Test
    public void cachedSyncedBaseNeedsNoNetworkCall() {
        assertEquals(LyricsSourcePolicy.Decision.USE_CACHED_BASE,
                LyricsSourcePolicy.decide(true, true, false, false));
        assertEquals(LyricsSourcePolicy.Decision.USE_CACHED_BASE,
                LyricsSourcePolicy.decide(true, true, true, false));
    }

    @Test
    public void missingBaseIsTheOnlyCaseThatBlocksOnTheNetwork() {
        assertEquals(LyricsSourcePolicy.Decision.FETCH_REQUIRED,
                LyricsSourcePolicy.decide(false, false, false, false));
        assertEquals(LyricsSourcePolicy.Decision.FETCH_REQUIRED,
                LyricsSourcePolicy.decide(false, true, true, true));
    }

    @Test
    public void staticCachedBaseProbesOnceThenStopsAsking() {
        assertEquals(LyricsSourcePolicy.Decision.REFRESH_AFTER_CACHED_BASE,
                LyricsSourcePolicy.decide(true, false, false, false));
        assertEquals(LyricsSourcePolicy.Decision.USE_CACHED_BASE,
                LyricsSourcePolicy.decide(true, false, true, false));
    }

    @Test
    public void explicitRefreshAlwaysProbesButStillRendersTheCachedBaseFirst() {
        assertEquals(LyricsSourcePolicy.Decision.REFRESH_AFTER_CACHED_BASE,
                LyricsSourcePolicy.decide(true, true, true, true));
    }

    @Test
    public void syncedDetectionCoversTheTimingTypesTheParsersEmit() {
        assertTrue(LyricsSourcePolicy.isSynced("Line"));
        assertTrue(LyricsSourcePolicy.isSynced("Syllable"));
        assertTrue(LyricsSourcePolicy.isSynced("word"));
        assertFalse(LyricsSourcePolicy.isSynced("Static"));
        assertFalse(LyricsSourcePolicy.isSynced("Unknown"));
        assertFalse(LyricsSourcePolicy.isSynced(null));
    }

    @Test
    public void manualSourceLanguageIsStrictAndNeverFallsBack() {
        assertEquals("hin", LyricsSourcePolicy.effectiveSourceLanguage("manual", "hin", "ja"));
        assertEquals("", LyricsSourcePolicy.effectiveSourceLanguage("manual", "", "ja"));
        assertEquals("ja", LyricsSourcePolicy.effectiveSourceLanguage("auto", "hin", "ja"));
        assertTrue(LyricsSourcePolicy.isStrictSourceLanguage("manual"));
        assertFalse(LyricsSourcePolicy.isStrictSourceLanguage("auto"));
    }

    // --- adoption / replacement identity ------------------------------------

    @Test
    public void theSameSourceArrivingTwiceIsNotAReplacement() {
        assertEquals(CanonicalBaseAdoption.Outcome.UNCHANGED,
                CanonicalBaseAdoption.evaluate(true, "digest-a", "digest-a"));
        assertEquals(4, CanonicalBaseAdoption.nextSourceRevision(4,
                CanonicalBaseAdoption.Outcome.UNCHANGED));
    }

    @Test
    public void aDifferentSourceReplacesTheBaseAndBumpsTheRevision() {
        assertEquals(CanonicalBaseAdoption.Outcome.REPLACE,
                CanonicalBaseAdoption.evaluate(true, "digest-a", "digest-b"));
        assertEquals(5, CanonicalBaseAdoption.nextSourceRevision(4,
                CanonicalBaseAdoption.Outcome.REPLACE));
    }

    @Test
    public void theFirstSourceAdoptsAtRevisionOne() {
        assertEquals(CanonicalBaseAdoption.Outcome.ADOPT,
                CanonicalBaseAdoption.evaluate(false, "", "digest-a"));
        assertEquals(1, CanonicalBaseAdoption.nextSourceRevision(0,
                CanonicalBaseAdoption.Outcome.ADOPT));
    }

    @Test
    public void anEmptyIncomingDigestNeverDisturbsTheBase() {
        assertEquals(CanonicalBaseAdoption.Outcome.UNCHANGED,
                CanonicalBaseAdoption.evaluate(true, "digest-a", ""));
        assertEquals(CanonicalBaseAdoption.Outcome.UNCHANGED,
                CanonicalBaseAdoption.evaluate(false, "", null));
    }

    @Test
    public void sourceReplacementInvalidatesOnlyArtifactsTiedToTheOldDigest() {
        LyricsDocument first = document("ichi", "ni");
        LyricSession session = LyricSession.of(CanonicalBase.fromDocument("spotify:track:a", first), 1);
        String soundConfig = LayerConfigIds.sound(true, "cn=pinyin", "ja", 3);
        SoundArtifact artifact = new SoundArtifact(session.base.digest, soundConfig,
                new LayerProvenance(LayerAuthority.DETERMINISTIC, "local", "c1", 0L),
                java.util.Collections.singletonList(
                        SoundEntry.line(session.base.rows.get(0).rowId, "ichi-r", "romaji")), false);
        session = session.withSound(session.sound.withArtifact(LayerStatus.READY, artifact, "run-1"));

        LyricSession afterSameSource = session.withReplacedBase(
                CanonicalBase.fromDocument("spotify:track:a", document("ichi", "ni")));
        assertTrue(afterSameSource.sound.hasArtifact());
        assertEquals(1, afterSameSource.identity.sourceRevision);

        LyricSession afterUpgrade = session.withReplacedBase(
                CanonicalBase.fromDocument("spotify:track:a", document("ichi", "ni", "san")));
        assertFalse(afterUpgrade.sound.hasArtifact());
        assertEquals(2, afterUpgrade.identity.sourceRevision);
    }

    // --- durable canonical record -------------------------------------------

    @Test
    public void canonicalRecordRoundTripsSourceTextTimingAndSpans() {
        LyricsDocument original = document("ichi", "ni");
        original.provider = "Spicy Lyrics";
        original.type = "Syllable";
        original.songWriters = "someone";
        original.durationMs = 200_000L;
        LyricsLine first = original.lines.get(0);
        first.oppositeAligned = true;
        first.syllables.add(syllable("span-a", "i", 0L, 200L, true));
        first.syllables.add(syllable("span-b", "chi", 200L, 500L, false));
        BackgroundLine background = new BackgroundLine();
        background.text = "hey";
        background.startMs = 100L;
        background.endMs = 300L;
        background.syllables.add(syllable("span-bg", "hey", 100L, 300L, false));
        first.backgroundLines.add(background);

        CanonicalSourceCodec.Record restored = CanonicalSourceCodec.decode(
                CanonicalSourceCodec.encode(original, 3, "digest-a", 1234L));

        assertEquals(3, restored.sourceRevision);
        assertEquals("digest-a", restored.canonicalDigest);
        assertEquals("Spicy Lyrics", restored.document.provider);
        assertEquals("Syllable", restored.document.type);
        assertEquals("someone", restored.document.songWriters);
        assertEquals(200_000L, restored.document.durationMs);
        assertEquals(2, restored.document.lines.size());
        LyricsLine restoredFirst = restored.document.lines.get(0);
        assertEquals("ichi", restoredFirst.text);
        assertTrue(restoredFirst.oppositeAligned);
        assertEquals(2, restoredFirst.syllables.size());
        assertEquals("span-b", restoredFirst.syllables.get(1).spanId);
        assertEquals(200L, restoredFirst.syllables.get(1).startMs);
        assertEquals(Boolean.TRUE, restoredFirst.syllables.get(0).providerPartOfWord);
        assertNull(restoredFirst.syllables.get(1).providerPartOfWord);
        assertEquals(1, restoredFirst.backgroundLines.size());
        assertEquals("hey", restoredFirst.backgroundLines.get(0).text);
    }

    @Test
    public void canonicalRecordNeverPersistsGeneratedText() {
        LyricsDocument original = document("ichi", "ni");
        original.lines.get(0).romanizedText = "ICHI";
        original.lines.get(0).translatedText = "one";
        original.lines.get(0).chineseMode = "pinyin";
        original.lines.get(0).syllables.add(syllable("span-a", "ichi", 0L, 500L, false));
        original.lines.get(0).syllables.get(0).romanizedText = "ICHI";
        original.includesRomanization = true;
        original.includesTranslation = true;

        String encoded = CanonicalSourceCodec.encode(original, 1, "digest-a", 0L);
        CanonicalSourceCodec.Record restored = CanonicalSourceCodec.decode(encoded);

        assertFalse(encoded.contains("ICHI"));
        assertFalse(encoded.contains("\"one\""));
        assertEquals("", restored.document.lines.get(0).romanizedText);
        assertEquals("", restored.document.lines.get(0).translatedText);
        assertEquals("", restored.document.lines.get(0).chineseMode);
        assertEquals("", restored.document.lines.get(0).syllables.get(0).romanizedText);
        assertFalse(restored.document.includesRomanization);
        assertFalse(restored.document.includesTranslation);
    }

    @Test
    public void providerSuppliedTranslationsStayCanonicalSourceData() {
        LyricsDocument original = document("ichi");
        original.lines.get(0).providerTranslatedText = "one";
        original.lines.get(0).providerTranslationLanguage = "en";

        CanonicalSourceCodec.Record restored = CanonicalSourceCodec.decode(
                CanonicalSourceCodec.encode(original, 1, "digest-a", 0L));

        assertEquals("one", restored.document.lines.get(0).providerTranslatedText);
        assertEquals("en", restored.document.lines.get(0).providerTranslationLanguage);
        // Still not displayed text — turning it into translatedText stays the Meaning layer's job.
        assertEquals("", restored.document.lines.get(0).translatedText);
    }

    @Test
    public void restoredCanonicalRecordReproducesTheSameDigest() {
        LyricsDocument original = document("ichi", "ni");
        original.lines.get(0).syllables.add(syllable("span-a", "ichi", 0L, 500L, false));
        CanonicalBase before = CanonicalBase.fromDocument("spotify:track:a", original);

        CanonicalSourceCodec.Record restored = CanonicalSourceCodec.decode(
                CanonicalSourceCodec.encode(original, 1, before.digest, 0L));

        assertEquals(before.digest,
                CanonicalBase.fromDocument("spotify:track:a", restored.document).digest);
    }

    @Test
    public void unreadableOrForeignSchemaRecordsDecodeToNothing() {
        assertNull(CanonicalSourceCodec.decode(null));
        assertNull(CanonicalSourceCodec.decode(""));
        assertNull(CanonicalSourceCodec.decode("not json"));
        assertNull(CanonicalSourceCodec.decode("{\"schema\":999,\"lines\":[]}"));
        assertNull(CanonicalSourceCodec.decode(
                CanonicalSourceCodec.encode(new LyricsDocument(), 1, "d", 0L)));
    }

    // --- durable bound (no TTL) ---------------------------------------------

    @Test
    public void canonicalEntriesEvictOnlyByCountAndBytesNeverByAge() {
        CanonicalSourceCache.Bound first = CanonicalSourceCache.plan("", "a", 10L, 2, 1000L);
        CanonicalSourceCache.Bound second = CanonicalSourceCache.plan(first.nextOrder, "b", 10L, 2, 1000L);
        CanonicalSourceCache.Bound third = CanonicalSourceCache.plan(second.nextOrder, "c", 10L, 2, 1000L);

        assertTrue(first.evicted.isEmpty());
        assertTrue(second.evicted.isEmpty());
        assertEquals(1, third.evicted.size());
        assertTrue(third.evicted.contains("a"));
        assertTrue(third.nextOrder.contains("b"));
        assertTrue(third.nextOrder.contains("c"));
    }

    @Test
    public void rewritingAnEntryRefreshesItsPlaceRatherThanEvictingIt() {
        CanonicalSourceCache.Bound first = CanonicalSourceCache.plan("", "a", 10L, 2, 1000L);
        CanonicalSourceCache.Bound second = CanonicalSourceCache.plan(first.nextOrder, "b", 10L, 2, 1000L);
        CanonicalSourceCache.Bound rewriteA =
                CanonicalSourceCache.plan(second.nextOrder, "a", 10L, 2, 1000L);
        CanonicalSourceCache.Bound third =
                CanonicalSourceCache.plan(rewriteA.nextOrder, "c", 10L, 2, 1000L);

        assertTrue(rewriteA.evicted.isEmpty());
        assertTrue(third.evicted.contains("b"));
        assertFalse(third.evicted.contains("a"));
    }

    @Test
    public void anOversizedRecordIsRejectedRatherThanEvictingEverythingElse() {
        CanonicalSourceCache.Bound seeded = CanonicalSourceCache.plan("", "a", 10L, 4, 100L);
        CanonicalSourceCache.Bound huge = CanonicalSourceCache.plan(seeded.nextOrder, "big", 500L, 4, 100L);

        assertTrue(huge.rejectedWrite);
        assertTrue(huge.nextOrder.contains("a"));
        assertFalse(huge.nextOrder.contains("big"));
    }

    @Test
    public void byteBoundEvictsOldestUntilTheNewRecordFits() {
        CanonicalSourceCache.Bound a = CanonicalSourceCache.plan("", "a", 60L, 10, 100L);
        CanonicalSourceCache.Bound b = CanonicalSourceCache.plan(a.nextOrder, "b", 60L, 10, 100L);

        assertTrue(b.evicted.contains("a"));
        assertFalse(b.rejectedWrite);
        assertTrue(b.nextOrder.contains("b"));
    }

    // --- helpers ------------------------------------------------------------

    private static LyricsDocument document(String... texts) {
        LyricsDocument doc = new LyricsDocument();
        doc.trackId = "track";
        doc.language = "ja";
        doc.type = "Line";
        long at = 0L;
        for (String text : texts) {
            LyricsLine line = new LyricsLine();
            line.text = text;
            line.startMs = at;
            line.endMs = at + 1000L;
            at += 1000L;
            doc.lines.add(line);
        }
        return doc;
    }

    private static SyllableSegment syllable(String spanId, String text, long startMs, long endMs,
                                            boolean providerPartOfWord) {
        SyllableSegment segment = new SyllableSegment();
        segment.spanId = spanId;
        segment.text = text;
        segment.startMs = startMs;
        segment.endMs = endMs;
        segment.providerPartOfWord = providerPartOfWord ? Boolean.TRUE : null;
        return segment;
    }
}
