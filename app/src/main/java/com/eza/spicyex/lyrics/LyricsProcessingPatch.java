package com.eza.spicyex.lyrics;

import java.util.ArrayList;
import java.util.List;

/** Main-thread patch for secondary processing changes computed off-thread. */
public final class LyricsProcessingPatch {
    private final ArrayList<LinePatch> linePatches = new ArrayList<>();
    public boolean romanizationPending;
    public boolean translationPending;
    public boolean processingPending;
    public boolean includesRomanization;
    public boolean includesTranslation;
    public int changed;

    public void addLinePatch(LinePatch patch) {
        if (patch != null) linePatches.add(patch);
    }

    public boolean hasLineChanges() {
        return !linePatches.isEmpty();
    }

    public void applyTo(LyricsDocument document) {
        if (document == null) return;
        document.romanizationPending = romanizationPending;
        document.translationPending = translationPending;
        document.processingPending = processingPending;
        document.includesRomanization = includesRomanization;
        document.includesTranslation = includesTranslation;
        for (LinePatch patch : linePatches) patch.applyTo(document);
    }

    public static LinePatch fromLine(int index, LyricsLine line, boolean includeRomanized, boolean includeTranslated) {
        if (line == null) return null;
        LinePatch patch = new LinePatch(index);
        if (includeRomanized) {
            patch.romanizedText = safe(line.romanizedText);
            patch.japaneseReading = line.japaneseReading;
            patch.chineseMode = safe(line.chineseMode);
            patch.syllableRomanizedText = new ArrayList<>();
            for (SyllableSegment seg : line.syllables) {
                patch.syllableRomanizedText.add(seg == null ? "" : safe(seg.romanizedText));
            }
        }
        if (includeTranslated) patch.translatedText = safe(line.translatedText);
        return patch;
    }

    public static final class LinePatch {
        private final int index;
        private String romanizedText;
        private String translatedText;
        private SpicyJapaneseChineseProcessor.JapaneseReading japaneseReading;
        private String chineseMode;
        private List<String> syllableRomanizedText;

        public LinePatch(int index) {
            this.index = index;
        }

        public void setRomanizedText(String romanizedText) {
            this.romanizedText = safe(romanizedText);
        }

        public void setTranslatedText(String translatedText) {
            this.translatedText = safe(translatedText);
        }

        private void applyTo(LyricsDocument document) {
            if (index < 0 || document.lines == null || index >= document.lines.size()) return;
            LyricsLine target = document.lines.get(index);
            if (target == null) return;
            if (romanizedText != null) target.romanizedText = romanizedText;
            if (translatedText != null) target.translatedText = translatedText;
            if (japaneseReading != null) target.japaneseReading = japaneseReading;
            if (chineseMode != null) target.chineseMode = chineseMode;
            if (syllableRomanizedText != null && target.syllables != null) {
                int count = Math.min(target.syllables.size(), syllableRomanizedText.size());
                for (int i = 0; i < count; i++) {
                    SyllableSegment seg = target.syllables.get(i);
                    if (seg != null) seg.romanizedText = safe(syllableRomanizedText.get(i));
                }
            }
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
