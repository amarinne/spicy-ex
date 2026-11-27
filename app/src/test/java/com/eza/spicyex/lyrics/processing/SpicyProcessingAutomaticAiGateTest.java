package com.eza.spicyex.lyrics.processing;

import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.language.ScriptClassifier;
import com.eza.spicyex.lyrics.session.DetectionResult;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Automatic AI dispatch needs confident foreign text, not the Google gate's uncertainty. */
public class SpicyProcessingAutomaticAiGateTest {
    private static LyricsLine line(String text, String language) {
        LyricsLine line = new LyricsLine();
        line.text = text;
        line.detection = language == null
                ? DetectionResult.scriptOnly("r#" + text.hashCode(), text,
                        ScriptClassifier.ScriptClass.LATIN)
                : DetectionResult.detected("r#" + text.hashCode(), text,
                        ScriptClassifier.ScriptClass.LATIN, language, 0.95);
        return line;
    }

    private static List<LyricsLine> englishSong() {
        List<LyricsLine> lines = new ArrayList<>();
        for (int i = 0; i < 10; i++) lines.add(line("I keep thinking about you " + i, "en"));
        lines.add(line("Maybe not today", null));
        lines.add(line("Ok Next", null));
        return lines;
    }

    @Test
    public void uncertainRowsDoNotSendAnEnglishSong() {
        assertFalse(SpicyProcessing.hasConfidentTranslationWork(englishSong(), "en"));
    }

    @Test
    public void oneMisdetectedRowAndAdlibsDoNotSendAnEnglishSong() {
        List<LyricsLine> lines = englishSong();
        lines.add(line("I want you", "wa"));
        lines.add(line("Na na na na", "ig"));
        assertFalse(SpicyProcessing.hasConfidentTranslationWork(lines, "en"));
    }

    @Test
    public void repeatedConfidentForeignRowsSendTheSong() {
        List<LyricsLine> lines = englishSong();
        lines.add(line("Te quiero mucho mi amor", "es"));
        lines.add(line("No me dejes nunca", "es"));
        assertTrue(SpicyProcessing.hasConfidentTranslationWork(lines, "en"));
    }

    @Test
    public void aNonTargetScriptRowSendsTheSong() {
        List<LyricsLine> lines = englishSong();
        lines.add(line("君を愛してない", null));
        assertTrue(SpicyProcessing.hasConfidentTranslationWork(lines, "en"));
    }

    @Test
    public void romanizedLyricsWithoutAnyConfidentDetectionStayEligible() {
        List<LyricsLine> lines = new ArrayList<>();
        lines.add(line("Nae pi, ttam, nunmul, nae majimag chumeul", null));
        lines.add(line("Da gajyeoga, ga", null));
        assertTrue(SpicyProcessing.hasConfidentTranslationWork(lines, "en"));
    }

    @Test
    public void aNonLatinTargetComparesDetectedLanguage() {
        List<LyricsLine> lines = new ArrayList<>();
        for (int i = 0; i < 4; i++) lines.add(line("I keep thinking about you " + i, "en"));
        assertTrue(SpicyProcessing.hasConfidentTranslationWork(lines, "ja"));
    }
}
