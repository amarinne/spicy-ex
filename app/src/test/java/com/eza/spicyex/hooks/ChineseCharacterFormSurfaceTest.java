package com.eza.spicyex.hooks;

import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.LyricTimeline;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;
import com.eza.spicyex.lyrics.language.ChineseCharacterForm;
import com.eza.spicyex.lyrics.language.ScriptClassifier;
import com.eza.spicyex.lyrics.session.DetectionResult;
import com.eza.spicyex.lyrics.processing.LyricsDocumentProcessor;
import com.eza.spicyex.lyrics.reading.ReadingPlanFactory;

import org.junit.Test;

import java.util.Arrays;
import java.util.function.BiFunction;

import static org.junit.Assert.*;

public class ChineseCharacterFormSurfaceTest {
    @Test
    public void autoAndBridgeProjectOwnedCopiesWithoutChangingSessionSource() {
        LyricsDocument source = source();
        AppliedLine sessionRow = source.appliedLines.get(0);
        String canonical = LyricsDocumentProcessor.canonicalBaseOf(source).digest;
        for (BiFunction<LyricsDocument, ChineseCharacterForm, LyricsDocument> project : Arrays
                .<BiFunction<LyricsDocument, ChineseCharacterForm, LyricsDocument>>asList(
                        AutoPrototypePublisher::projectDocument,
                        SpicyLyricBridgeCoordinator::projectDocument)) {
            LyricsDocument projected = project.apply(source, ChineseCharacterForm.TRADITIONAL);
            assertNotSame(source, projected);
            assertNotSame(source.lines.get(0), projected.lines.get(0));
            assertEquals("漢", projected.appliedLines.get(0).text);
            assertEquals("漢", projected.appliedLines.get(0).words.get(0).text);
            assertEquals("汉", projected.lines.get(0).text);
            assertEquals("汉", projected.lines.get(0).syllables.get(0).text);
            assertEquals("hàn", projected.lines.get(0).readingRenderPlan.joinedDisplayText);
            assertEquals("Chinese", projected.appliedLines.get(0).translatedText);
            assertEquals(1000L, projected.appliedLines.get(0).words.get(0).startMs);
            assertEquals(2000L, projected.appliedLines.get(0).words.get(0).endMs);
            assertEquals("han", projected.appliedLines.get(0).words.get(0).spanId);
            assertEquals(canonical, LyricsDocumentProcessor.canonicalBaseOf(projected).digest);
        }
        assertEquals(ChineseCharacterForm.ORIGINAL, source.chineseCharacterForm);
        assertSame(sessionRow, source.appliedLines.get(0));
        assertEquals("汉", sessionRow.text);
        assertEquals(canonical, LyricsDocumentProcessor.canonicalBaseOf(source).digest);
    }

    @Test
    public void nowPlayingReconcilesAWorkerSnapshotWithTheLatestSettingAndRestoresOff() {
        LyricsDocument prepared = AutoPrototypePublisher.projectDocument(
                source(), ChineseCharacterForm.SIMPLIFIED);
        assertTrue(NowPlayingLyricController.applyCharacterForm(prepared, ChineseCharacterForm.TRADITIONAL));
        assertEquals("漢", prepared.appliedLines.get(0).text);
        assertFalse(NowPlayingLyricController.applyCharacterForm(prepared, ChineseCharacterForm.TRADITIONAL));
        assertTrue(NowPlayingLyricController.applyCharacterForm(prepared, ChineseCharacterForm.ORIGINAL));
        assertEquals("汉", prepared.appliedLines.get(0).text);
        assertEquals("hàn", prepared.lines.get(0).readingRenderPlan.joinedDisplayText);
        assertEquals("汉", prepared.lines.get(0).syllables.get(0).text);
    }

    @Test
    public void compactCardConsumesOnlyActiveProjectedBackgroundRowsFromTheSameLead() {
        LyricsDocument document = source();
        AppliedLine lead = document.appliedLines.get(0);
        com.eza.spicyex.lyrics.BackgroundLine canonicalBackground = new com.eza.spicyex.lyrics.BackgroundLine();
        canonicalBackground.text = "汉";
        lead.sourceLine.backgroundLines.add(canonicalBackground);
        AppliedLine background = new AppliedLine();
        background.sourceLine = lead.sourceLine;
        background.bgLine = true;
        background.text = "漢";
        background.startMs = 1200;
        background.endMs = 1800;
        document.appliedLines.add(background);
        AppliedLine unrelated = new AppliedLine();
        unrelated.sourceLine = new LyricsLine();
        unrelated.bgLine = true;
        unrelated.text = "Other vocal";
        unrelated.startMs = 1000;
        unrelated.endMs = 2000;
        document.appliedLines.add(unrelated);
        assertEquals(Arrays.asList(background),
                NowPlayingLyricController.activeBackgroundRows(document, lead, 1500));
        assertTrue(NowPlayingLyricController.activeBackgroundRows(document, lead, 1800).isEmpty());
        assertEquals("汉", canonicalBackground.text);
        assertEquals("漢", NowPlayingLyricController.activeBackgroundRows(document, lead, 1500).get(0).text);
    }

