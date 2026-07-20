package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.reading.CodePointRanges;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Display-only lexical ranges. Timing ownership stays on {@link SyllableSegment}. */
public final class DisplayLayoutGroup {
    public final int start;
    public final int end;
    public final String kind;
    public final boolean keepTogether;
    public final double confidence;

    public DisplayLayoutGroup(int start, int end, String kind, boolean keepTogether, double confidence) {
        this.start = Math.max(0, start);
        this.end = Math.max(this.start, end);
        this.kind = kind == null ? "fallback" : kind;
        this.keepTogether = keepTogether;
        this.confidence = confidence;
    }

    public static List<DisplayLayoutGroup> forLine(
            String language,
            String text,
            SpicyJapaneseChineseProcessor.JapaneseReading reading
    ) {
        String source = text == null ? "" : text;
        if (source.isEmpty()) return Collections.emptyList();
        if (isJapanese(language, source)) {
            SpicyJapaneseChineseProcessor.JapaneseReading analyzed = reading != null
                    ? reading : SpicyJapaneseChineseProcessor.analyzeJapaneseLine(source, null);
            if (analyzed != null && analyzed.readingContext != null
                    && analyzed.readingContext.tokens != null && !analyzed.readingContext.tokens.isEmpty()) {
                return japanese(source, analyzed.readingContext.tokens);
            }
        }
        if (isChinese(language, source)) {
            List<DisplayLayoutGroup> icu = icuChineseGroups(source);
            if (!icu.isEmpty()) return extendClosingPunctuation(source, icu);
            List<int[]> ranges = SpicyJapaneseChineseProcessor.chineseLayoutRanges(source);
            if (ranges != null && !ranges.isEmpty()) {
                ArrayList<DisplayLayoutGroup> groups = new ArrayList<>();
                for (int[] range : ranges) {
                    if (range == null || range.length < 2 || range[1] <= range[0]) continue;
                    String value = source.substring(range[0], Math.min(source.length(), range[1]));
                    if (isClosingPunctuation(value) && !groups.isEmpty()
                            && groups.get(groups.size() - 1).end == range[0]) {
                        DisplayLayoutGroup previous = groups.remove(groups.size() - 1);
                        groups.add(new DisplayLayoutGroup(previous.start, range[1], previous.kind, true,
                                previous.confidence));
                    } else {
                        groups.add(new DisplayLayoutGroup(range[0], range[1],
                                "zh-pronunciation-phrase", true, 0.7));
                    }
                }
                if (!groups.isEmpty()) return groups;
            }
        }
        return whitespaceGroups(source);
    }

    private static List<DisplayLayoutGroup> japanese(
            String text,
            List<JapaneseReadingPolicyModels.ReadingTokenEvidence> tokens
    ) {
        ArrayList<DisplayLayoutGroup> groups = new ArrayList<>();
        int cursor = 0;
        for (JapaneseReadingPolicyModels.ReadingTokenEvidence token : tokens) {
            if (token == null || token.canonicalRange == null || token.surface == null || token.surface.isEmpty()) continue;
            int start = text.indexOf(token.surface, cursor);
            if (start < 0) {
                start = CodePointRanges.codePointOffsetToUtf16Index(text, token.canonicalRange.start);
            }
            int end = start + token.surface.length();
            if (start < 0 || end > text.length() || end <= start) continue;
            boolean attach = isJapaneseAttachToken(token);
            if (attach && !groups.isEmpty() && groups.get(groups.size() - 1).end == start) {
                DisplayLayoutGroup previous = groups.remove(groups.size() - 1);
                groups.add(new DisplayLayoutGroup(previous.start, end, "ja-lexeme", true,
                        Math.min(previous.confidence, 0.95)));
            } else {
                groups.add(new DisplayLayoutGroup(start, end, "ja-token", true, 0.95));
            }
            cursor = end;
        }
        return groups.isEmpty() ? whitespaceGroups(text) : groups;
    }

    private static boolean isJapaneseAttachToken(JapaneseReadingPolicyModels.ReadingTokenEvidence token) {
        String pos = token.pos1 == null ? "" : token.pos1;
        return "助詞".equals(pos) || "助動詞".equals(pos) || "接尾辞".equals(pos)
                || "補助記号".equals(pos) && isClosingPunctuation(token.surface);
    }

    private static boolean isClosingPunctuation(String value) {
        return value != null && value.length() == 1 && "、。，．！？!?」』）】〉》〕］)".contains(value);
    }

    private static List<DisplayLayoutGroup> extendClosingPunctuation(
            String text, List<DisplayLayoutGroup> input) {
        ArrayList<DisplayLayoutGroup> groups = new ArrayList<>();
        for (DisplayLayoutGroup group : input) {
            int end = group.end;
            while (end < text.length()) {
                int cp = text.codePointAt(end);
                String value = new String(Character.toChars(cp));
                if (!isClosingPunctuation(value)) break;
                end += Character.charCount(cp);
            }
            groups.add(new DisplayLayoutGroup(group.start, end, group.kind,
                    group.keepTogether, group.confidence));
        }
        return groups;
    }

    /** Android ICU uses dictionary word breaking for Chinese. Reflection keeps JVM tests portable. */
    private static List<DisplayLayoutGroup> icuChineseGroups(String text) {
        ArrayList<DisplayLayoutGroup> groups = new ArrayList<>();
        try {
            Class<?> type = Class.forName("android.icu.text.BreakIterator");
            Object iterator = type.getMethod("getWordInstance", Locale.class).invoke(null, Locale.CHINESE);
            type.getMethod("setText", String.class).invoke(iterator, text);
            int done = type.getField("DONE").getInt(null);
            int start = (Integer) type.getMethod("first").invoke(iterator);
            while (true) {
                int end = (Integer) type.getMethod("next").invoke(iterator);
                if (end == done) break;
                if (end > start && containsWordCharacter(text, start, end)) {
                    groups.add(new DisplayLayoutGroup(start, end, "zh-icu-word", true, 0.9));
                }
                start = end;
            }
        } catch (Throwable ignored) {
            groups.clear();
        }
        return groups;
    }

    private static boolean containsWordCharacter(String text, int start, int end) {
        for (int i = start; i < end;) {
            int cp = text.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static List<DisplayLayoutGroup> whitespaceGroups(String text) {
        ArrayList<DisplayLayoutGroup> groups = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                if (start >= 0) groups.add(new DisplayLayoutGroup(start, i, "space-token", true, 1.0));
                start = -1;
            } else if (start < 0) {
                start = i;
            }
        }
        if (start >= 0) groups.add(new DisplayLayoutGroup(start, text.length(), "fallback", true, 0.5));
        return groups;
    }

    private static boolean isJapanese(String language, String text) {
        String value = language == null ? "" : language.toLowerCase();
        return value.startsWith("ja") || SpicyTextDetection.hasKana(text);
    }

    private static boolean isChinese(String language, String text) {
        String value = language == null ? "" : language.toLowerCase();
        return value.startsWith("zh") || SpicyTextDetection.itemChineseTest(text);
    }
}
