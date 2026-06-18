package com.eza.spicyex.lyrics;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/**
 * Builds the furigana (ruby) spannable for a Japanese lyric line: the kana reading drawn in a
 * smaller font above each kanji run. Reading runs come from
 * {@link SpicyJapaneseChineseProcessor.JapaneseReading#furigana} as start/end offsets into the
 * line text.
 */
public final class FuriganaText {

    private FuriganaText() {
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
            if (raw.start < wordStart || raw.end > wordEnd) continue;
            int start = Math.max(0, Math.min(word.length(), raw.start - wordStart));
            int end = Math.max(start + 1, Math.min(word.length(), raw.end - wordStart));
            if (start < cursor || start >= end) continue;
            builder.setSpan(new FuriganaSpan(raw.reading), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            cursor = end;
            any = true;
        }
        return any ? builder : word;
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
            float readingSize = Math.max(1f, baseSize * 0.46f);
            float gap = baseSize * 0.12f;
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
            float readingSize = Math.max(1f, baseSize * 0.46f);
            float gap = baseSize * 0.12f;
            float baseWidth = paint.measureText(text, start, end);
            int width = spanWidth > 0 ? spanWidth : (int) Math.ceil(baseWidth);
            float baseX = x + (width - baseWidth) / 2f;

            int oldColor = paint.getColor();
            float oldSize = paint.getTextSize();
            Typeface oldTypeface = paint.getTypeface();
            paint.setTextSize(readingSize);
            paint.setTypeface(Typeface.DEFAULT);
            paint.setColor(Color.rgb(150, 150, 150));
            Paint.FontMetricsInt readingFm = paint.getFontMetricsInt();
            Paint.FontMetricsInt baseFm = new Paint.FontMetricsInt();
            paint.setTextSize(oldSize);
            paint.setTypeface(oldTypeface);
            paint.setColor(oldColor);
            paint.getFontMetricsInt(baseFm);

            paint.setTextSize(readingSize);
            paint.setTypeface(Typeface.DEFAULT);
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
