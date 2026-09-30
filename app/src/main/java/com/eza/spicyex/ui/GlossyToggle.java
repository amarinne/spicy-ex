package com.eza.spicyex.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * A glossy, animated pill toggle (Spicy-6 styling) — canvas-drawn so it doesn't depend on platform
 * Switch theming. Off = translucent track; On = accent track with a vertical sheen + a soft accent
 * glow behind a glossy (radial-highlight) thumb that slides with a spring-ish ease.
 */
public final class GlossyToggle extends View {
    private int accent = 0xFF1ED760;
    private boolean checked;
    private float progress; // 0 = off, 1 = on
    private ValueAnimator animator;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final android.graphics.Matrix thumbMatrix = new android.graphics.Matrix();
    private Shader sheenShader;       // cached (size-dependent only)
    private RadialGradient thumbShader; // cached, centered at origin, positioned via thumbMatrix
    private float thumbR;
    private Runnable onChange;

    public GlossyToggle(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> setChecked(!checked, true));
    }

    public void setAccent(int color) {
        accent = color;
        invalidate();
    }

    public boolean isChecked() {
        return checked;
    }

    public void setOnChangeListener(Runnable r) {
        onChange = r;
    }

    public void setChecked(boolean value, boolean animate) {
        if (value == checked && progress == (value ? 1f : 0f)) return;
        checked = value;
        if (animate) {
            if (animator != null) animator.cancel();
            animator = ValueAnimator.ofFloat(progress, value ? 1f : 0f);
            animator.setDuration(220);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        } else {
            progress = value ? 1f : 0f;
            invalidate();
        }
        if (onChange != null) onChange.run();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(dp(50), dp(30));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // Build shaders once per size (not per frame) to avoid allocation churn / jank.
        float pad = dp(1.5f);
        thumbR = (h - pad * 2) / 2f - dp(2);
        sheenShader = new LinearGradient(0, pad, 0, h / 2f, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        thumbShader = new RadialGradient(0, 0, Math.max(1f, thumbR * 1.4f), 0xFFFFFFFF, 0xFFE3E6EA, Shader.TileMode.CLAMP);
        sheenPaint.setShader(sheenShader);
        thumbPaint.setShader(thumbShader);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (sheenShader == null) return;
        float w = getWidth(), h = getHeight();
        float pad = dp(1.5f);
        float r = (h - pad * 2) / 2f;

        // Track: lerp translucent-gray -> accent (cheap, no allocation).
        trackPaint.setColor(lerpColor(0x33FFFFFF, accent, progress));
        rect.set(pad, pad, w - pad, h - pad);
        canvas.drawRoundRect(rect, r, r, trackPaint);

        sheenPaint.setAlpha((int) (90 + 90 * progress));
        canvas.drawRoundRect(rect, r, r, sheenPaint);

        float cy = h / 2f;
        float left = pad + dp(2) + thumbR;
        float right = w - pad - dp(2) - thumbR;
        float cx = left + (right - left) * progress;

        if (progress > 0f) {
            glowPaint.setColor(accent);
            glowPaint.setAlpha((int) (120 * progress));
            canvas.drawCircle(cx, cy, thumbR + dp(3) * progress, glowPaint);
        }

        // Position the cached thumb highlight via matrix instead of allocating a new shader.
        thumbMatrix.setTranslate(cx - thumbR * 0.3f, cy - thumbR * 0.4f);
        thumbShader.setLocalMatrix(thumbMatrix);
        canvas.drawCircle(cx, cy, thumbR, thumbPaint);
    }

    private static int lerpColor(int a, int b, float t) {
        int aa = (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t);
        int rr = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int gg = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bb = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.argb(aa, rr, gg, bb);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
