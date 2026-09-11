package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import org.junit.Test;

import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.LayerAuthority;
import com.eza.spicyex.lyrics.session.LayerFailure;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.LayerProvenance;
import com.eza.spicyex.lyrics.session.LayerState;
import com.eza.spicyex.lyrics.session.LayerStatus;
import com.eza.spicyex.lyrics.session.LayerConfigIds;
import com.eza.spicyex.lyrics.session.LyricSession;
import com.eza.spicyex.lyrics.session.MeaningArtifact;
import com.eza.spicyex.lyrics.session.MeaningEntry;
import com.eza.spicyex.lyrics.session.SoundArtifact;
import com.eza.spicyex.lyrics.session.SoundEntry;

/**
 * Phases 2 and 3: the Sound and Meaning lanes must not share readiness, flags, or fate.
 */
public class DerivedLaneIsolationTest {

    private static final String SOUND_CONFIG =
            LayerConfigIds.sound(true, RomanizationOptions.DEFAULTS.cacheKey(), "ja", 3);
    private static final String MEANING_CONFIG =
            LayerConfigIds.meaning(true, "google_unofficial", "en", "auto", "auto", "google_draft");

    // --- layer-scoped patches ------------------------------------------------

    @Test
    public void aSoundPatchNeverClearsTheMeaningPendingFlag() {
        LyricsDocument doc = document("ichi");
        doc.romanizationPending = true;
        doc.translationPending = true;
        doc.processingPending = true;

        new LyricsProcessingPatch().setSoundFlags(false, true).applyTo(doc);

        assertFalse(doc.romanizationPending);
        assertTrue(doc.includesRomanization);
        assertTrue("Meaning must still be pending", doc.translationPending);
        assertFalse(doc.includesTranslation);
        assertTrue(doc.processingPending);
    }

    @Test
    public void aMeaningPatchNeverClearsTheSoundPendingFlag() {
        LyricsDocument doc = document("ichi");
        doc.romanizationPending = true;
        doc.translationPending = true;
        doc.processingPending = true;

        new LyricsProcessingPatch().setMeaningFlags(false, true).applyTo(doc);

        assertTrue("Sound must still be pending", doc.romanizationPending);
        assertFalse(doc.translationPending);
        assertTrue(doc.includesTranslation);
        assertFalse(doc.includesRomanization);
        assertTrue(doc.processingPending);
    }

    @Test
    public void bothLanesLandingInEitherOrderClearProcessingExactlyOnce() {
        for (boolean soundFirst : new boolean[] {true, false}) {
            LyricsDocument doc = document("ichi");
            doc.romanizationPending = true;
            doc.translationPending = true;
            doc.processingPending = true;
            LyricsProcessingPatch sound = new LyricsProcessingPatch().setSoundFlags(false, true);
            LyricsProcessingPatch meaning = new LyricsProcessingPatch().setMeaningFlags(false, true);

            if (soundFirst) {
                sound.applyTo(doc);
                assertTrue(doc.processingPending);
                meaning.applyTo(doc);
            } else {
                meaning.applyTo(doc);
                assertTrue(doc.processingPending);
                sound.applyTo(doc);
            }

            assertFalse(doc.processingPending);
            assertTrue(doc.includesRomanization);
            assertTrue(doc.includesTranslation);
        }
    }

    @Test
    public void aPatchWithNoLayerFlagsLeavesEveryFlagAlone() {
        LyricsDocument doc = document("ichi");
        doc.romanizationPending = true;
        doc.translationPending = false;
        doc.includesTranslation = true;

        LyricsProcessingPatch.LinePatch line = new LyricsProcessingPatch.LinePatch(0);
        line.setTranslatedText("one");
        LyricsProcessingPatch patch = new LyricsProcessingPatch();
        patch.addLinePatch(line);
        patch.applyTo(doc);

        assertTrue(doc.romanizationPending);
        assertFalse(doc.translationPending);
        assertTrue(doc.includesTranslation);
        assertEquals("one", doc.lines.get(0).translatedText);
    }

