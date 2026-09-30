package com.eza.spicyex.sharecard;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Where a design puts the Spotify Code: its right edge, vertical centre and width. */
final class CodeSlot {
    final float right, centreY, width;
    final boolean paper;

    CodeSlot(float right, float centreY, float width, boolean paper) {
        this.right = right;
        this.centreY = centreY;
        this.width = width;
        this.paper = paper;
    }

    RectF frame(Bitmap code) {
        float height = width * code.getHeight() / Math.max(1f, code.getWidth());
        return new RectF(right - width, centreY - height / 2f, right, centreY + height / 2f);
    }
}

/**
 * Code lines for rendering: the monochrome ink bitmap (white on transparent, or black if the
 * card multiplies it), the logo's box, and each bar's column and extent.
 */
final class CodeArt {
    final Bitmap ink;
    /** In code pixels; null when the image could not be read as logo + bars. */
    final android.graphics.Rect logo;
    final float[] barLeft, barRight, barTop, barBottom;
    final float midY, tallest;
    final int color;

    CodeArt(Bitmap ink, android.graphics.Rect logo, float[] barLeft, float[] barRight,
            float[] barTop, float[] barBottom, int color) {
        this.ink = ink;
        this.logo = logo;
        this.barLeft = barLeft;
        this.barRight = barRight;
        this.barTop = barTop;
        this.barBottom = barBottom;
        this.color = color;
        this.midY = logo == null ? ink.getHeight() / 2f : logo.exactCenterY();
        float max = 0f;
        for (int i = 0; i < barTop.length; i++) max = Math.max(max, barBottom[i] - barTop[i]);
        this.tallest = max;
    }
}

/**
 * The code building itself on the preview card, like a track starting to play: the Spotify
 * logo fades up, then a play-head sweeps left to right and every bar it passes kicks up like
 * a level meter and springs back down. While the code is still downloading the sweep keeps
 * going round over low resting bars; the first sweep after it arrives lands each bar on its
 * real height, and the scannable code is left exactly as shared.
 */
final class CodeView extends View {
    private static final long LOGO_MS = 420L;
    /** The first sweep starts as the logo settles; later ones follow at this period. */
    private static final long FIRST_SWEEP = 300L;
    private static final long SWEEP_PERIOD = 1350L;
    /** Time for the play-head to cross from the first bar to the last. */
    private static final long SWEEP_SPAN = 640L;
    /** A bar's kick up to its peak, then a damped spring down to where it rests. */
    private static final long RISE_MS = 110L;
    private static final float SPRING_DECAY_MS = 140f;
    private static final float SPRING_PERIOD_MS = 75f;
    private static final long RING_OUT_MS = 900L;
    // The stand-in's geometry, in the code's own 400x100 units: the logo, then 23 bars.
    private static final int STAND_IN_BARS = 23;
    private final int color;
    private CodeArt art;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path arc = new Path();
    private final android.view.animation.DecelerateInterpolator soft =
            new android.view.animation.DecelerateInterpolator(1.8f);
    private android.animation.TimeAnimator animator;
    private boolean playing;
    /** Time into the animation. */
    private float elapsed;
    /** When the real code was there: 0 from the start, -1 while it is still coming. */
    private float artAt = -1f;

    CodeView(Context context, CodeArt art, boolean paper) {
        super(context);
        this.art = art;
        this.color = paper ? Color.BLACK : Color.argb(230, 255, 255, 255);
        barPaint.setColor(color);
        clear.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        clear.setStyle(Paint.Style.STROKE);
        clear.setStrokeCap(Paint.Cap.ROUND);
    }

    /** The real code has arrived: the next sweep lands on it. */
    void setArt(CodeArt art) {
        if (art == null) return;
        this.art = art;
        if (playing) {
            artAt = elapsed;
        } else {
            invalidate();
        }
    }

