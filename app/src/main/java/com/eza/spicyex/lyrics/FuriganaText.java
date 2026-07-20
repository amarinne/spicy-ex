package com.eza.spicyex.lyrics;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import java.util.ArrayList;
import java.util.List;
import com.eza.spicyex.lyrics.reading.CodePointRanges;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalSpanMapping;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/**
 * Builds the furigana (ruby) spannable for a Japanese lyric line: the kana reading drawn in a
 * smaller font above each kanji run. Reading runs come from
 * {@link SpicyJapaneseChineseProcessor.JapaneseReading#furigana} as start/end offsets into the
 * line text.
 */
public final class FuriganaText {
    static final float RUBY_SIZE_RATIO = 0.46f;
    static final float RUBY_GAP_RATIO = 0.12f;

    private FuriganaText() {
    }

    static float rubyTextSize(float baseTextSize) {
        return Math.max(1f, baseTextSize * RUBY_SIZE_RATIO);
    }

    static int rubyAscentReservationPx(float baseTextSize) {
        return (int) Math.ceil(rubyTextSize(baseTextSize) + baseTextSize * RUBY_GAP_RATIO);
    }

    static int rubyGapReservationPx(float baseTextSize) {
        return (int) Math.ceil(baseTextSize * RUBY_GAP_RATIO);
    }

    /** Whole-line ruby: spans the line's furigana runs over {@code line.text}. */
    public static CharSequence build(AppliedLine line) {
        if (line == null || line.japaneseReading == null || line.japaneseReading.furigana == null
                || line.japaneseReading.furigana.isEmpty()) {
            return line == null ? "" : safe(line.text);
        }
        String text = safe(line.text);
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        int cursor = 0;
        for (SpicyJapaneseChineseProcessor.FuriganaSegment raw : line.japaneseReading.furigana) {
            if (raw == null || isBlank(raw.reading)) continue;
            int start = Math.max(0, Math.min(text.length(), raw.start));
            int end = Math.max(start + 1, Math.min(text.length(), raw.end));
            if (start < cursor || start >= end) continue;
            builder.setSpan(new FuriganaSpan(raw.reading), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            cursor = end;
        }
        return builder;
    }

    /**
     * Slice the line-level furigana reading down to a single word so it can be applied to that
     * word's per-syllable karaoke view. {@code wordStart} is the word's offset into the line text
     * (the same spaced string the reading was computed against); reading segments that fall inside
     * {@code [wordStart, wordStart + wordText.length())} are re-based to the word's local
     * coordinates. Returns the bare word when no run applies.
     */
    public static CharSequence buildWord(AppliedLine line, String wordText, int wordStart) {
        String word = safe(wordText);
        if (line == null || line.japaneseReading == null || line.japaneseReading.furigana == null
                || line.japaneseReading.furigana.isEmpty() || word.isEmpty()) {
            return word;
        }
        int wordEnd = wordStart + word.length();
        SpannableStringBuilder builder = new SpannableStringBuilder(word);
        int cursor = 0;
        boolean any = false;
        for (SpicyJapaneseChineseProcessor.FuriganaSegment raw : line.japaneseReading.furigana) {
            if (raw == null || isBlank(raw.reading)) continue;
            if (!segmentStartsInWord(raw, wordStart, wordEnd)) continue;
            int start = Math.max(0, Math.min(word.length(), raw.start - wordStart));
            int end = Math.max(start + 1, Math.min(word.length(), raw.end - wordStart));
            if (start < cursor || start >= end) continue;
            builder.setSpan(new FuriganaSpan(raw.reading), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            cursor = end;
            any = true;
        }
        return any ? builder : word;
    }

    static boolean segmentStartsInWord(SpicyJapaneseChineseProcessor.FuriganaSegment segment, int wordStart, int wordEnd) {
        return segment != null && segment.start >= wordStart && segment.start < wordEnd && segment.end > wordStart;
    }

    static boolean hasRubyCrossingWordBoundaries(AppliedLine line) {
        return !crossingRubyWordRanges(line).isEmpty();
    }

    static List<int[]> crossingRubyWordRanges(AppliedLine line) {
        ArrayList<int[]> crossings = new ArrayList<>();
        if (line == null || line.words == null || line.words.isEmpty()
                || line.japaneseReading == null || line.japaneseReading.furigana == null
                || line.japaneseReading.furigana.isEmpty()) return crossings;

        int offset = 0;
        String lineText = safe(line.text);
        int[] starts = new int[line.words.size()];
        int[] ends = new int[line.words.size()];
        for (int index = 0; index < line.words.size(); index++) {
            SyllableSegment seg = line.words.get(index);
            if (seg == null || isBlank(seg.text)) {
                starts[index] = ends[index] = offset;
                continue;
            }
            int[] range = wordRange(line, seg, index, offset);
            starts[index] = range[0];
            ends[index] = range[1];
            offset = Math.max(offset, range[1]);
        }

        for (SpicyJapaneseChineseProcessor.FuriganaSegment segment : line.japaneseReading.furigana) {
            if (segment == null || isBlank(segment.reading) || segment.end <= segment.start) continue;
            int first = -1;
            int last = -1;
            for (int i = 0; i < line.words.size(); i++) {
                if (segment.end <= starts[i] || segment.start >= ends[i]) continue;
                if (first < 0) first = i;
                last = i;
            }
            if (first >= 0 && last > first) crossings.add(new int[]{first, last});
        }
        crossings.sort((left, right) -> Integer.compare(left[0], right[0]));
        ArrayList<int[]> merged = new ArrayList<>();
        for (int[] crossing : crossings) {
            if (merged.isEmpty() || crossing[0] > merged.get(merged.size() - 1)[1]) {
                merged.add(crossing.clone());
            } else {
                merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], crossing[1]);
            }
        }
        return merged;
    }