    @Test
    public void aSoundLineDeltaCarriesNoTranslationAndViceVersa() {
        LyricsLine source = new LyricsLine();
        source.text = "kana";
        source.romanizedText = "romaji";
        source.translatedText = "meaning";

        LyricsDocument doc = document("kana");
        doc.lines.get(0).translatedText = "existing";
        LyricsProcessingPatch patch = new LyricsProcessingPatch();
        patch.addLinePatch(LyricsProcessingPatch.soundLine(0, source));
        patch.applyTo(doc);

        assertEquals("existing", doc.lines.get(0).translatedText);

        LyricsDocument other = document("kana");
        other.lines.get(0).romanizedText = "existing-reading";
        LyricsProcessingPatch meaningPatch = new LyricsProcessingPatch();
        LyricsProcessingPatch.LinePatch meaningLine = new LyricsProcessingPatch.LinePatch(0);
        meaningLine.setTranslatedText("one");
        meaningPatch.addLinePatch(meaningLine);
        meaningPatch.applyTo(other);

        assertEquals("existing-reading", other.lines.get(0).romanizedText);
        assertNull(other.lines.get(0).readingRenderPlan);
    }

    // --- Meaning backend contract -------------------------------------------

    @Test
    public void onlyTheSelectedBackendClaimsMeaningWork() {
        LyricsMeaningLane.MeaningProvider google = new LyricsMeaningLane.GoogleMeaningProvider();

        assertEquals("google_unofficial", google.backendId());
        assertTrue(google.handles("google_unofficial"));
        assertTrue(google.handles("GOOGLE_UNOFFICIAL"));
        assertFalse(google.handles("disabled"));
        assertFalse(google.handles(""));
        assertFalse(google.handles(null));
    }

    @Test
    public void aPartialTranslationPassIsNotComplete() {
        assertFalse(LyricsMeaningLane.translationPassComplete(true,
                Arrays.asList(1, 2), new HashSet<>(Collections.singletonList(1))));
        assertTrue(LyricsMeaningLane.translationPassComplete(true,
                Arrays.asList(1, 2), new HashSet<>(Arrays.asList(1, 2))));
        assertTrue(LyricsMeaningLane.translationPassComplete(false,
                Arrays.asList(1, 2), Collections.<Integer>emptySet()));
    }

    @Test
    public void surfaceCachePassNeverOverwritesAnAiAuthority() {
        LyricsDocument aiSound = document("เพลง");
        aiSound.readingFromAi = true;
        LyricsDocument aiMeaning = document("เพลง");
        aiMeaning.translationFromAi = true;

        assertFalse(LyricsDocumentProcessor.shouldApplyCachedSound(aiSound, true));
        assertTrue(LyricsDocumentProcessor.shouldApplyCachedMeaning(aiSound, true));
        assertFalse(LyricsDocumentProcessor.shouldApplyCachedMeaning(aiMeaning, true));
        assertTrue(LyricsDocumentProcessor.shouldApplyCachedSound(aiMeaning, true));
        assertTrue(LyricsDocumentProcessor.shouldApplyCachedSound(aiSound, false));
        assertTrue(LyricsDocumentProcessor.shouldApplyCachedMeaning(aiMeaning, false));
    }

    @Test
    public void enabledLayerNeedsRealOutputBeforeItCanLookSelected() {
        LyricsDocument doc = document("เพลง");

        assertFalse(LyricsDocumentProcessor.hasDisplayedSound(doc));
        assertFalse(LyricsDocumentProcessor.hasDisplayedMeaning(doc));

        doc.lines.get(0).romanizedText = "phleng";
        assertTrue(LyricsDocumentProcessor.hasDisplayedSound(doc));

        doc.lines.get(0).translatedText = "song";
        assertTrue(LyricsDocumentProcessor.hasDisplayedMeaning(doc));
    }

