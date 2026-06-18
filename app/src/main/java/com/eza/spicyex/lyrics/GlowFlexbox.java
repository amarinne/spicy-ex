package com.eza.spicyex.lyrics;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Shader;
import android.text.Layout;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.flexbox.FlexboxLayout;

/**
 * Word-row container that draws the lyric blur-glow ONCE on its own canvas, across every animated
 * word, instead of each word view drawing its own {@code setShadowLayer}. A per-word shadow is
 * clipped to that word's box, so adjacent words showed faint rectangular seams; drawing every word's
 * glyph-shadow onto this shared canvas lets the halos blend continuously (the desktop gets this free
 * from rendering a whole line as one element). Only words with glow &gt; 0 contribute, so it tracks
 * the active karaoke position. The real (gradient) word text is drawn on top by super.dispatchDraw.
 */
public class GlowFlexbox extends FlexboxLayout {
    private final float density;

    public GlowFlexbox(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        drawGlowLayer(canvas, this, 0f, 0f);
        super.dispatchDraw(canvas);
    }

    private void drawGlowLayer(Canvas canvas, ViewGroup parent, float ox, float oy) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            float cx = ox + child.getLeft();
            float cy = oy + child.getTop();
            if (child instanceof SpicyAnimatedTextView) {
                drawWordGlow(canvas, (SpicyAnimatedTextView) child, cx, cy);
            } else if (child instanceof ViewGroup) {
                drawGlowLayer(canvas, (ViewGroup) child, cx, cy);
            }
        }
    }

    private void drawWordGlow(Canvas canvas, SpicyAnimatedTextView tv, float x, float y) {
        float g = Math.max(0f, Math.min(1f, tv.getGlow()));
        if (g <= 0.02f) return;
        Layout layout = tv.getLayout();
        if (layout == null) return;
        TextPaint paint = tv.getPaint();
        int savedColor = paint.getColor();
        Shader savedShader = paint.getShader();
        int alpha = Math.round(255f * 0.8f * g);
        int glowColor = Color.argb(alpha, 255, 255, 255);
        // Draw the glyphs at low alpha with a white blur shadow → a soft continuous halo. The real
        // word (full gradient) is painted over this by the normal child draw, so this is glow-only.
        // Bumped up (was 0.5α / 3+7g) now the heavier font can carry a more visible halo.
        paint.setShader(null);
        paint.setColor(glowColor);
        paint.setShadowLayer((5f + 12f * g) * density, 0f, 0f, glowColor);
        int save = canvas.save();
        canvas.translate(x + tv.getTotalPaddingLeft(), y + tv.getTotalPaddingTop());
        try {
            layout.draw(canvas);
        } catch (Throwable ignored) {
        }
        canvas.restoreToCount(save);
        paint.clearShadowLayer();
        paint.setColor(savedColor);
        paint.setShader(savedShader);
    }
}
