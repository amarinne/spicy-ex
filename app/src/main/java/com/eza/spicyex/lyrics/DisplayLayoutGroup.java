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
                // Morphology is authoritative where it reaches, but it routinely leaves stretches
                // uncovered (an unknown word, a name, a stylised spelling). Those gaps used to have
                // no grouping at all, so the wrapper could break anywhere inside them.
                return fillJapaneseGaps(source, japanese(source, analyzed.readingContext.tokens));
            }
            // No morphological analysis for this line - script runs are a far better guess than
            // whitespace tokenisation, which on a space-less Japanese line yields one group
            // covering everything and forces the planner to give up and wrap greedily.
            List<DisplayLayoutGroup> runs = JapaneseScriptRunGrouping.forText(source);
            if (!runs.isEmpty()) return runs;
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
        if (isKorean(language, source)) {
            // Korean: use syllable-based grouping with punctuation awareness
            return koreanGroups(source);
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

    /** Groups the stretches of {@code text} no analyzer token claimed, using the same script-run
     *  heuristics as the no-analysis path, and returns the merged list in document order. */
    private static List<DisplayLayoutGroup> fillJapaneseGaps(String text, List<DisplayLayoutGroup> tokens) {
        if (tokens.isEmpty()) return tokens;
        ArrayList<DisplayLayoutGroup> merged = new ArrayList<>();
        int cursor = 0;
        for (DisplayLayoutGroup token : tokens) {
            if (token.start > cursor) addGapGroups(merged, text, cursor, token.start);
            merged.add(token);
            cursor = Math.max(cursor, token.end);
        }
        if (cursor < text.length()) addGapGroups(merged, text, cursor, text.length());
        return merged;
    }

    private static void addGapGroups(List<DisplayLayoutGroup> out, String text, int start, int end) {
        String slice = text.substring(start, end);
        if (slice.trim().isEmpty()) return;
        for (DisplayLayoutGroup group : JapaneseScriptRunGrouping.forText(slice)) {
            out.add(new DisplayLayoutGroup(start + group.start, start + group.end,
                    group.kind, group.keepTogether, group.confidence));
        }
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
        return value.equals("ja") || value.isEmpty() && SpicyTextDetection.hasKana(text);
    }

    private static boolean isChinese(String language, String text) {
        String value = language == null ? "" : language.toLowerCase();
        return value.startsWith("zh") || value.isEmpty() && SpicyTextDetection.itemChineseTest(text);
    }

    private static boolean isKorean(String language, String text) {
        String value = language == null ? "" : language.toLowerCase();
        return value.equals("ko") || value.isEmpty() && SpicyTextDetection.itemKoreanTest(text);
    }

    /** Korean syllable-based grouping with punctuation awareness.
     *  Korean doesn't use spaces between words, so we group by syllables
     *  and ensure punctuation stays with the preceding text. */
    private static List<DisplayLayoutGroup> koreanGroups(String text) {
        ArrayList<DisplayLayoutGroup> groups = new ArrayList<>();
        int start = 0;
        int syllableCount = 0;
        final int MAX_SYLLABLES_PER_GROUP = 8; // ~8 syllables per visual group
        
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);
            
            boolean isPunct = isKoreanPunctuation(cp);
            boolean isSpace = Character.isWhitespace(cp);
            
            // Count Hangul syllables (each syllable = 1 code point in precomposed form)
            if (!isPunct && !isSpace && cp >= 0xAC00 && cp <= 0xD7A3) {
                syllableCount++;
            }
            
            // Check if we should break here
            boolean shouldBreak = false;
            
            // Break after punctuation if followed by non-punctuation
            if (isPunct && i + charCount < text.length()) {
                int nextCp = text.codePointAt(i + charCount);
                if (!isKoreanPunctuation(nextCp) && !Character.isWhitespace(nextCp)) {
                    shouldBreak = true;
                }
            }
            // Break at space. Keep the space out of the previous group so authored gaps remain
            // explicit boundaries instead of swallowing the separator into the syllable run.
            else if (isSpace) {
                shouldBreak = true;
            }
            // Break at max syllable count
            else if (syllableCount >= MAX_SYLLABLES_PER_GROUP) {
                shouldBreak = true;
            }

            if (shouldBreak) {
                int end = isSpace ? i : i + charCount;
                // Extend to include following punctuation, but never absorb the separating space.
                while (end < text.length()) {
                    int nextCp = text.codePointAt(end);
                    if (isKoreanPunctuation(nextCp)) {
                        end += Character.charCount(nextCp);
                    } else {
                        break;
                    }
                }
                if (end > start) {
                    groups.add(new DisplayLayoutGroup(start, end, "ko-syllable-group", true, 0.8));
                }
                start = isSpace ? end + 1 : end;
                if (isSpace) {
                    while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
                        start++;
                    }
                }
                syllableCount = 0;
            }
            
            i += charCount;
        }
        
        // Add remaining
        if (start < text.length()) {
            groups.add(new DisplayLayoutGroup(start, text.length(), "ko-syllable-group", true, 0.8));
        }
        
        // If no groups formed, fall back to whole text
        if (groups.isEmpty()) {
            groups.add(new DisplayLayoutGroup(0, text.length(), "ko-fallback", true, 0.5));
        }
        
        return groups;
    }

    private static boolean isKoreanPunctuation(int cp) {
        return cp == 0x3000 || // ideographic space
               cp == 0x3001 || // ideographic comma
               cp == 0x3002 || // ideographic full stop
               cp == 0xFF01 || // fullwidth exclamation
               cp == 0xFF0C || // fullwidth comma
               cp == 0xFF0E || // fullwidth period
               cp == 0xFF1A || // fullwidth colon
               cp == 0xFF1B || // fullwidth semicolon
               cp == 0xFF1F || // fullwidth question mark
               cp == 0x2018 || cp == 0x2019 || // single quotes
               cp == 0x201C || cp == 0x201D || // double quotes
               cp == 0x3008 || cp == 0x3009 || // angle brackets
               cp == 0x300A || cp == 0x300B || // double angle brackets
               cp == 0x300C || cp == 0x300D || // corner brackets
               cp == 0x300E || cp == 0x300F || // white corner brackets
               cp == 0x3010 || cp == 0x3011 || // lenticular brackets
               cp == 0xFF08 || cp == 0xFF09 || // fullwidth parentheses
               cp == 0xFF3B || cp == 0xFF3D || // fullwidth brackets
               cp >= 0xFE30 && cp <= 0xFE6B; // various CJK punctuation
    }
}