    @Test
    public void surfacePreparationNeverReprocessesWholeLineAiOrGoogleSound() {
        LyricsDocument bare = document("เมื่อรัก");
        assertTrue(LyricsDocumentProcessor.needsSurfaceLocalRomanization(bare));

        LyricsDocument wholeLine = document("เมื่อรัก");
        wholeLine.lines.get(0).romanizedText = "muea rak";
        wholeLine.readingFromAi = true;
        assertFalse(LyricsDocumentProcessor.needsSurfaceLocalRomanization(wholeLine));

        wholeLine.readingFromAi = false;
        assertFalse("Google whole-line output has the same no-fake-alignment contract",
                LyricsDocumentProcessor.needsSurfaceLocalRomanization(wholeLine));
    }

    // --- in-place derived merge (keeps the mounted document) -----------------

    @Test
    public void derivedTextMergesOntoTheMountedDocumentWithoutReplacingIt() {
        LyricsDocument mounted = document("ichi", "ni");
        LyricsDocument published = document("ichi", "ni");
        published.lines.get(0).romanizedText = "ichi-r";
        published.lines.get(1).translatedText = "two";
        published.includesRomanization = true;
        published.includesTranslation = true;
        LyricsLine mountedFirst = mounted.lines.get(0);

        assertEquals(LyricsDocumentProcessor.DerivedMergeResult.CHANGED,
                LyricsDocumentProcessor.mergeDerivedPublication(mounted, published));

        assertSame("row objects must survive so timing and view state are preserved",
                mountedFirst, mounted.lines.get(0));
        assertEquals("ichi-r", mounted.lines.get(0).romanizedText);
        assertEquals("two", mounted.lines.get(1).translatedText);
        assertTrue(mounted.includesRomanization);
        assertTrue(mounted.includesTranslation);
    }

    @Test
    public void anIdenticalRepublicationReportsNoChange() {
        LyricsDocument mounted = document("ichi");
        mounted.lines.get(0).romanizedText = "ichi-r";
        LyricsDocument published = document("ichi");
        published.lines.get(0).romanizedText = "ichi-r";

        assertEquals(LyricsDocumentProcessor.DerivedMergeResult.UNCHANGED,
                LyricsDocumentProcessor.mergeDerivedPublication(mounted, published));
    }

    @Test
    public void aDifferentCanonicalBaseIsNeverMergedInPlace() {
        LyricsDocument mounted = document("ichi", "ni");
        LyricsDocument replacement = document("ichi", "ni", "san");
        replacement.lines.get(0).romanizedText = "ichi-r";

        assertEquals(LyricsDocumentProcessor.DerivedMergeResult.DIFFERENT_BASE,
                LyricsDocumentProcessor.mergeDerivedPublication(mounted, replacement));
        assertEquals("", mounted.lines.get(0).romanizedText);
    }

    @Test
    public void mergeRejectsMissingDocumentsAndRetimedSourcesWithoutMutation() {
        LyricsDocument mounted = document("ichi");
        LyricsDocument published = document("ichi");
        published.lines.get(0).endMs++;
        published.lines.get(0).translatedText = "one";
        published.translationAiPending = true;

        assertEquals(LyricsDocumentProcessor.DerivedMergeResult.DIFFERENT_BASE,
                LyricsDocumentProcessor.mergeDerivedPublication(null, published));
        assertEquals(LyricsDocumentProcessor.DerivedMergeResult.DIFFERENT_BASE,
                LyricsDocumentProcessor.mergeDerivedPublication(mounted, null));
        assertEquals(LyricsDocumentProcessor.DerivedMergeResult.DIFFERENT_BASE,
                LyricsDocumentProcessor.mergeDerivedPublication(mounted, published));
        assertEquals("", mounted.lines.get(0).translatedText);
        assertFalse(mounted.translationAiPending);
    }

    @Test
    public void mergeUsesDocumentIndicesAcrossNullRows() {
        LyricsDocument mounted = document("ichi", "ni");
        LyricsDocument published = document("ichi", "ni");
        mounted.lines.add(0, null);
        published.lines.add(0, null);
        LyricsLine first = mounted.lines.get(1);
        LyricsLine second = mounted.lines.get(2);
        published.lines.get(1).translatedText = "one";
        published.lines.get(2).translatedText = "two";

        assertEquals(LyricsDocumentProcessor.DerivedMergeResult.CHANGED,
                LyricsDocumentProcessor.mergeDerivedPublication(mounted, published));
        assertSame(first, mounted.lines.get(1));
        assertSame(second, mounted.lines.get(2));
        assertEquals("one", first.translatedText);
        assertEquals("two", second.translatedText);
        assertEquals(LyricsDocumentProcessor.DerivedMergeResult.UNCHANGED,
                LyricsDocumentProcessor.mergeDerivedPublication(mounted, published));
    }

