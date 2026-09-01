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
        return !segment.boundaryAfter;
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
