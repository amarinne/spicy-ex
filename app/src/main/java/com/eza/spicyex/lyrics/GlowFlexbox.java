package com.eza.spicyex.lyrics;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.MaskFilter;
import android.graphics.Shader;
import android.text.Layout;
import android.text.TextPaint;
import android.util.SparseArray;
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
    // Blur filters cached by quantized sigma; sigma animates every frame and BlurMaskFilter is
    // immutable, so allocating one per word per frame would churn. Shared with the selfGlow path
    // in SpicyAnimatedTextView; only touched from the UI thread.
    private static final SparseArray<BlurMaskFilter> blurCache = new SparseArray<>();

    public GlowFlexbox(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    static BlurMaskFilter blurFilter(float sigma) {
        int key = Math.max(1, Math.round(sigma * 4f)); // quantize to 0.25px steps
        BlurMaskFilter filter = blurCache.get(key);
        if (filter == null) {
            filter = new BlurMaskFilter(key / 4f, BlurMaskFilter.Blur.NORMAL);
            blurCache.put(key, filter);
        }
        return filter;
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
        MaskFilter savedMask = paint.getMaskFilter();
        int alpha = Math.round(255f * 0.35f * g);
        int glowColor = Color.argb(alpha, 255, 255, 255);
        // CSS-equivalent of desktop's `text-shadow: 0 0 (4+2g)px rgba(255,255,255,.35g)`: a blurred
        // copy of the glyphs only — no sharp underlay. CSS blur radius r means Gaussian sigma r/2,
        // and desktop's r is 4-6px against a ~48px reference font, so sigma scales with text size.
        float sigma = (2f + g) * paint.getTextSize() / 48f;
        paint.setShader(null);
        paint.setColor(glowColor);
        paint.setMaskFilter(blurFilter(sigma));
        int save = canvas.save();
        canvas.translate(x + tv.getTotalPaddingLeft(), y + tv.getTotalPaddingTop());
        try {
            layout.draw(canvas);
        } catch (Throwable ignored) {
        }
        canvas.restoreToCount(save);
        paint.setMaskFilter(savedMask);
        paint.setColor(savedColor);
        paint.setShader(savedShader);
    }
}