    @Test
    public void mergeCarriesEachLayersPendingFlagsIndependently() {
        LyricsDocument mounted = document("ichi");
        mounted.romanizationPending = true;
        mounted.translationPending = true;
        LyricsDocument published = document("ichi");
        published.lines.get(0).romanizedText = "ichi-r";
        published.includesRomanization = true;
        published.romanizationPending = false;
        published.translationPending = true;
        published.processingPending = true;

        LyricsDocumentProcessor.mergeDerivedPublication(mounted, published);

        assertFalse(mounted.romanizationPending);
        assertTrue(mounted.translationPending);
        assertTrue(mounted.processingPending);
    }

    @Test
    public void mergeCarriesAiProvenanceAndFailureWithoutLyricTextChange() {
        LyricsDocument mounted = document("ichi");
        LyricsDocument published = document("ichi");
        published.readingFromAi = true;
        published.translationFromAi = true;
        published.readingAiPending = true;
        published.translationAiPending = true;
        published.translationAiRefinedFromGoogle = true;
        published.readingAiFailureToken = "protocol_invalid";
        published.translationAiFailureToken = "rate_limited";

        assertEquals(LyricsDocumentProcessor.DerivedMergeResult.UNCHANGED,
                LyricsDocumentProcessor.mergeDerivedPublication(mounted, published));

        assertTrue(mounted.readingFromAi);
        assertTrue(mounted.translationFromAi);
        assertTrue(mounted.readingAiPending);
        assertTrue(mounted.translationAiPending);
        assertTrue(mounted.translationAiRefinedFromGoogle);
        assertEquals("protocol_invalid", mounted.readingAiFailureToken);
        assertEquals("rate_limited", mounted.translationAiFailureToken);
    }

    @Test
    public void layerResetClearsStaleAiStatusWithItsOutput() {
        LyricsDocument doc = document("ก็ไม่รู้");
        doc.readingFromAi = true;
        doc.translationFromAi = true;
        doc.readingAiPending = true;
        doc.translationAiPending = true;
        doc.translationAiRefinedFromGoogle = true;
        doc.readingAiFailureToken = "protocol_invalid";
        doc.translationAiFailureToken = "rate_limited";

        LyricsDocumentProcessor.resetSoundLayer(null, doc);
        assertFalse(doc.readingFromAi);
        assertFalse(doc.readingAiPending);
        assertEquals("", doc.readingAiFailureToken);
        assertTrue(doc.translationFromAi);

        LyricsDocumentProcessor.resetMeaningLayer(null, doc);
        assertFalse(doc.translationFromAi);
        assertFalse(doc.translationAiPending);
        assertFalse(doc.translationAiRefinedFromGoogle);
        assertEquals("", doc.translationAiFailureToken);
    }

    @Test
    public void mergeCopiesSpanAndBackgroundReadings() {
        LyricsDocument mounted = document("ichi");
        mounted.lines.get(0).syllables.add(segment("i"));
        mounted.lines.get(0).backgroundLines.add(background("hey"));
        LyricsDocument published = document("ichi");
        published.lines.get(0).syllables.add(segment("i"));
        published.lines.get(0).syllables.get(0).romanizedText = "i-r";
        published.lines.get(0).backgroundLines.add(background("hey"));
        published.lines.get(0).backgroundLines.get(0).translatedText = "hey!";

        assertEquals(LyricsDocumentProcessor.DerivedMergeResult.CHANGED,
                LyricsDocumentProcessor.mergeDerivedPublication(mounted, published));

        assertEquals("i-r", mounted.lines.get(0).syllables.get(0).romanizedText);
        assertEquals("hey!", mounted.lines.get(0).backgroundLines.get(0).translatedText);
    }