    /** Resolve ruby coordinates from immutable source ownership when a plan is available. The
     * fallback is only for legacy rows without a plan. */
    static int[] wordRange(AppliedLine line, SyllableSegment segment, int fallbackIndex, int fallbackOffset) {
        String text = safe(line == null ? "" : line.text);
        if (line != null && line.readingRenderPlan != null && segment != null) {
            String spanId = segment.spanId == null || segment.spanId.trim().isEmpty()
                    ? String.valueOf(fallbackIndex) : segment.spanId;
            int startCp = Integer.MAX_VALUE;
            int endCp = -1;
            for (String id : spanId.split("\\+")) {
                for (CanonicalSpanMapping mapping : line.readingRenderPlan.sourceUnits) {
                    if (mapping == null || mapping.canonicalRange == null || !id.equals(mapping.spanId)) continue;
                    startCp = Math.min(startCp, mapping.canonicalRange.startCp);
                    endCp = Math.max(endCp, mapping.canonicalRange.endCp);
                }
            }
            if (endCp >= 0) {
                return new int[]{
                        CodePointRanges.codePointOffsetToUtf16Index(text, startCp),
                        CodePointRanges.codePointOffsetToUtf16Index(text, endCp)
                };
            }
        }
        String word = segment == null ? "" : safe(segment.text);
        int found = word.isEmpty() ? fallbackOffset : text.indexOf(word, fallbackOffset);
        int start = found >= 0 ? found : fallbackOffset;
        return new int[]{start, Math.min(text.length(), start + word.length())};
    }

    /** Draws a small kana reading centered above the spanned base text. */
    static final class FuriganaSpan extends ReplacementSpan {
        private final String reading;
        private int spanWidth;

        FuriganaSpan(String reading) {
            this.reading = reading == null ? "" : reading;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            float baseSize = paint.getTextSize();
            float readingSize = rubyTextSize(baseSize);
            float gap = baseSize * RUBY_GAP_RATIO;
            float baseWidth = paint.measureText(text, start, end);
            float oldSize = paint.getTextSize();
            paint.setTextSize(readingSize);
            float readingWidth = paint.measureText(reading);
            paint.setTextSize(oldSize);
            spanWidth = (int) Math.ceil(Math.max(baseWidth, readingWidth));
            if (fm != null) {
                Paint.FontMetricsInt baseFm = paint.getFontMetricsInt();
                int extra = (int) Math.ceil(readingSize + gap);
                fm.ascent = baseFm.ascent - extra;
                fm.top = Math.min(baseFm.top, fm.ascent);
                fm.descent = baseFm.descent;
                fm.bottom = baseFm.bottom;
            }
            return spanWidth;
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
            float baseSize = paint.getTextSize();
            float readingSize = rubyTextSize(baseSize);
            float gap = baseSize * RUBY_GAP_RATIO;
            float baseWidth = paint.measureText(text, start, end);
            int width = spanWidth > 0 ? spanWidth : (int) Math.ceil(baseWidth);
            float baseX = x + (width - baseWidth) / 2f;

            int oldColor = paint.getColor();
            float oldSize = paint.getTextSize();
            Typeface oldTypeface = paint.getTypeface();
            paint.setTextSize(readingSize);
            paint.setTypeface(oldTypeface);
            paint.setColor(Color.rgb(150, 150, 150));
            Paint.FontMetricsInt readingFm = paint.getFontMetricsInt();
            Paint.FontMetricsInt baseFm = new Paint.FontMetricsInt();
            paint.setTextSize(oldSize);
            paint.setTypeface(oldTypeface);
            paint.setColor(oldColor);
            paint.getFontMetricsInt(baseFm);

            paint.setTextSize(readingSize);
            paint.setTypeface(oldTypeface);
            paint.setColor(Color.rgb(150, 150, 150));
            float readingWidth = paint.measureText(reading);
            float readingX = x + (width - readingWidth) / 2f;
            float readingBaseline = y + baseFm.ascent - gap - readingFm.descent;
            canvas.drawText(reading, readingX, readingBaseline, paint);

            paint.setTextSize(oldSize);
            paint.setTypeface(oldTypeface);
            paint.setColor(oldColor);
            canvas.drawText(text, start, end, baseX, y, paint);
        }
    }
}
