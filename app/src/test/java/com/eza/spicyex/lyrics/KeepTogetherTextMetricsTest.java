package com.eza.spicyex.lyrics;

import android.graphics.Paint;
import android.text.style.ReplacementSpan;
import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class KeepTogetherTextMetricsTest {
    @Test public void fullyGroupedJapaneseTextRetainsFontHeight() throws Exception {
        ReplacementSpan span = span();
        Paint.FontMetricsInt metrics = allocate(Paint.FontMetricsInt.class);

        assertEquals(43, span.getSize(paint(), "ありがとう", 0, 5, metrics));
        assertEquals(-32, metrics.top);
        assertEquals(-28, metrics.ascent);
        assertEquals(7, metrics.descent);
        assertEquals(9, metrics.bottom);
        assertEquals(2, metrics.leading);
        assertTrue("A fully spanned main row needs a positive text height",
                metrics.descent - metrics.ascent > 0);
    }

    @Test public void measuringWidthWithoutMetricsRemainsSupported() throws Exception {
        assertEquals(43, span().getSize(paint(), "ありがとう", 0, 5, null));
    }

    @Test public void subgroupKeepsItsWidthAndOverwritesStaleMetrics() throws Exception {
        Paint.FontMetricsInt metrics = allocate(Paint.FontMetricsInt.class);
        metrics.ascent = -100;
        metrics.descent = 100;

        assertEquals(17, span().getSize(paint(), "ありがとう", 1, 3, metrics));
        assertEquals(-28, metrics.ascent);
        assertEquals(7, metrics.descent);
    }

    private static ReplacementSpan span() throws Exception {
        return (ReplacementSpan) allocate(Class.forName(KeepTogetherText.class.getName() + "$GroupSpan"));
    }

    private static Paint paint() throws Exception {
        return allocate(RecordingPaint.class);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        // Use the real span without calling Android's unavailable JVM constructors.
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }

    public static class RecordingPaint extends Paint {
        @Override public int getFontMetricsInt(FontMetricsInt metrics) {
            metrics.top = -32;
            metrics.ascent = -28;
            metrics.descent = 7;
            metrics.bottom = 9;
            metrics.leading = 2;
            return 35;
        }

        @Override public float measureText(CharSequence text, int start, int end) {
            return (end - start) * 8.5f;
        }
    }
}