    @Test
    public void mergeNeverBlanksWhatThePublisherDidNotProduce() {
        LyricsDocument mounted = document("ichi");
        mounted.lines.get(0).romanizedText = "ichi-r";
        mounted.lines.get(0).syllables.add(segment("i"));
        mounted.lines.get(0).syllables.get(0).romanizedText = "i-r";
        mounted.lines.get(0).translatedText = "one";
        // A Meaning-only publication carries no reading at all.
        LyricsDocument published = document("ichi");
        published.lines.get(0).syllables.add(segment("i"));
        published.lines.get(0).translatedText = "uno";

        assertEquals(LyricsDocumentProcessor.DerivedMergeResult.CHANGED,
                LyricsDocumentProcessor.mergeDerivedPublication(mounted, published));

        assertEquals("ichi-r", mounted.lines.get(0).romanizedText);
        assertEquals("i-r", mounted.lines.get(0).syllables.get(0).romanizedText);
        assertEquals("uno", mounted.lines.get(0).translatedText);
    }

    @Test
    public void aPlanAndALegacyStringAreNeverLeftSideBySide() {
        LyricsDocument mounted = document("ichi");
        mounted.lines.get(0).romanizedText = "ichi-r";
        LyricsDocument published = document("ichi");
        published.lines.get(0).readingRenderPlan = new com.eza.spicyex.lyrics.reading.ReadingModels
                .RenderPlan("l0", null, null, null, "ICHI", null);

        assertEquals(LyricsDocumentProcessor.DerivedMergeResult.CHANGED,
                LyricsDocumentProcessor.mergeDerivedPublication(mounted, published));

        assertEquals("", mounted.lines.get(0).romanizedText);
        assertEquals("ICHI", mounted.lines.get(0).readingRenderPlan.joinedDisplayText);
    }

    @Test
    public void thePublicationFingerprintTracksOnlyWhatAViewerWouldSee() {
        LyricsDocument a = document("ichi", "ni");
        LyricsDocument b = document("ichi", "ni");
        assertEquals(LyricsDocumentProcessor.publicationFingerprint(a),
                LyricsDocumentProcessor.publicationFingerprint(b));

        // A lane completing without changing displayed text must not force a republication.
        b.romanizationPending = false;
        b.processingPending = false;
        assertEquals(LyricsDocumentProcessor.publicationFingerprint(a),
                LyricsDocumentProcessor.publicationFingerprint(b));

        b.lines.get(0).romanizedText = "ichi-r";
        assertNotEquals(LyricsDocumentProcessor.publicationFingerprint(a),
                LyricsDocumentProcessor.publicationFingerprint(b));
    }

    @Test
    public void thePublicationFingerprintCoversTranslationsAndSpanReadings() {
        LyricsDocument base = document("ichi");
        base.lines.get(0).syllables.add(segment("i"));
        String before = LyricsDocumentProcessor.publicationFingerprint(base);

        base.lines.get(0).translatedText = "one";
        String afterTranslation = LyricsDocumentProcessor.publicationFingerprint(base);
        assertNotEquals(before, afterTranslation);

        base.lines.get(0).syllables.get(0).romanizedText = "i-r";
        assertNotEquals(afterTranslation, LyricsDocumentProcessor.publicationFingerprint(base));
    }

    @Test
    public void spanReadingsFromTheSessionAreDetectedSoSurfacesDoNotRederive() {
        LyricsDocument bare = document("ichi");
        bare.lines.get(0).syllables.add(segment("i"));
        assertFalse(LyricsDocumentProcessor.hasSpanReadings(bare));

        // Line-level reading alone is not span reading: the surface still has its own pass to do.
        bare.lines.get(0).romanizedText = "ichi-r";
        assertFalse(LyricsDocumentProcessor.hasSpanReadings(bare));

        bare.lines.get(0).syllables.get(0).romanizedText = "i-r";
        assertTrue(LyricsDocumentProcessor.hasSpanReadings(bare));
        assertFalse(LyricsDocumentProcessor.hasSpanReadings(null));
    }

