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
        return gradientPosition(line, positionMs, line != null && line.smoothSentenceGradient);
    }

    private static float gradientPosition(AppliedLine line, long positionMs, boolean smooth) {
        if (line == null) return LyricAnimations.GRADIENT_UNSUNG;
        if (line.gradientSource != null) {
            AppliedLine source = line.gradientSource;
            int length = source.text.codePointCount(0, source.text.length());
            return SentenceFill.lineGradient(gradientPosition(source, positionMs, smooth), length,
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
        int previousTimed = -1;
        int firstTimed = smooth ? nextTimed(spans, 0) : -1;
        for (int i = 0; i < spans.size(); i++) {
            SyllableSegment span = spans.get(i);
            if (span == null) continue;
            int start = canonical ? span.canonicalStartCp : preceding;
            int end = canonical ? span.canonicalEndCp : preceding + textLength(span);
            preceding = end;
            if (span.endMs < span.startMs) continue;
            if (smooth && span.endMs == span.startMs) continue;
            if (positionMs < span.startMs) break;
            float fraction = progress(positionMs, span.startMs, span.endMs);
            if (smooth) {
                int next = nextTimed(spans, i + 1);
                int from = canonical && previousTimed < 0 ? 0 : start;
                // Sweep separators and trailing punctuation with the preceding authored word.
                int to = canonical ? next < 0 ? length
                        : Math.max(end, spans.get(next).canonicalStartCp) : end;
                float incoming = 0f, outgoing = 0f;
                long duration = span.endMs - span.startMs;
                if (previousTimed >= 0) {
                    SyllableSegment previous = spans.get(previousTimed);
                    if (previous.endMs == span.startMs
                            && (!canonical || previous.canonicalEndCp <= start)) {
                        int previousStart = canonical ? previousTimed == firstTimed
                                ? 0 : previous.canonicalStartCp : from - textLength(previous);
                        incoming = tangent(to - from, duration, from - previousStart,
                                previous.endMs - previous.startMs);
                    }
                }
                if (next >= 0) {
                    SyllableSegment following = spans.get(next);
                    if (span.endMs == following.startMs
                            && (!canonical || to == following.canonicalStartCp)) {
                        int after = nextTimed(spans, next + 1);
                        int followingEnd = canonical ? after < 0 ? length
                                : Math.max(following.canonicalEndCp, spans.get(after).canonicalStartCp)
                                : to + textLength(following);
                        outgoing = tangent(to - from, duration, followingEnd - to,
                                following.endMs - following.startMs);
                    }
                }
                // Monotone cubic interpolation matches speed at connected word boundaries.
                // Zero boundary speed eases into real gaps; playback time remains the only clock.
                fraction = fraction * (incoming + fraction * (3f - 2f * incoming - outgoing
                        + fraction * (incoming + outgoing - 2f)));
                start = from; end = to;
                previousTimed = i;
            }
            cursor = Math.max(cursor, (start + (end - start) * fraction) / length);
        }
        // The final source span can leave trailing punctuation outside its canonical range.
        if (positionMs >= lastEnd) cursor = 1f;
        return LyricAnimations.gradientPosition(Math.max(0f, Math.min(1f, cursor)));
    }

    private static int nextTimed(List<SyllableSegment> spans, int from) {
        for (int i = from; i < spans.size(); i++) {
            SyllableSegment span = spans.get(i);
            if (span != null && span.endMs > span.startMs) return i;
        }
        return -1;
    }

    /** Normalized harmonic-mean speed stays between zero and two, avoiding cubic overshoot. */
    private static float tangent(int extent, long duration, int adjacentExtent, long adjacentDuration) {
        if (extent <= 0 || adjacentExtent <= 0 || duration <= 0 || adjacentDuration <= 0) return 0f;
        float speed = extent / (float) duration;
        float adjacent = adjacentExtent / (float) adjacentDuration;
        return 2f * adjacent / (speed + adjacent);
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
