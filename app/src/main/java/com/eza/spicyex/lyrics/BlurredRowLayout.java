package com.eza.spicyex.lyrics;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.widget.LinearLayout;

/**
 * A lyric row that can draw its content blurred, with the blur baked into a cached layer.
 *
 * <p>Putting the blur {@link RenderEffect} on the row's own RenderNode made HWUI re-run it while
 * compositing, i.e. on every frame the window draws. Here the children are recorded into
 * {@code content} and the blur is applied inside a compositing layer, which re-renders only when
 * the blur radius or something inside it changes.
 *
 * <p>The layer is rendered at reduced resolution (see {@link #downscaleFor}): a strong blur removes
 * everything finer than a few pixels anyway, so blurring a 1/2-1/4 size copy and scaling it back
 * up looks the same at 1/4-1/16 of the GPU memory and fill cost. Full-size layers for every row
 * added up to ~400 MB of render targets and made each blur change a full-row render pass. That
 * saving is what lets a changing blur simply re-render at its exact radius: approximating the
 * in-between radii by crossfading two cached blur layers showed as two blurs at once.
 *
 * <p>Composited with PLUS, as the blurred rows always have been.
 */
public class BlurredRowLayout extends LinearLayout {
    private static final Paint PLUS_PAINT = plusPaint();

    private float blurRadiusPx;
    /** Side inset baked into this row's padding when it was built (LyricsRowViewFactory). */
    public int horizontalOffsetPx;

    private RenderNode content;
    private RenderNode effect;
    private RenderNode cache;
    private float recordedRadius = -1f;
    private int recordedScale = 1;
    /** Children, their layout or visibility changed since {@link #content} was recorded. */
    private boolean contentDirty = true;
    private int recordedOutset = -1;

    public BlurredRowLayout(Context context) {
        super(context);
    }

    private static Paint plusPaint() {
        Paint paint = new Paint();
        if (Build.VERSION.SDK_INT >= 29) paint.setBlendMode(BlendMode.PLUS);
        return paint;
    }

    /**
     * Keeps the blur (sigma ~0.58r) comfortably wider than the upscaled pixels. Weak blurs are
     * downscaled too: every blur change passes through them (a row blurring in starts at 0), and
     * at full resolution all the rows changing together were the stutter when blur came and went.
     */
    static int downscaleFor(float radiusPx) {
        // Even a light blur softens away single-pixel detail, so it is blurred at half size: a
        // full-resolution layer per lightly blurred row was most of the blur's memory.
        if (radiusPx < 6f) return 2;
        if (radiusPx < 12f) return 3;
        return 4;
    }

    /** Below this the blur is imperceptible: the row draws sharp, with no layer at all. */
    static final float SHARP_BELOW_PX = 1.2f;

    /**
     * The radius actually rendered: in steps of half a pixel of the downscaled layer, finer
     * than the eye can tell apart at that blur. A radius easing in or out used to re-run the blur
     * on every frame for every row changing together - the stutter when the focus moved.
     */
    static float renderedRadius(float radiusPx) {
        int scale = downscaleFor(radiusPx);
        return Math.round(radiusPx / scale * 2f) / 2f * scale;
    }

    /** A heavily blurred row whose content keeps changing re-blurs at most this often. */
    private static final long HEAVY_REBLUR_MS = 33L;
    private static final float HEAVY_BLUR_PX = 6f;
    private long lastBlurRenderMs;

    // Every dimmed row carries alpha < 1. With overlapping rendering, HWUI draws each such row into
    // its own offscreen buffer and composites it - one render-target switch per row per frame, the
    // dominant GPU cost of the lyric column on tiled mobile GPUs. The row's children (text lines,
    // readings, translation) don't overlap one another, so applying the alpha to each draw directly
    // renders the same pixels.
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /** Blur radius in px (as passed to {@link RenderEffect#createBlurEffect}); 0 draws sharp. */
    public void setContentBlur(float radiusPx) {
        float radius = Math.max(0f, radiusPx);
        if (Math.abs(radius - blurRadiusPx) < 0.01f) return;
        boolean wasBlurred = blurRadiusPx >= SHARP_BELOW_PX;
        blurRadiusPx = radius;
        if (radius < SHARP_BELOW_PX) {
            // Sharp now (the line in focus): its blur layer is let go instead of held in GPU
            // memory for as long as the row exists.
            if (wasBlurred) {
                releaseLayers();
                invalidate();
            }
            return;
        }
        // Only a change the rendered blur would show redraws the row.
        if (renderedRadius(radius) != recordedRadius || !wasBlurred) invalidate();
    }

