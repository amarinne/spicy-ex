package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeIconButtons.applyPressScale;
import static com.eza.spicyex.hooks.NativeIconButtons.createRoundButtonBackground;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.lyrics.LyricsTextFactory;

/** Owns the floating "jump back to active lyric" affordance. */
final class LyricsJumpToCurrentController {
    private static final int MOTION_OFFSET_DP = 8;

    private final SpotifyPlusConfig config;
    private final TextView button;
    private final PillProgressDrawable progressDrawable = new PillProgressDrawable();
    private boolean shown;

    private LyricsJumpToCurrentController(SpotifyPlusConfig config, TextView button) {
        this.config = config;
        this.button = button;
    }

    static LyricsJumpToCurrentController attach(
            Activity activity,
            FrameLayout parent,
            LyricsTextFactory textFactory,
            SpotifyPlusConfig config,
            Runnable onClick
    ) {
        TextView view = textFactory.createChip(activity, "↓");
        view.setTextSize(13);
        view.setAlpha(0f);
        view.setVisibility(View.GONE);
        view.setBackground(createRoundButtonBackground());
        view.setElevation(dp(8));
        applyPressScale(view);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(44),
                Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, dp(14), dp(24));
        parent.addView(view, lp);

        LyricsJumpToCurrentController controller =
                new LyricsJumpToCurrentController(config, view);
        view.setOnClickListener(v -> {
            controller.update(false);
            if (onClick != null) onClick.run();
        });
        return controller;
    }

    void update(boolean show) {
        boolean wasShown = shown;
        if (show == wasShown) {
            if (show && button.getVisibility() == View.VISIBLE && button.getAlpha() < 0.9f) {
                button.setAlpha(0.92f);
            }
            return;
        }

        shown = show;
        boolean animationEnabled = config != null && Boolean.TRUE.equals(config.get(Settings.FOLLOW_CHIP_ANIMATION));

        if (show) {
            button.animate().cancel();
            if (button.getVisibility() != View.VISIBLE) {
                button.setVisibility(View.VISIBLE);

                if (animationEnabled) {
                    button.setScaleX(0.8f);
                    button.setScaleY(0.8f);
                    button.setAlpha(0f);
                    button.setTranslationY(dp(MOTION_OFFSET_DP));
                    button.animate()
                            .alpha(0.92f)
                            .translationY(0f)
                            .scaleX(1f).scaleY(1f)
                            .setDuration(300)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f))
                            .start();
                } else {
                    // Animation off: appear at once, exactly as before the option existed.
                    button.setScaleX(1f);
                    button.setScaleY(1f);
                    button.setTranslationY(0f);
                    button.setAlpha(0.92f);
                }
            } else {
                button.animate().alpha(0.92f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(180).start();
            }
        } else {
            if (button.getVisibility() == View.VISIBLE) {
                button.animate().cancel();
                if (animationEnabled) {
                    button.animate()
                            .alpha(0f)
                            .scaleX(0.7f)
                            .scaleY(0.7f)
                            .translationY(dp(MOTION_OFFSET_DP))
                            .setDuration(220)
                            .setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f))
                            .withEndAction(() -> {
                                button.setVisibility(View.GONE);
                                button.setScaleX(1f);
                                button.setScaleY(1f);
                                button.setTranslationY(0f);
                            })
                            .start();
                } else {
                    button.setVisibility(View.GONE);
                    button.setAlpha(0f);
                    button.setTranslationY(0f);
                }
            }
        }
    }

    void setProgress(float value) {
        if (config != null && Boolean.TRUE.equals(config.get(Settings.FOLLOW_CHIP_PROGRESS))) {
            if (button.getBackground() != progressDrawable) {
                button.setBackground(progressDrawable);
            }
            progressDrawable.setProgress(value);
        }
    }

    void fadeProgress() {
        if (config == null || !Boolean.TRUE.equals(config.get(Settings.FOLLOW_CHIP_PROGRESS))) return;
        progressDrawable.fadeOut();
    }

    void resetProgress() {
        if (config != null && Boolean.TRUE.equals(config.get(Settings.FOLLOW_CHIP_PROGRESS))) {
            if (button.getBackground() != progressDrawable) button.setBackground(progressDrawable);
            progressDrawable.reset();
        }
    }

    void onPreferenceChanged() {
        if (config != null && !Boolean.TRUE.equals(config.get(Settings.FOLLOW_CHIP_PROGRESS))) {
            if (button.getBackground() == progressDrawable) {
                button.setBackground(createRoundButtonBackground());
            }
        }
    }

    /** Raises the chip above the bottom track-info readout (bottom mode) or restores it. */
    void setBottomMarginDp(int marginDp) {
        ViewGroup.LayoutParams lp = button.getLayoutParams();
        if (!(lp instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
        int target = dp(marginDp);
        if (flp.bottomMargin != target) {
            flp.bottomMargin = target;
            button.setLayoutParams(flp);
        }
    }

    private static final class PillProgressDrawable extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float progress;
        private float progressAlpha = 1f;
        private ValueAnimator progressFadeAnimator;

        PillProgressDrawable() {
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1));
            stroke.setColor(Color.argb(52, 255, 255, 255));
        }

        void setProgress(float value) {
            if (progressFadeAnimator != null) { progressFadeAnimator.cancel(); progressFadeAnimator = null; }
            progressAlpha = 1f;
            progress = Math.max(0f, Math.min(1f, value));
            invalidateSelf();
        }

        void fadeOut() {
            if (progressAlpha <= 0.01f) return;
            if (progressFadeAnimator != null) {
                if (progressFadeAnimator.isRunning()) return;
                progressFadeAnimator = null;
            }
            ValueAnimator animator = ValueAnimator.ofFloat(progressAlpha, 0f);
            progressFadeAnimator = animator;
            animator.setDuration(260L);
            animator.addUpdateListener(a -> {
                progressAlpha = (Float) a.getAnimatedValue();
                invalidateSelf();
            });
            animator.start();
        }

        void reset() {
            if (progressFadeAnimator != null) {
                progressFadeAnimator.cancel();
                progressFadeAnimator = null;
            }
            if (progress <= 0.001f) {
                progressAlpha = 1f;
                invalidateSelf();
                return;
            }
            ValueAnimator animator = ValueAnimator.ofFloat(progress, 0f);
            progressFadeAnimator = animator;
            animator.setDuration(150L);
            animator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
            animator.addUpdateListener(a -> {
                progress = (Float) a.getAnimatedValue();
                progressAlpha = 1f;
                invalidateSelf();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    if (progressFadeAnimator == animation) progressFadeAnimator = null;
                    progress = 0f;
                    progressAlpha = 1f;
                    invalidateSelf();
                }
            });
            animator.start();
        }

        @Override public void draw(Canvas canvas) {
            RectF bounds = new RectF(getBounds());
            float radius = Math.min(bounds.width(), bounds.height()) * 0.5f;
            fill.setColor(Color.argb(48, 255, 255, 255));
            canvas.drawRoundRect(bounds, radius, radius, fill);
            if (progress > 0f) {
                float right = bounds.left + bounds.width() * progress;
                canvas.save();
                canvas.clipPath(roundRectPath(bounds, radius));
                fill.setColor(Color.argb(Math.round((35 + 25 * progress) * progressAlpha), 255, 255, 255));
                canvas.drawRect(bounds.left, bounds.top, right, bounds.bottom, fill);
                canvas.restore();
            }
            canvas.drawRoundRect(bounds, radius, radius, stroke);
        }

        @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); stroke.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {
            fill.setColorFilter(filter); stroke.setColorFilter(filter);
        }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }

        private android.graphics.Path roundRectPath(RectF rect, float radius) {
            android.graphics.Path path = new android.graphics.Path();
            path.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW);
            return path;
        }
    }
}