    // --- failure isolation ---------------------------------------------------

    @Test
    public void everyMeaningFailureModeLeavesSoundUntouched() {
        LayerFailure[] failures = {
                LayerFailure.of(LayerFailure.Reason.TIMEOUT),
                LayerFailure.http(429),
                LayerFailure.http(404),
                LayerFailure.http(503),
                LayerFailure.of(LayerFailure.Reason.MALFORMED),
                LayerFailure.of(LayerFailure.Reason.CANCELLED)
        };
        for (LayerFailure failure : failures) {
            LyricSession session = sessionWithBothLayers();
            String soundDigest = session.sound.artifactDigest;

            LyricSession afterFailure = session.withMeaning(
                    session.meaning.processing(LayerAuthority.MACHINE, MEANING_CONFIG, "", "run-1")
                            .failed(failure));

            assertEquals(soundDigest, afterFailure.sound.artifactDigest);
            assertTrue(afterFailure.sound.hasArtifact());
            assertEquals(LayerStatus.READY, afterFailure.sound.status);
            assertTrue(afterFailure.meaning.failure.isFailure());
        }
    }

    @Test
    public void meaningFailureClassificationMapsHttpStatuses() {
        assertEquals(LayerFailure.Reason.RATE_LIMITED, LayerFailure.http(429).reason);
        assertEquals(LayerFailure.Reason.CLIENT_ERROR, LayerFailure.http(400).reason);
        assertEquals(LayerFailure.Reason.CLIENT_ERROR, LayerFailure.http(404).reason);
        assertEquals(LayerFailure.Reason.SERVER_ERROR, LayerFailure.http(500).reason);
        assertEquals(LayerFailure.Reason.SERVER_ERROR, LayerFailure.http(503).reason);
        assertFalse(LayerFailure.NONE.isFailure());
    }

    @Test
    public void aFailureRecordCarriesNoProviderPayload() {
        LayerFailure failure = LayerFailure.http(429);

        assertEquals("", failure.detail);
        assertEquals(429, failure.httpStatus);
    }

    @Test
    public void soundFailureLeavesMeaningUntouched() {
        LyricSession session = sessionWithBothLayers();
        String meaningDigest = session.meaning.artifactDigest;

        LyricSession afterFailure = session.withSound(
                session.sound.processing(LayerAuthority.DETERMINISTIC, SOUND_CONFIG, "", "run-1")
                        .failed(LayerFailure.of(LayerFailure.Reason.UNAVAILABLE)));

        assertEquals(meaningDigest, afterFailure.meaning.artifactDigest);
        assertEquals(LayerStatus.READY, afterFailure.meaning.status);
    }

    // --- helpers -------------------------------------------------------------

    private static LyricSession sessionWithBothLayers() {
        LyricsDocument doc = document("ichi", "ni");
        LyricSession session = LyricSession.of(CanonicalBase.fromDocument("spotify:track:a", doc), 1);
        String row0 = session.base.rows.get(0).rowId;
        LayerProvenance provenance = new LayerProvenance(LayerAuthority.DETERMINISTIC, "local", "c1", 0L);
        SoundArtifact sound = new SoundArtifact(session.base.digest, SOUND_CONFIG, provenance,
                Collections.singletonList(SoundEntry.line(row0, "ichi-r", "romaji")), false);
        MeaningArtifact meaning = new MeaningArtifact(session.base.digest, MEANING_CONFIG, provenance,
                Collections.singletonList(new MeaningEntry(row0, "one", "en")), false);
        return session
                .withSound(session.sound.withArtifact(LayerStatus.READY, sound, "run-sound"))
                .withMeaning(session.meaning.withArtifact(LayerStatus.READY, meaning, "run-meaning"));
    }

    private static SyllableSegment segment(String text) {
        SyllableSegment seg = new SyllableSegment();
        seg.text = text;
        return seg;
    }

    private static BackgroundLine background(String text) {
        BackgroundLine line = new BackgroundLine();
        line.text = text;
        return line;
    }

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
}
