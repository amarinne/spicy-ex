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
    private boolean glowLayerEnabled = true;
    // Apple active-line drop shadow intensity (0 = off, the shared-path default).
    private float lineShadowAlpha;
    // Blur filters cached by quantized sigma; sigma animates every frame and BlurMaskFilter is
    // immutable, so allocating one per word per frame would churn. Shared with the selfGlow path
    // in SpicyAnimatedTextView; only touched from the UI thread.
    private static final SparseArray<BlurMaskFilter> blurCache = new SparseArray<>();

    // Adaptive sectioning state, armed by LyricsRowViewFactory for wrapping word rows when the
    // setting is on. Default (disarmed) keeps plain greedy Flexbox wrapping.
    private boolean adaptiveSectioning;
    private boolean[] adaptiveForbiddenBreaks = new boolean[0];
    private int[][] adaptiveKeepTogetherGroups = new int[0][];
    private long adaptiveSignature = Long.MIN_VALUE; // sentinel: nothing planned yet
    private boolean adaptiveWrapApplied;

    public GlowFlexbox(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    /**
     * Arms or disarms adaptive wrapBefore planning for this row. {@code forbiddenBreakAfter[i]}
     * forbids a line break between direct child i and i+1; {@code keepTogetherGroups} holds
     * inclusive {first, last} child-index pairs whose members may split only when the whole group
     * is wider than the row (emergency overflow rule). Safe to call before the first measure;
     * grouping changes force a re-plan on the next measure pass.
     */
    public void setAdaptiveSectioning(boolean enabled, boolean[] forbiddenBreakAfter,
                                      int[][] keepTogetherGroups) {
        adaptiveSectioning = enabled;
        adaptiveForbiddenBreaks = forbiddenBreakAfter == null
                ? new boolean[0] : forbiddenBreakAfter.clone();
        adaptiveKeepTogetherGroups = keepTogetherGroups == null
                ? new int[0][] : cloneGroups(keepTogetherGroups);
        adaptiveSignature = Long.MIN_VALUE;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int childCount = getChildCount();
        int available = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        if (!adaptiveSectioning || childCount == 0 || available <= 0) {
            if (adaptiveWrapApplied) applyWrapBefore(null);
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        // Children are WRAP_CONTENT, so their measured widths are natural regardless of the
        // currently applied wrapBefore flags; this measure doubles as the planning width probe.
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        long signature = adaptiveSignature(available, childCount);
        if (signature == adaptiveSignature) return; // width, count and widths unchanged
        adaptiveSignature = signature;
        boolean[] plan = AdaptiveBreakPlanner.plan(childOuterWidths(childCount), available,
                adaptiveForbiddenBreaks, adaptiveKeepTogetherGroups);
        if (applyWrapBefore(plan)) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec); // bounded second measure
        }
    }

    private long adaptiveSignature(int available, int childCount) {
        long h = 1125899906842597L;
        h = 31L * h + available;
        h = 31L * h + childCount;
        for (int i = 0; i < childCount; i++) h = 31L * h + getChildAt(i).getMeasuredWidth();
        return h;
    }

    private int[] childOuterWidths(int childCount) {
        int[] widths = new int[childCount];
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            int margins = 0;
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                margins = mlp.leftMargin + mlp.rightMargin;
            }
            widths[i] = child.getMeasuredWidth() + margins;
        }
        return widths;
    }

    /** Applies the plan to direct children and returns true when any flag changed. */
    private boolean applyWrapBefore(boolean[] plan) {
        boolean changed = false;
        boolean anySet = false;
        for (int i = 0; i < getChildCount(); i++) {
            boolean want = plan != null && i < plan.length && plan[i];
            ViewGroup.LayoutParams lp = getChildAt(i).getLayoutParams();
            if (lp instanceof FlexboxLayout.LayoutParams) {
                FlexboxLayout.LayoutParams flp = (FlexboxLayout.LayoutParams) lp;
                if (flp.isWrapBefore() != want) {
                    flp.setWrapBefore(want);
                    changed = true;
                }
            }
            if (want) anySet = true;
        }
        adaptiveWrapApplied = anySet;
        return changed;
    }

    private static int[][] cloneGroups(int[][] groups) {
        int[][] out = new int[groups.length][];
        for (int i = 0; i < groups.length; i++) out[i] = groups[i] == null ? null : groups[i].clone();
        return out;
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

    public void setGlowLayerEnabled(boolean enabled) {
        glowLayerEnabled = enabled;
        invalidate();
    }

    /** Apple line-shadow intensity for the word container (0 = off). */
    public void setLineShadowIntensity(float intensity) {
        float clamped = Math.max(0f, Math.min(1f, intensity));
        if (clamped == lineShadowAlpha) return;
        lineShadowAlpha = clamped;
        invalidate();
    }

    static boolean shouldDrawGlow(boolean enabled, float glow) {
        return enabled && glow > 0.02f;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        drawGlowLayer(canvas, this);
        if (lineShadowAlpha > 0.02f) drawShadowLayer(canvas, this);
        super.dispatchDraw(canvas);
    }

    private void drawGlowLayer(Canvas canvas, ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int save = canvas.save();
            canvas.translate(child.getLeft(), child.getTop());
            canvas.concat(child.getMatrix());
            if (child instanceof SpicyAnimatedTextView) {
                drawWordGlow(canvas, (SpicyAnimatedTextView) child, 0f, 0f);
            } else if (child instanceof ViewGroup) {
                drawGlowLayer(canvas, (ViewGroup) child);
            }
            canvas.restoreToCount(save);
        }
    }

    /** Apple shadow pass: blurred dark glyph copies beneath the word container's children. */
    private void drawShadowLayer(Canvas canvas, ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int save = canvas.save();
            canvas.translate(child.getLeft(), child.getTop());
            canvas.concat(child.getMatrix());
            if (child instanceof SpicyAnimatedTextView) {
                drawWordShadow(canvas, (SpicyAnimatedTextView) child);
            } else if (child instanceof ViewGroup) {
                drawShadowLayer(canvas, (ViewGroup) child);
            }
            canvas.restoreToCount(save);
        }
    }

    private void drawWordShadow(Canvas canvas, SpicyAnimatedTextView tv) {
        Layout layout = tv.getLayout();
        if (layout == null) return;
        TextPaint paint = tv.getPaint();
        int savedColor = paint.getColor();
        Shader savedShader = paint.getShader();
        MaskFilter savedMask = paint.getMaskFilter();
        int alpha = Math.round(55f * lineShadowAlpha);
        paint.setShader(null);
        paint.setColor(Color.argb(alpha, 0, 0, 0));
        paint.setMaskFilter(blurFilter(16f * paint.getTextSize() / 48f));
        int save = canvas.save();
        canvas.translate(tv.getTotalPaddingLeft(), tv.getTotalPaddingTop() + paint.getTextSize() * 0.06f);
        try {
            layout.draw(canvas);
        } catch (Throwable ignored) {
        }
        canvas.restoreToCount(save);
        paint.setMaskFilter(savedMask);
        paint.setColor(savedColor);
        paint.setShader(savedShader);
    }

    private void drawWordGlow(Canvas canvas, SpicyAnimatedTextView tv, float x, float y) {        float g = Math.max(0f, Math.min(1f, tv.getGlow()));
        if (!shouldDrawGlow(glowLayerEnabled, g)) return;
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
            FuriganaText.FuriganaSpan.onBeginDraw();
            layout.draw(canvas);
        } catch (Throwable ignored) {
        }
        canvas.restoreToCount(save);
        paint.setMaskFilter(savedMask);
        paint.setColor(savedColor);
        paint.setShader(savedShader);
    }
}
