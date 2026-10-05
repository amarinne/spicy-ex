package com.eza.spicyex.lyrics;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import java.util.List;

/** Uses finalized lexical ranges for line-level wrapping without changing the lyric text. */
final class KeepTogetherText {
    private KeepTogetherText() { }

    static CharSequence build(String text, List<DisplayLayoutGroup> groups) {
        SpannableString result = new SpannableString(text);
        for (DisplayLayoutGroup group : groups) {
            if (group.keepTogether && group.end > group.start && group.end <= text.length())
                result.setSpan(new GroupSpan(), group.start, group.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return result;
    }

    private static final class GroupSpan extends ReplacementSpan {
        @Override public int getSize(Paint paint, CharSequence text, int start, int end,
                                     Paint.FontMetricsInt metrics) {
            // Fully grouped text has no plain run to supply its line height.
            if (metrics != null) paint.getFontMetricsInt(metrics);
            return (int) Math.ceil(paint.measureText(text, start, end));
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                                   float x, int top, int baseline, int bottom, Paint paint) {
            canvas.drawText(text, start, end, x, baseline, paint);
        }
    }
}
