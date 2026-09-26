package com.eza.spicyex.lyrics;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.swiperefreshlayout.widget.CircularProgressDrawable;

import com.eza.spicyex.ui.ActionIconDrawable;

/**
 * Toggle-chip progress spinner. We do NOT hand-draw the arc — this wraps androidx's
 * {@link CircularProgressDrawable} (the standard Material indeterminate refresh spinner used across
 * the platform), which is polished and proven and needs no Material theme. The wrapper only:
 * <ol>
 *   <li>draws nothing while inactive (the bare CircularProgressDrawable would draw a static arc),</li>
 *   <li>sizes the ring to ride the chip's rim,</li>
 *   <li>exposes a simple {@link #setActive} and forwards the inner drawable's animation
 *       invalidations to the host view (so it repaints as a chip foreground).</li>
 * </ol>
 */
public final class ChipSpinnerDrawable extends Drawable {
    private static final int AI_GREEN = 0xFF1ED760;
    private static final int AI_RUNNING_GRAY = 0xFF8E8E93;
    private static final int AI_FAILED_RED = 0xFFE91429;

    private final CircularProgressDrawable inner;
    private final android.graphics.drawable.Drawable aiBadge;
    private boolean active;
    private boolean aiActive;
    private boolean aiOutput;
    private boolean aiFailed;

    private final Callback relay = new Callback() {
        @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
        @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
        @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
    };

    public ChipSpinnerDrawable(Context context) {
        inner = new CircularProgressDrawable(context);
        inner.setStyle(CircularProgressDrawable.DEFAULT);
        inner.setColorSchemeColors(0xB3FFFFFF);
        inner.setStrokeCap(Paint.Cap.ROUND);
        inner.setCallback(relay);
        aiBadge = new ActionIconDrawable(ActionIconDrawable.Kind.SPARKLE,
                AI_GREEN, context.getResources().getDisplayMetrics().density);
        updateAiBadgeTint();
    }

    /**
     * Marks this control as currently showing AI output.
     *
     * <p>A green sparkle taken from the Spicy Lyrics mark, because the reader needs to know which
     * of two possible answers they are looking at — a model's or an engine's — and the control is
     * the only place that distinction belongs. During an AI request the same sparkle stays visible
     * beside a green ring, distinct from the neutral ring used for local/provider processing.
     */
    public void setAiOutput(boolean value) {
        if (value == aiOutput) return;
        aiOutput = value;
        updateAiBadgeTint();
        invalidateSelf();
    }

    public boolean isAiOutput() {
        return aiOutput;
    }

    /** Marks an in-flight AI request independently from ordinary layer processing. */
    public void setAiActive(boolean value) {
        if (value == aiActive) return;
        aiActive = value;
        inner.setColorSchemeColors(value ? AI_GREEN : 0xB3FFFFFF);
        updateAiBadgeTint();
        invalidateSelf();
    }

    public boolean isAiActive() {
        return aiActive;
    }

    /**
     * Marks the last AI request for this control as failed. The sparkle turns red and stays red
     * over any older accepted output, because the failure describes the latest operation; the
     * review panel explains what remains on screen. Cleared by the next running or success state.
     */
    public void setAiFailed(boolean value) {
        if (value == aiFailed) return;
        aiFailed = value;
        updateAiBadgeTint();
        invalidateSelf();
    }

    /** An ordinary (not AI) run failed: a small red "!" on the rim, tap to retry. */
    private boolean failed;
    private final android.graphics.Paint failPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

    public void setFailed(boolean value) {
        if (value == failed) return;
        failed = value;
        invalidateSelf();
    }

    public boolean isFailed() {
        return failed;
    }

    private void drawFailedMark(Canvas canvas) {
        Rect b = getBounds();
        int size = Math.min(b.width(), b.height());
        if (size <= 0) return;
        float r = size * 0.17f;
        float cx = b.right - r;
        float cy = b.top + r;
        failPaint.setStyle(android.graphics.Paint.Style.FILL);
        failPaint.setColor(AI_FAILED_RED);
        canvas.drawCircle(cx, cy, r, failPaint);
        failPaint.setColor(0xFFFFFFFF);
        failPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        failPaint.setStrokeWidth(Math.max(1.5f, r * 0.3f));
        canvas.drawLine(cx, cy - r * 0.5f, cx, cy + r * 0.12f, failPaint);
        canvas.drawCircle(cx, cy + r * 0.48f, r * 0.15f, failPaint);
    }

    public boolean isAiFailed() {
        return aiFailed;
    }

    private void updateAiBadgeTint() {
        if (aiBadge != null) {
            aiBadge.setTint(aiFailed ? AI_FAILED_RED : aiActive ? AI_RUNNING_GRAY : AI_GREEN);
        }
    }

    /** Start/stop the spinner. Idempotent — safe to call every frame. */
    public void setActive(boolean value) {
        if (value == active) return;
        active = value;
        if (value) {
            inner.start();
        } else {
            inner.stop();
        }
        invalidateSelf();
    }

    public boolean isActive() {
        return active;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        int size = Math.min(bounds.width(), bounds.height());
        if (size <= 0) return;
        float stroke = Math.max(3f, size * 0.075f);
        inner.setStrokeWidth(stroke);
        // Ring centered near the rim so it spins on the button edge.
        inner.setCenterRadius(size / 2f - stroke);
        inner.setBounds(bounds);
        if (aiBadge != null) {
            // A quarter-size mark on the lower-right rim, clear of the glyph underneath.
            int badge = Math.max(1, Math.round(size * 0.42f));
            int right = bounds.right;
            int bottom = bounds.bottom;
            aiBadge.setBounds(right - badge, bottom - badge, right, bottom);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        if (active) {
            inner.draw(canvas);
            if ((aiActive || aiFailed) && aiBadge != null) aiBadge.draw(canvas);
            return;
        }
        if ((aiFailed || aiOutput) && aiBadge != null) aiBadge.draw(canvas);
        if (failed && !aiFailed) drawFailedMark(canvas);
    }

    @Override
    public void setAlpha(int alpha) {
        inner.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        inner.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