    void play(long delay) {
        if (animator != null) animator.cancel();
        playing = true;
        elapsed = 0f;
        artAt = art != null ? 0f : -1f;
        invalidate();
        animator = new android.animation.TimeAnimator();
        animator.setTimeListener((animation, totalTime, deltaTime) -> {
            elapsed = Math.max(0f, totalTime - delay);
            int last = finalSweep();
            if (last >= 0 && elapsed >= hit(last, STAND_IN_BARS - 1) + RISE_MS + RING_OUT_MS) {
                playing = false;
                animation.cancel();
            }
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (animator != null) animator.cancel();
    }

    /** The sweep that lands on the real code: the first to start once it is here; -1 before. */
    private int finalSweep() {
        if (artAt < 0f) return -1;
        if (artAt <= FIRST_SWEEP) return 0;
        return (int) Math.ceil((artAt - FIRST_SWEEP) / SWEEP_PERIOD);
    }

    /** When sweep {@code k} reaches bar {@code i}. */
    private static float hit(int k, int i) {
        float along = Math.min(1f, i / (float) (STAND_IN_BARS - 1));
        return FIRST_SWEEP + k * SWEEP_PERIOD + along * SWEEP_SPAN;
    }

    private static float smooth(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }

    /** A fixed, varied peak per bar: a waveform's shape, not a random flicker. */
    private static float peakShare(int i) {
        double n = Math.sin(i * 12.9898 + 4.1) * 43758.5453;
        float r = (float) (n - Math.floor(n));
        return 0.55f + 0.45f * r;
    }

    /** Kick from {@code from} to {@code peak}, then a damped spring settling on {@code rest}. */
    private static float kick(float tau, float from, float peak, float rest) {
        if (tau < RISE_MS) {
            float t = tau / RISE_MS;
            return from + (peak - from) * (1f - (1f - t) * (1f - t));
        }
        float u = tau - RISE_MS;
        return rest + (peak - rest) * (float) (Math.exp(-u / SPRING_DECAY_MS) * Math.cos(u / SPRING_PERIOD_MS));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        boolean real = art != null && art.logo != null;
        if (!playing) {
            if (art == null) return;
            bitmapPaint.setAlpha(255);
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(art.ink, null, rect, bitmapPaint);
            return;
        }
        if (art != null && !real) {
            // An image that could not be read as bars: it simply fades in.
            bitmapPaint.setAlpha(Math.round(255 * smooth(0f, 400f, elapsed - Math.max(0f, artAt))));
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(art.ink, null, rect, bitmapPaint);
            return;
        }
        float unit = getWidth() / 400f;
        float sx = real ? getWidth() / (float) art.ink.getWidth() : 0f;
        float sy = real ? getHeight() / (float) art.ink.getHeight() : 0f;
        float standInMid = 50f * unit;

        // The logo fades up from a little smaller; the stand-in gives way to the real mark.
        float lt = Math.min(1f, elapsed / LOGO_MS);
        if (lt > 0f) {
            float scale = 0.72f + 0.28f * soft.getInterpolation(lt);
            int alpha = Math.round(255 * smooth(0f, 0.6f, lt));
            float cx = real ? art.logo.exactCenterX() * sx : 50f * unit;
            float cy = real ? art.logo.exactCenterY() * sy : standInMid;
            canvas.save();
            canvas.scale(scale, scale, cx, cy);
            if (real) {
                bitmapPaint.setAlpha(alpha);
                rect.set(art.logo.left * sx, art.logo.top * sy, art.logo.right * sx, art.logo.bottom * sy);
                canvas.drawBitmap(art.ink, art.logo, rect, bitmapPaint);
            } else {
                drawStandInLogo(canvas, cx, cy, 31f * unit, alpha);
            }
            canvas.restore();
        }

        // The bars, each driven by the latest sweep to have reached it.
        int last = finalSweep();
        float loud = real ? art.tallest * sy : 60f * unit;
        float resting = loud * 0.2f;
        int realBars = real ? art.barTop.length : 0;
        int bars = Math.max(STAND_IN_BARS, realBars);
        int baseAlpha = Color.alpha(color);
        for (int i = 0; i < bars; i++) {
            int k = (int) Math.floor((elapsed - hit(0, i)) / SWEEP_PERIOD);
            if (elapsed < hit(0, i)) continue;
            if (last >= 0) k = Math.min(k, last);
            float tau = elapsed - hit(k, i);
            boolean landing = k == last;
            float standInLeft = (100f + Math.min(i, STAND_IN_BARS - 1) * 12.83f) * unit;
            float standInWidth = 6f * unit;
            float peak = Math.min(getHeight(), loud * peakShare(i) * 1.1f);
            float from = k == 0 ? 0f : resting;
            float left, right, height, centre;
            if (landing && i < realBars) {
                float top = art.barTop[i] * sy;
                float bottom = art.barBottom[i] * sy;
                float realLeft = art.barLeft[i] * sx;
                float realRight = art.barRight[i] * sx;
                // Bars that rested as the stand-in (the code came late) glide to their place.
                float glide = k == 0 ? 1f : smooth(0f, RISE_MS + 260f, tau);
                left = standInLeft + (realLeft - standInLeft) * glide;
                right = left + (standInWidth + (realRight - realLeft - standInWidth) * glide);
                float target = bottom - top;
                height = kick(tau, from, Math.max(peak, target * 1.15f), target);
                centre = standInMid + ((top + bottom) / 2f - standInMid) * glide;
            } else if (i < STAND_IN_BARS) {
                // The stand-in, resting low between sweeps; one the real code lacks bows out.
                left = standInLeft;
                right = left + standInWidth;
                height = kick(tau, from, peak, landing ? 0f : resting);
                centre = standInMid;
                if (landing && tau > RISE_MS + 300f) continue;
            } else {
                continue;
            }
            float width = right - left;
            height = Math.max(width, height);
            rect.set(left, centre - height / 2f, right, centre + height / 2f);
            float appear = k == 0 ? Math.min(1f, tau / 90f) : 1f;
            barPaint.setAlpha(Math.round(baseAlpha * appear));
            canvas.drawRoundRect(rect, width / 2f, width / 2f, barPaint);
        }
    }

    /** Spotify's mark before the real code is here: a disc with its three arcs cut out. */
    private void drawStandInLogo(Canvas canvas, float cx, float cy, float r, int alpha) {
        int layer = canvas.saveLayer(cx - r - 2, cy - r - 2, cx + r + 2, cy + r + 2, null);
        barPaint.setAlpha(Math.round(Color.alpha(color) * alpha / 255f));
        canvas.drawCircle(cx, cy, r, barPaint);
        float[] ys = {-0.3f, 0.02f, 0.3f};
        float[] widths = {1.18f, 0.98f, 0.78f};
        float[] strokes = {0.17f, 0.14f, 0.12f};
        for (int k = 0; k < 3; k++) {
            float y = cy + ys[k] * r;
            float half = widths[k] * r / 2f;
            arc.reset();
            arc.moveTo(cx - half, y + 0.1f * r);
            arc.quadTo(cx, y - 0.16f * r, cx + half, y + 0.02f * r);
            clear.setStrokeWidth(strokes[k] * r);
            canvas.drawPath(arc, clear);
        }
        canvas.restoreToCount(layer);
    }
}