    private void releaseLayers() {
        if (content != null && Build.VERSION.SDK_INT >= 31) {
            content.discardDisplayList();
            effect.discardDisplayList();
            cache.discardDisplayList();
        }
        contentDirty = true;
        recordedOutset = -1;
        recordedRadius = -1f;
    }

    public float contentBlur() {
        return blurRadiusPx;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (blurRadiusPx < SHARP_BELOW_PX || Build.VERSION.SDK_INT < 31 || width <= 0 || height <= 0
                || !(canvas instanceof RecordingCanvas) || !canvas.isHardwareAccelerated()) {
            super.dispatchDraw(canvas);
            return;
        }
        if (content == null) {
            content = new RenderNode("lyricRowContent");
            effect = new RenderNode("lyricRowBlur");
            cache = new RenderNode("lyricRowBlurCache");
            cache.setUseCompositingLayer(true, PLUS_PAINT);
            cache.setPivotX(0f);
            cache.setPivotY(0f);
        }
        float radius = renderedRadius(blurRadiusPx);
        // A heavily blurred row whose children changed again within a frame or two keeps its
        // last blur a moment longer and catches up just after: under that much blur the change
        // cannot be seen at full rate, and re-blurring every frame was the cost.
        long now = android.os.SystemClock.uptimeMillis();
        if (contentDirty && radius == recordedRadius && radius >= HEAVY_BLUR_PX && cache.hasDisplayList()
                && now - lastBlurRenderMs < HEAVY_REBLUR_MS) {
            postInvalidateDelayed(HEAVY_REBLUR_MS - (now - lastBlurRenderMs));
            ((RecordingCanvas) canvas).drawRenderNode(cache);
            return;
        }
        // Gaussian reach: sigma ~ 0.577r + 0.5, visible to ~3 sigma. Only grows, so the content
        // recording is reused while a blur eases down.
        int outset = Math.max(recordedOutset, (int) Math.ceil(radius * 1.8f) + 2);
        boolean contentChanged = false;
        if (contentDirty || outset != recordedOutset || !content.hasDisplayList()) {
            content.setPosition(0, 0, width + 2 * outset, height + 2 * outset);
            RecordingCanvas c = content.beginRecording();
            try {
                c.translate(outset, outset);
                super.dispatchDraw(c);
            } finally {
                content.endRecording();
            }
            contentDirty = false;
            recordedOutset = outset;
            contentChanged = true;
        }
        int scale = downscaleFor(radius);
        if (contentChanged || radius != recordedRadius || scale != recordedScale || !cache.hasDisplayList()) {
            // DECAL: outside the content is transparent. CLAMP stretched the edge pixels outward,
            // which smeared glyph outlines into streaks at the row bounds.
            effect.setRenderEffect(RenderEffect.createBlurEffect(
                    radius / scale, radius / scale, Shader.TileMode.DECAL));
            int smallW = (int) Math.ceil((width + 2f * outset) / scale);
            int smallH = (int) Math.ceil((height + 2f * outset) / scale);
            effect.setPosition(0, 0, smallW, smallH);
            RecordingCanvas e = effect.beginRecording();
            try {
                e.scale(1f / scale, 1f / scale);
                e.drawRenderNode(content);
            } finally {
                effect.endRecording();
            }
            // The small layer, scaled back up to cover the row (plus outset) when composited.
            cache.setPosition(-outset, -outset, -outset + smallW, -outset + smallH);
            cache.setScaleX(scale);
            cache.setScaleY(scale);
            RecordingCanvas c = cache.beginRecording();
            try {
                c.drawRenderNode(effect);
            } finally {
                cache.endRecording();
            }
            recordedRadius = radius;
            recordedScale = scale;
            lastBlurRenderMs = now;
        }
        ((RecordingCanvas) canvas).drawRenderNode(cache);
    }

    // Anything that changes what the children draw, where, or whether: the content recording is
    // stale. (A child's own redraw lands in its own RenderNode, which content references, but the
    // child set, their positions and visibility live in this row's recording.)
    @Override
    public void onDescendantInvalidated(View child, View target) {
        super.onDescendantInvalidated(child, target);
        contentDirty = true;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        contentDirty = true;
        recordedOutset = -1;
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        contentDirty = true;
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        contentDirty = true;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // The nodes exist only on 31+ (see above); the check also tells lint so.
        releaseLayers();
    }
}
