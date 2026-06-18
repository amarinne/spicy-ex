package com.eza.spicyex.lyrics;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/**
 * Draws a single centered text glyph, sized to its bounds. Used as the transliteration chip's
 * image so the glyph can change to signify "romanize whatever script is on screen" (あ for
 * Japanese, 拼/粤 for Chinese, 가 for Korean, Я for Cyrillic, Ω for Greek, neutral A otherwise)
 * instead of a fixed kana drawable.
 *
 * <p>The glyph is painted opaque white; the host {@code ImageView}'s color filter (set by
 * {@code styleIconChip}) tints it via SRC_ATOP, matching the other chips' enabled/disabled colors.
 */
public final class GlyphIconDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String glyph;
    private boolean glowing;

    public GlyphIconDrawable(String initialGlyph, Typeface typeface) {
        glyph = initialGlyph == null ? "" : initialGlyph;
        paint.setColor(0xFFFFFFFF);
        paint.setTypeface(typeface == null ? Typeface.DEFAULT_BOLD : typeface);
        paint.setTextAlign(Paint.Align.CENTER);
    }

    /** Update the glyph; returns true if it changed (so callers can skip redundant work). */
    public boolean setGlyph(String value) {
        String next = LyricUtils.safe(value);
        if (next.equals(glyph)) return false;
        glyph = next;
        invalidateSelf();
        return true;
    }

    public String getGlyph() {
        return glyph;
    }

    /** Toggle a soft glow behind the glyph, used to signal the active (transliteration-on) state. */
    public void setGlowing(boolean value) {
        if (value == glowing) return;
        glowing = value;
        invalidateSelf();
    }

    public boolean isGlowing() {
        return glowing;
    }

    @Override
    public void draw(Canvas canvas) {
        if (glyph.isEmpty()) return;
        Rect b = getBounds();
        int size = Math.min(b.width(), b.height());
        if (size <= 0) return;
        paint.setTextSize(size * 1.02f);
        if (glowing) {
            paint.setShadowLayer(size * 0.16f, 0f, 0f, 0xFFFFFFFF);
        } else {
            paint.clearShadowLayer();
        }
        Paint.FontMetrics fm = paint.getFontMetrics();
        float cx = b.exactCenterX();
        float cy = b.exactCenterY() - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(glyph, cx, cy, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
