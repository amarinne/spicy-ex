package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.reading.SyllableCanonicalizer;

import java.util.ArrayList;
import java.util.List;

/** Rejects unusable QQ/NetEase timing instead of changing sung word timestamps. */
public final class ProviderTimingPolicy {
    private ProviderTimingPolicy() {}

    public static boolean appliesTo(LyricsDocument doc) {
        if (doc == null) return false;
        String source = LyricUtils.safe(doc.fetchSource);
        return "netease".equals(source) || "qq_music".equals(source);
    }

    /** Also repairs the one-millisecond punctuation tokens in older normalized candidates. */
    public static boolean normalizeAndValidate(LyricsDocument doc) {
        if (doc == null) return false;
        List<LyricsLine> inferredEnds = new ArrayList<>();
        for (LyricsLine line : doc.lines) {
            if (line.startMs < 0 || line.endMs < 0
                    || (line.endMs > 0 && line.endMs <= line.startMs)) return false;
            if (!line.syllables.isEmpty() && line.endMs <= line.startMs) return false;
            for (SyllableSegment word : line.syllables) {
                if (word.startMs < 0 || word.endMs < word.startMs) return false;
            }
            if (line.syllables.isEmpty() && line.endMs == 0) inferredEnds.add(line);
        }
        LyricTimeline.fillMissingEndTimes(doc);
        // LRC has no authored end. Limit only its inferred display lifetime to the track.
        if (doc.durationMs > 0) {
            for (LyricsLine line : inferredEnds) line.endMs = Math.min(line.endMs, doc.durationMs);
        }
        for (LyricsLine line : doc.lines) attachUntimedPunctuation(line);
        for (int i = 0; i < doc.lines.size(); i++) {
            LyricsLine line = doc.lines.get(i);
            if (line.interlude) continue;
            if (doc.durationMs > 0 && (line.startMs >= doc.durationMs
                    || line.endMs > doc.durationMs)) return false;
            long nextStartMs = 0;
            for (int j = i + 1; j < doc.lines.size(); j++) {
                if (!doc.lines.get(j).interlude) {
                    nextStartMs = doc.lines.get(j).startMs;
                    break;
                }
            }
            long activeEndMs = LyricTimeline.resolveAppliedEndMs(line.endMs, nextStartMs);
            for (SyllableSegment word : line.syllables) {
                if (word.startMs < line.startMs || word.endMs <= word.startMs
                        || word.endMs > activeEndMs
                        || (doc.durationMs > 0 && word.endMs > doc.durationMs)) return false;
            }
        }
        return true;
    }

    private static void attachUntimedPunctuation(LyricsLine line) {
        List<SyllableSegment> words = line.syllables;
        boolean changed = false;
        for (int i = 0; i < words.size(); i++) {
            SyllableSegment mark = words.get(i);
            if (mark.endMs - mark.startMs > 1 || !punctuationOnly(mark.text)) continue;
            boolean opening = Character.getType(mark.text.codePointAt(0)) == Character.START_PUNCTUATION
                    || Character.getType(mark.text.codePointAt(0)) == Character.INITIAL_QUOTE_PUNCTUATION;
            int owner = opening || i == 0 ? i + 1 : i - 1;
            if (owner >= words.size()) owner = i - 1;
            if (owner < 0) continue;
            SyllableSegment timed = words.get(owner);
            String authoredMark = sourceText(mark);
            String authoredOwner = sourceText(timed);
            if (owner > i) {
                timed.text = mark.text + timed.text;
                timed.sourceText = authoredMark + authoredOwner;
            } else {
                timed.text += mark.text;
                timed.sourceText = authoredOwner + authoredMark;
            }
            words.remove(i--);
            changed = true;
        }
        if (changed && !words.isEmpty()) {
            line.text = SyllableCanonicalizer.displayText(SyllableCanonicalizer.canonicalize(
                    "provider-" + line.startMs, line.text, words), words);
        }
    }

    private static String sourceText(SyllableSegment word) {
        return word.sourceText == null || word.sourceText.isEmpty() ? word.text : word.sourceText;
    }

    private static boolean punctuationOnly(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.codePoints().allMatch(cp -> {
            int type = Character.getType(cp);
            return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                    || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
                    || type == Character.INITIAL_QUOTE_PUNCTUATION
                    || type == Character.FINAL_QUOTE_PUNCTUATION || type == Character.OTHER_PUNCTUATION;
        });
    }
}
