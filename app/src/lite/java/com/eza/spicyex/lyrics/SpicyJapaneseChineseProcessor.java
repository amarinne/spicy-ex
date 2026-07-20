package com.eza.spicyex.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Lite build stub: JP/CN dictionary-backed romanization lives in the full flavor. */
public final class SpicyJapaneseChineseProcessor {
    public static final class FuriganaSegment {
        public final int start;
        public final int end;
        public final String reading;

        public FuriganaSegment(int start, int end, String reading) {
            this.start = start;
            this.end = end;
            this.reading = reading == null ? "" : reading;
        }
    }

    public static final class ReadingGroup {
        public final int start;
        public final int end;
        public final String romaji;

        public ReadingGroup(int start, int end, String romaji) {
            this.start = start;
            this.end = end;
            this.romaji = romaji == null ? "" : romaji;
        }
    }

    public static final class JapaneseReading {
        public final String sourceText;
        public final String romaji;
        public final List<FuriganaSegment> furigana;
        public final List<ReadingGroup> groups;
        public final JapaneseReadingPolicyModels.ReadingContext readingContext;
        public final List<JapaneseReadingPolicyModels.ReadingDecision> readingDecisions;
        public final List<String> diagnostics;

        public JapaneseReading(String sourceText, String romaji, List<FuriganaSegment> furigana) {
            this(sourceText, romaji, furigana, null);
        }

        public JapaneseReading(String sourceText, String romaji, List<FuriganaSegment> furigana,
                               List<ReadingGroup> groups) {
            this(sourceText, romaji, furigana, groups, null, null, null);
        }

        public JapaneseReading(String sourceText, String romaji, List<FuriganaSegment> furigana,
                               List<ReadingGroup> groups,
                               JapaneseReadingPolicyModels.ReadingContext readingContext,
                               List<JapaneseReadingPolicyModels.ReadingDecision> readingDecisions,
                               List<String> diagnostics) {
            this.sourceText = sourceText == null ? "" : sourceText;
            this.romaji = romaji == null ? "" : romaji;
            this.furigana = immutableCopy(furigana);
            this.groups = immutableCopy(groups);
            this.readingContext = readingContext == null
                    ? JapaneseReadingPolicyModels.ReadingContext.empty(this.sourceText) : readingContext;
            this.readingDecisions = immutableCopy(readingDecisions);
            this.diagnostics = immutableCopy(diagnostics);
        }
    }

    private SpicyJapaneseChineseProcessor() {
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return Collections.unmodifiableList(values == null ? new ArrayList<>() : new ArrayList<>(values));
    }

    public static boolean canRomanizeJapanese(String text) {
        return false;
    }

    public static boolean canRomanizeChinese(String text) {
        return false;
    }

    public static JapaneseReading analyzeJapaneseLine(String text, String fullSpacedRomaji) {
        return null;
    }

    public static JapaneseReading analyzeJapaneseLineWithProviderFurigana(String text, List<FuriganaSegment> furigana) {
        return null;
    }

    public static String romanizeJapaneseLine(String text) {
        return "";
    }

    public static String romanizeJapaneseLineFromFurigana(String text, List<FuriganaSegment> furigana) {
        return "";
    }

    public static List<String> romanizeJapaneseSyllables(String lineText, List<String> syllableTexts) {
        ArrayList<String> out = new ArrayList<>();
        if (syllableTexts != null) {
            for (int i = 0; i < syllableTexts.size(); i++) out.add("");
        }
        return out;
    }

    public static List<String> romanizeJapaneseSyllables(JapaneseReading reading, List<String> syllableTexts) {
        ArrayList<String> out = new ArrayList<>();
        if (syllableTexts != null) {
            for (int i = 0; i < syllableTexts.size(); i++) out.add("");
        }
        return out;
    }

    public static String romanizeChineseLine(String text, String mode) {
        return "";
    }

    public static String romanizeChineseLine(String text, String mode, boolean tones) {
        return "";
    }

    public static List<int[]> chineseLayoutRanges(String text) {
        return new ArrayList<>();
    }
}
