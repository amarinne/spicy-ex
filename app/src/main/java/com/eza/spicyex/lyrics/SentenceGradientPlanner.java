package com.eza.spicyex.lyrics;

import java.util.List;

/** Follows provider timing across a sentence, independently of coalesced display words. */
public final class SentenceGradientPlanner {
    private SentenceGradientPlanner() { }

    /** Shared timing cursor used by render adapters to select display pages. */
    public static float sourceProgress(AppliedLine line, long positionMs) {
        return (gradientPosition(line, positionMs) - LyricAnimations.GRADIENT_UNSUNG)
                / LyricAnimations.GRADIENT_RANGE;
    }

    static float gradientPosition(AppliedLine line, long positionMs) {
        if (line == null) return LyricAnimations.GRADIENT_UNSUNG;
        if (line.gradientSource != null) {
            AppliedLine source = line.gradientSource;
            int length = source.text.codePointCount(0, source.text.length());
            return SentenceFill.lineGradient(gradientPosition(source, positionMs), length,
                    line.gradientStartCp, line.gradientEndCp - line.gradientStartCp);
        }
        long fillEnd = line.bgLine ? line.endMs : LyricTimeline.fillEndMs(line);
        List<SyllableSegment> spans = line.bgLine || line.sourceLine == null
                ? null : line.sourceLine.syllables;
        if (!hasTiming(spans)) spans = line.syntheticWords ? null : line.words;
        if (!hasTiming(spans)
                || LyricsFrameRenderer.hasDegenerateWordTiming(spans, fillEnd - line.startMs)) {
            return LyricAnimations.gradientPosition(progress(positionMs, line.startMs,
                    fillEnd));
        }
        int textLength = line.text == null ? 0 : line.text.codePointCount(0, line.text.length());
        int length = 0;
        long lastEnd = Long.MIN_VALUE;
        boolean canonical = true;
        for (SyllableSegment span : spans) {
            if (span == null) continue;
            canonical &= span.canonicalStartCp >= 0 && span.canonicalEndCp > span.canonicalStartCp
                    && span.canonicalEndCp <= textLength;
            if (span.endMs >= span.startMs) lastEnd = Math.max(lastEnd, span.endMs);
            length += textLength(span);
        }
        if (canonical) length = textLength;
        if (length <= 0) return LyricAnimations.GRADIENT_UNSUNG;
        float cursor = 0f;
        int preceding = 0;
        for (SyllableSegment span : spans) {
            if (span == null) continue;
            int start = canonical ? span.canonicalStartCp : preceding;
            int end = canonical ? span.canonicalEndCp : preceding + textLength(span);
            preceding = end;
            if (span.endMs < span.startMs) continue;
            if (positionMs < span.startMs) break;
            float fraction = progress(positionMs, span.startMs, span.endMs);
            cursor = Math.max(cursor, (start + (end - start) * fraction) / length);
        }
        // The final source span can leave trailing punctuation outside its canonical range.
        if (positionMs >= lastEnd) cursor = 1f;
        return LyricAnimations.gradientPosition(Math.max(0f, Math.min(1f, cursor)));
    }

    private static boolean hasTiming(List<SyllableSegment> spans) {
        if (spans != null) for (SyllableSegment span : spans) {
            if (span != null && span.endMs > span.startMs) return true;
        }
        return false;
    }

    private static int textLength(SyllableSegment span) {
        return span.text == null ? 0 : span.text.codePointCount(0, span.text.length());
    }

    private static float progress(long position, long start, long end) {
        if (position < start) return 0f;
        if (end <= start) return 1f;
        return Math.max(0f, Math.min(1f, (position - start) / (float) (end - start)));
    }
}
