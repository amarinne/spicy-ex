package com.eza.spicyex.lyrics;

import java.util.ArrayList;
import java.util.List;
import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;
import com.eza.spicyex.lyrics.reading.ReadingPlanFactory;

/**
 * Main-thread patch for derived-layer changes computed off-thread.
 *
 * <p>Flags are layer-scoped: a patch touches only the flags of the layer that produced it. The
 * Sound and Meaning lanes run concurrently, so a Sound patch must never clear a Meaning flag or
 * vice versa. {@code processingPending} is recomputed from both layer flags after every apply.
 */
public final class LyricsProcessingPatch {
    private final ArrayList<LinePatch> linePatches = new ArrayList<>();
    private Boolean romanizationPending;
    private Boolean translationPending;
    private Boolean includesRomanization;
    private Boolean includesTranslation;
    public int changed;

    /** Declares the Sound layer's flags. Leaves Meaning flags untouched. */
    public LyricsProcessingPatch setSoundFlags(boolean pending, boolean includes) {
        romanizationPending = pending;
        includesRomanization = includes;
        return this;
    }

    /** Declares the Meaning layer's flags. Leaves Sound flags untouched. */
    public LyricsProcessingPatch setMeaningFlags(boolean pending, boolean includes) {
        translationPending = pending;
        includesTranslation = includes;
        return this;
    }

    public void addLinePatch(LinePatch patch) {
        if (patch != null) linePatches.add(patch);
    }

    public boolean hasLineChanges() {
        return !linePatches.isEmpty();
    }

    public void applyTo(LyricsDocument document) {
        if (document == null) return;
        if (romanizationPending != null) document.romanizationPending = romanizationPending;
        if (translationPending != null) document.translationPending = translationPending;
        if (includesRomanization != null) document.includesRomanization = includesRomanization;
        if (includesTranslation != null) document.includesTranslation = includesTranslation;
        document.processingPending = document.romanizationPending || document.translationPending;
        for (LinePatch patch : linePatches) patch.applyTo(document);
    }

    /** Sound delta for one line: reading plan, legacy romaji fallback, and span readings. */
    public static LinePatch soundLine(int index, LyricsLine line) {
        if (line == null) return null;
        LinePatch patch = new LinePatch(index);
        patch.readingRenderPlan = line.readingRenderPlan != null ? line.readingRenderPlan
                : ReadingPlanFactory.lineFallback(line, safe(line.romanizedText), "remoteFallback");
        patch.romanizedText = patch.readingRenderPlan == null ? safe(line.romanizedText) : "";
        patch.japaneseReading = line.japaneseReading;
        patch.chineseMode = safe(line.chineseMode);
        if (patch.readingRenderPlan == null) {
            patch.syllableRomanizedText = new ArrayList<>();
            for (SyllableSegment seg : line.syllables) {
                patch.syllableRomanizedText.add(seg == null ? "" : safe(seg.romanizedText));
            }
        }
        return patch;
    }

    public static final class LinePatch {
        private final int index;
        private String romanizedText;
        private String translatedText;
        private SpicyJapaneseChineseProcessor.JapaneseReading japaneseReading;
        private String chineseMode;
        private List<String> syllableRomanizedText;
        private RenderPlan readingRenderPlan;

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
            if (readingRenderPlan != null) target.readingRenderPlan = readingRenderPlan;
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
