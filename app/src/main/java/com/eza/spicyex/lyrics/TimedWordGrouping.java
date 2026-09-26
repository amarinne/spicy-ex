package com.eza.spicyex.lyrics;

import java.util.List;

import com.eza.spicyex.lyrics.reading.ReadingModels.TimedReadingUnit;

/** Groups provider timing fragments into one visual word without changing their timing ownership. */
final class TimedWordGrouping {
    private TimedWordGrouping() {}

    static int groupEnd(AppliedLine line, int start) {
        if (line == null || line.words == null || start < 0 || start >= line.words.size()) {
            return start;
        }
        int end = start;
        while (end + 1 < line.words.size() && attachedAfter(line, end)) end++;
        return end;
    }

    static boolean isGrouped(AppliedLine line, int index) {
        if (line == null || line.words == null || index < 0 || index >= line.words.size()) {
            return false;
        }
        return index > 0 && attachedAfter(line, index - 1)
                || index + 1 < line.words.size() && attachedAfter(line, index);
    }

    static long startMs(AppliedLine line, int groupStart) {
        if (line == null || line.words == null || groupStart < 0 || groupStart >= line.words.size()
                || line.words.get(groupStart) == null) return 0L;
        return line.words.get(groupStart).startMs;
    }

    static long endMs(AppliedLine line, int groupStart) {
        int end = groupEnd(line, groupStart);
        if (line == null || line.words == null || end < 0 || end >= line.words.size()
                || line.words.get(end) == null) return startMs(line, groupStart) + 1L;
        return Math.max(startMs(line, groupStart) + 1L, line.words.get(end).endMs);
    }

    /** Timed fragment whose local progress owns current motion focus. */
    static int focusIndex(AppliedLine line, int groupStart, int groupEnd, long positionMs) {
        if (line == null || line.words == null || groupStart < 0
                || groupStart >= line.words.size()) return groupStart;
        int safeEnd = Math.min(Math.max(groupStart, groupEnd), line.words.size() - 1);
        for (int index = groupStart; index <= safeEnd; index++) {
            SyllableSegment segment = line.words.get(index);
            if (segment != null && positionMs < segment.endMs) return index;
        }
        return safeEnd;
    }

    private static boolean attachedAfter(AppliedLine line, int index) {
        if (line.syntheticWords || index < 0 || index + 1 >= line.words.size()) return false;
        SyllableSegment segment = line.words.get(index);
        if (segment == null) return false;
        String currentGroup = logicalGroup(line, segment);
        String nextGroup = logicalGroup(line, line.words.get(index + 1));
        if (currentGroup != null && nextGroup != null) return currentGroup.equals(nextGroup);
        if (segment.providerPartOfWord != null) return segment.providerPartOfWord;
        // A line where NO segment anywhere reports a real boundary is raw character-level timing
        // with zero word-boundary information (no lexical grouping, no provider flag, no space
        // anywhere in the reconstructed provider text) - e.g. QQ's QRC content for CJK lines,
        // which naturally has no spaces between characters. Trusting !boundaryAfter there glues
        // the entire clause into one unwrappable view, same failure this method's class comment
        // already warns about for providerPartOfWord - so total absence of any boundary in the
        // line means "no grouping info available", not "this is one giant word".
        if (!lineHasAnyBoundary(line)) {
            // For Korean and other space-less languages without provider boundaries,
            // group consecutive non-punctuation segments to form words.
            String language = ReadingLanguagePolicy.layoutLanguage(line);
            if ("ko".equals(language)) {
                return !isPunctuationOrSpace(segment.text) && !isPunctuationOrSpace(line.words.get(index + 1).text);
            }
            return false;
        }
        return !segment.boundaryAfter;
    }

    private static boolean isPunctuationOrSpace(String text) {
        if (text == null || text.isEmpty()) return true;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isWhitespace(cp) || isPunctuation(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean isPunctuation(int cp) {
        return "،।，．！？!?、。，．！？!?」』）】〉》〕］)…‥〜ー".indexOf(cp) >= 0
                || Character.getType(cp) == Character.OTHER_PUNCTUATION
                || Character.getType(cp) == Character.CONNECTOR_PUNCTUATION
                || Character.getType(cp) == Character.DASH_PUNCTUATION
                || Character.getType(cp) == Character.END_PUNCTUATION
                || Character.getType(cp) == Character.FINAL_QUOTE_PUNCTUATION
                || Character.getType(cp) == Character.INITIAL_QUOTE_PUNCTUATION
                || Character.getType(cp) == Character.OTHER_PUNCTUATION
                || Character.getType(cp) == Character.START_PUNCTUATION;
    }

    private static boolean lineHasAnyBoundary(AppliedLine line) {
        if (line.words == null) return false;
        for (SyllableSegment segment : line.words) {
            if (segment != null && segment.boundaryAfter) return true;
        }
        return false;
    }

    /** Finalized reading groups are lexical owners. Provider part flags can mark every Japanese
     * syllable as attached and would otherwise turn a complete clause into one unwrappable view. */
    private static String logicalGroup(AppliedLine line, SyllableSegment segment) {
        if (line == null || line.readingRenderPlan == null
                || line.readingRenderPlan.timedReadingUnits == null || segment == null
                || segment.spanId == null || segment.spanId.isEmpty()) return null;
        String group = null;
        for (String id : segment.spanId.split("\\+")) {
            TimedReadingUnit match = null;
            for (TimedReadingUnit unit : line.readingRenderPlan.timedReadingUnits) {
                if (unit != null && id.equals(unit.spanId)) {
                    match = unit;
                    break;
                }
            }
            if (match == null || match.logicalGroupId == null || match.logicalGroupId.isEmpty()) {
                return null;
            }
            if (group == null) group = match.logicalGroupId;
            else if (!group.equals(match.logicalGroupId)) return null;
        }
        return group;
    }
}