    @Test
    public void bridgePublishesFormOnlyChangesWhileTheCanonicalFingerprintStaysStable() {
        LyricsDocument source = source();
        String canonicalFingerprint = LyricsDocumentProcessor.publicationFingerprint(source);
        String off = SpicyLyricBridgeDocumentSerializer.publicationFingerprint(source);
        source.chineseCharacterForm = ChineseCharacterForm.SIMPLIFIED;
        String simplified = SpicyLyricBridgeDocumentSerializer.publicationFingerprint(source);
        source.chineseCharacterForm = ChineseCharacterForm.TRADITIONAL;
        String traditional = SpicyLyricBridgeDocumentSerializer.publicationFingerprint(source);
        assertNotEquals(off, simplified);
        assertNotEquals(off, traditional);
        assertNotEquals(simplified, traditional);
        assertEquals(canonicalFingerprint, LyricsDocumentProcessor.publicationFingerprint(source));
        source.chineseCharacterForm = ChineseCharacterForm.ORIGINAL;
        assertEquals(off, SpicyLyricBridgeDocumentSerializer.publicationFingerprint(source));
    }

    @Test
    public void bridgeRejectsAnOvertakenFormEvenWhenReturningToAnAlreadyPublishedFingerprint() {
        // A return to Off can skip serialization because Off was already published. The pending
        // Traditional snapshot must still be rejected even though its revision did not change.
        assertFalse(SpicyLyricBridgeCoordinator.shouldPublishSerializedDocument(
                true, 7, 7, 3L, 3L, ChineseCharacterForm.TRADITIONAL, ChineseCharacterForm.ORIGINAL));
        assertTrue(SpicyLyricBridgeCoordinator.shouldPublishSerializedDocument(
                true, 7, 7, 3L, 3L, ChineseCharacterForm.ORIGINAL, ChineseCharacterForm.ORIGINAL));
        assertFalse(SpicyLyricBridgeCoordinator.shouldPublishSerializedDocument(
                true, 7, 7, 3L, 4L, ChineseCharacterForm.ORIGINAL, ChineseCharacterForm.ORIGINAL));
    }

    @Test public void bridgePublishesMainGlyphChangesFromDetectionWithUnchangedSound() {
        LyricsDocument source = source();
        source.chineseCharacterForm = ChineseCharacterForm.TRADITIONAL;
        source.lines.get(0).detection = DetectionResult.unknown("row", "汉");
        LyricTimeline.applySyncedRows(source);
        String canonical = LyricsDocumentProcessor.publicationFingerprint(source);
        String unresolved = SpicyLyricBridgeDocumentSerializer.publicationFingerprint(source);
        source.lines.get(0).detection = DetectionResult.detected("row", "汉",
                ScriptClassifier.ScriptClass.CHINESE, "zh", 1);
        LyricTimeline.applySyncedRows(source);
        assertEquals("漢", source.appliedLines.get(0).text);
        assertEquals(canonical, LyricsDocumentProcessor.publicationFingerprint(source));
        assertNotEquals(unresolved, SpicyLyricBridgeDocumentSerializer.publicationFingerprint(source));
    }

    private static LyricsDocument source() {
        LyricsDocument document = new LyricsDocument();
        document.type = "Syllable";
        document.language = "zh";
        document.detectedChinese = true;
        LyricsLine line = new LyricsLine();
        line.text = "汉";
        line.translatedText = "Chinese";
        line.startMs = 1000L;
        line.endMs = 2000L;
        SyllableSegment word = new SyllableSegment();
        word.spanId = "han";
        word.text = "汉";
        word.sourceText = "汉";
        word.canonicalStartCp = 0;
        word.canonicalEndCp = 1;
        word.startMs = 1000L;
        word.endMs = 2000L;
        line.syllables.add(word);
        line.readingRenderPlan = ReadingPlanFactory.lineFallback(line, "hàn", "local");
        document.lines.add(line);
        LyricTimeline.applySyncedRows(document);
        return document;
    }
}
