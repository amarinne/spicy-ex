package com.eza.spicyex.lyrics;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Shimmering skeleton placeholder shown while lyrics are loading, in place of a "Loading…" line.
 * Draws a column of rounded bars of varying widths with a translucent highlight band that sweeps
 * across them. The sweep animator is tied to window attach state, so it never leaks when the
 * loading view is swapped out for the rendered lyrics (or an error).
 */
public final class LyricsSkeletonView extends View {
    // Relative bar widths (fraction of content width); irregular widths read as lyric lines.
    private static final float[] BAR_WIDTHS = {0.74f, 0.52f, 0.86f, 0.43f, 0.68f, 0.58f, 0.80f, 0.48f};

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix shimmerMatrix = new Matrix();
    private final float density;
    private LinearGradient shimmer;
    private ValueAnimator animator;
    private float sweep;
    private int horizontalPaddingPx;

    public LyricsSkeletonView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        barPaint.setColor(0x1FFFFFFF);
        horizontalPaddingPx = dp(18);
    }

    public void setHorizontalPaddingPx(int px) {
        if (px >= 0 && px != horizontalPaddingPx) {
            horizontalPaddingPx = px;
            invalidate();
        }
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private int barHeightPx() {
        return dp(26);
    }

    private int gapPx() {
        return dp(24);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = (barHeightPx() + gapPx()) * BAR_WIDTHS.length;
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0) return;
        shimmer = new LinearGradient(0f, 0f, w, 0f,
                new int[]{0x00FFFFFF, 0x33FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        shimmerPaint.setShader(shimmer);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int padLeft = Math.max(horizontalPaddingPx, getPaddingLeft());
        int padRight = Math.max(horizontalPaddingPx, getPaddingRight());
        int contentWidth = getWidth() - padLeft - padRight;
        if (contentWidth <= 0) return;
        float radius = dp(9);
        float left = padLeft;
        if (shimmer != null) {
            shimmerMatrix.setTranslate((sweep * 2f - 1f) * getWidth(), 0f);
            shimmer.setLocalMatrix(shimmerMatrix);
        }
        int barHeight = barHeightPx();
        int step = barHeight + gapPx();
        for (int i = 0; i < BAR_WIDTHS.length; i++) {
            float top = i * step;
            float right = left + contentWidth * BAR_WIDTHS[i];
            rect.set(left, top, right, top + barHeight);
            canvas.drawRoundRect(rect, radius, radius, barPaint);
            if (shimmer != null) canvas.drawRoundRect(rect, radius, radius, shimmerPaint);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(1150);
            animator.setInterpolator(new LinearInterpolator());
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.addUpdateListener(a -> {
                sweep = (float) a.getAnimatedValue();
                invalidate();
            });
        }
        if (!animator.isStarted()) animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }
}
