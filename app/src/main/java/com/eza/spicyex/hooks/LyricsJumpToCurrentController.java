package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeIconButtons.applyPressScale;

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
import com.eza.spicyex.ui.SettingsUiStrings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.lyrics.LyricsTextFactory;

/** Owns the floating "jump back to active lyric" affordance. */
final class LyricsJumpToCurrentController {
    private static final int MOTION_OFFSET_DP = 8;
    private static final long COLLAPSE_DELAY_MS = 3200L;
    private static final int SIDE_MARGIN_DP = NativeLyricsUtils.EDGE_BUTTON_MARGIN_DP;
    private static final int CHIP_HEIGHT_DP = 44;
    private static final int ICON_DP = 18;
    private static final long COLLAPSE_DURATION_MS = 320L;

    private final SpotifyPlusConfig config;
    private final String followLabel;
    private final TextView button;
    private final PillProgressDrawable progressDrawable = new PillProgressDrawable();
    private final WaveformIconDrawable waveIcon = new WaveformIconDrawable();
    private final Runnable collapse = this::collapseToIcon;
    private ValueAnimator widthAnimator;
    private String style = Settings.FOLLOW_CHIP_STYLE.defaultValue;
    private String position = Settings.FOLLOW_CHIP_POSITION.defaultValue;
    private boolean shown;
    private boolean editingForcedVisible;
    /** Latest real follow-state request, tracked even while the editor pins the preview visible. */
    private boolean requestedShown;

    private LyricsJumpToCurrentController(SpotifyPlusConfig config, TextView button,
            String followLabel) {
        this.config = config;
        this.button = button;
        this.followLabel = followLabel;
    }

    static LyricsJumpToCurrentController attach(
            Activity activity,
            FrameLayout parent,
            LyricsTextFactory textFactory,
            SpotifyPlusConfig config,
            SettingsUiStrings strings,
            Runnable onClick
    ) {
        TextView view = textFactory.createChip(activity, "");
        view.setTextSize(13);
        view.setAlpha(0f);
        view.setVisibility(View.GONE);
        view.setElevation(dp(8));
        applyPressScale(view);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(CHIP_HEIGHT_DP),
                Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, dp(SIDE_MARGIN_DP), dp(24));
        parent.addView(view, lp);

        LyricsJumpToCurrentController controller =
                new LyricsJumpToCurrentController(config, view,
                        strings == null ? "Follow lyrics"
                                : strings.get("lyrics_follow_chip_label", "Follow lyrics"));
        view.setBackground(controller.progressDrawable);
        controller.onPreferenceChanged();
        view.setOnClickListener(v -> {
            controller.update(false);
            if (onClick != null) onClick.run();
        });
        return controller;
    }

    /** Re-applies the editor-owned style and horizontal anchor. */
    void onPreferenceChanged() {
        String nextStyle = config == null ? null : config.get(Settings.FOLLOW_CHIP_STYLE);
        style = nextStyle == null ? Settings.FOLLOW_CHIP_STYLE.defaultValue : nextStyle;
        String nextPosition = config == null ? null : config.get(Settings.FOLLOW_CHIP_POSITION);
        position = nextPosition == null ? Settings.FOLLOW_CHIP_POSITION.defaultValue : nextPosition;
        applyPosition();
        if (button.getVisibility() == View.VISIBLE) applyStyle(false);
    }

    private void applyPosition() {
        ViewGroup.LayoutParams raw = button.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        if ("Left".equals(position)) {
            lp.gravity = Gravity.BOTTOM | Gravity.START;
            lp.leftMargin = dp(SIDE_MARGIN_DP);
            lp.rightMargin = 0;
        } else if ("Center".equals(position)) {
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.leftMargin = 0;
            lp.rightMargin = 0;
        } else {
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.leftMargin = 0;
            lp.rightMargin = dp(SIDE_MARGIN_DP);
        }
        button.setLayoutParams(lp);
    }

    private void applyStyle(boolean appearing) {
        button.removeCallbacks(collapse);
        if ("Icon".equals(style)) {
            applyCollapsed(true);
        } else {
            applyCollapsed(false);
            if (!"Label".equals(style)) {
                button.postDelayed(collapse, appearing ? COLLAPSE_DELAY_MS : COLLAPSE_DELAY_MS / 2);
            }
        }
    }

    private void applyCollapsed(boolean collapsed) {
        cancelWidthAnimation();
        button.setText(collapsed ? "" : followLabel);
        button.setContentDescription(followLabel);
        waveIcon.setBounds(0, 0, dp(ICON_DP), dp(ICON_DP));
        button.setCompoundDrawablesRelative(waveIcon, null, null, null);
        button.setCompoundDrawablePadding(collapsed ? 0 : dp(8));
        // With no text the glyph sits at the start edge, not centred: the padding centres it.
        int iconInset = (dp(CHIP_HEIGHT_DP) - dp(ICON_DP)) / 2;
        button.setPaddingRelative(collapsed ? iconInset : dp(16), 0, collapsed ? 0 : dp(18), 0);
        ViewGroup.LayoutParams lp = button.getLayoutParams();
        if (lp != null) {
            lp.width = collapsed ? dp(CHIP_HEIGHT_DP) : ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.height = dp(CHIP_HEIGHT_DP);
            button.setLayoutParams(lp);
        }
    }

    /** The label pill narrows to the round icon, its text fading as it goes. */
    private void collapseToIcon() {
        if (button.getVisibility() != View.VISIBLE) return;
        int startWidth = button.getWidth();
        int endWidth = dp(CHIP_HEIGHT_DP);
        if (startWidth <= endWidth || widthAnimator != null) {
            applyCollapsed(true);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofInt(startWidth, endWidth);
        animator.setDuration(COLLAPSE_DURATION_MS);
        animator.setInterpolator(new android.view.animation.PathInterpolator(0.3f, 0f, 0.1f, 1f));
        animator.addUpdateListener(a -> {
            int width = (Integer) a.getAnimatedValue();
            ViewGroup.LayoutParams lp = button.getLayoutParams();
            if (lp == null) return;
            lp.width = width;
            button.setLayoutParams(lp);
            float progress = (float) (startWidth - width) / Math.max(1, startWidth - endWidth);
            button.setTextColor(Color.argb(Math.round(255 * Math.max(0f, 1f - progress * 1.6f)),
                    255, 255, 255));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (widthAnimator == animation) widthAnimator = null;
                button.setTextColor(Color.WHITE);
                if (!cancelled && button.getVisibility() == View.VISIBLE) applyCollapsed(true);
            }
        });
        widthAnimator = animator;
        animator.start();
    }

    private void cancelWidthAnimation() {
        ValueAnimator animator = widthAnimator;
        widthAnimator = null;
        if (animator != null) {
            animator.cancel();
            button.setTextColor(Color.WHITE);
        }
    }

    void update(boolean show) {
        requestedShown = show;
        applyShown(LyricsLayoutEditorRuntimePolicy.chipShouldBeVisible(
                requestedShown, editingForcedVisible));
    }

    private void applyShown(boolean show) {
        boolean wasShown = shown;
        if (show == wasShown) {
            if (show && button.getVisibility() == View.VISIBLE && button.getAlpha() < 0.9f) {
                button.setAlpha(0.92f);
            }
            return;
        }

        shown = show;

        if (show) {
            applyStyle(true);
            button.animate().cancel();
            if (button.getVisibility() != View.VISIBLE) {
                button.setVisibility(View.VISIBLE);

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
                button.animate().alpha(0.92f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(180).start();
            }
        } else {
            button.removeCallbacks(collapse);
            cancelWidthAnimation();
            if (button.getVisibility() == View.VISIBLE) {
                button.animate().cancel();
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
            }
        }
    }

    void setProgress(float value) {
        if (button.getBackground() != progressDrawable) {
            button.setBackground(progressDrawable);
        }
        progressDrawable.setProgress(value);
    }

    void fadeProgress() {
        progressDrawable.fadeOut();
    }

    void resetProgress() {
        progressDrawable.reset();
    }

    /** The real on-screen chip, for the layout editor's tap/outline overlay. */
    View view() {
        return button;
    }

    /** Layout editor preview: shows the chip even with follow-state not actually away from the
     *  active line right now, so its position can still be edited. A no-op if real follow-state
     *  already has it showing - {@link #restoreAfterEditing()} must never hide that. */
    void showForEditing() {
        editingForcedVisible = true;
        applyShown(LyricsLayoutEditorRuntimePolicy.chipShouldBeVisible(
                requestedShown, editingForcedVisible));
    }

    /** Pairs with {@link #showForEditing()}: hides the chip again only if this controller was the
     *  one that forced it visible. */
    void restoreAfterEditing() {
        if (!editingForcedVisible) return;
        editingForcedVisible = false;
        applyShown(LyricsLayoutEditorRuntimePolicy.chipShouldBeVisible(
                requestedShown, editingForcedVisible));
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

    /** Audio-waveform glyph: five rounded bars of varied height, centred. */
    private static final class WaveformIconDrawable extends Drawable {
        private static final float[] HEIGHTS = {0.38f, 0.7f, 1f, 0.62f, 0.3f};
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        WaveformIconDrawable() {
            paint.setColor(Color.WHITE);
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            int n = HEIGHTS.length;
            float slot = b.width() / (float) n;
            float bar = slot * 0.5f;
            for (int i = 0; i < n; i++) {
                float h = b.height() * HEIGHTS[i];
                float cx = b.left + slot * (i + 0.5f);
                float cy = b.exactCenterY();
                canvas.drawRoundRect(cx - bar / 2f, cy - h / 2f, cx + bar / 2f, cy + h / 2f,
                        bar / 2f, bar / 2f, paint);
            }
        }

        @Override public int getIntrinsicWidth() { return dp(ICON_DP); }
        @Override public int getIntrinsicHeight() { return dp(ICON_DP); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
        }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /**
     * The chip's countdown fill. It follows the countdown as it rises; when the countdown drops
     * (a touch on the list restarts it) the fill drains back instead of vanishing; and when
     * playback pauses it dissolves like mist - its edge softens and spreads while it fades - all
     * inside the pill's outline.
     */
    private static final class PillProgressDrawable extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fog = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path clip = new android.graphics.Path();
        private final RectF bounds = new RectF();
        private float progress;
        /** 0 = plain fill, 1 = fully dissolved. */
        private float mist;
        private ValueAnimator drainAnimator;
        private float drainTarget = -1f;
        private ValueAnimator mistAnimator;

        PillProgressDrawable() {
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(1));
            stroke.setColor(Color.argb(52, 255, 255, 255));
        }

        void setProgress(float value) {
            float next = Math.max(0f, Math.min(1f, value));
            if (mist > 0f) {
                // Playing again after a pause: the dissolved fill starts over from here.
                cancelMist();
                mist = 0f;
                progress = next;
                invalidateSelf();
                return;
            }
            if (drainAnimator != null) {
                // Draining toward a lower value: let it finish unless the countdown has risen
                // past what is shown again.
                if (next <= progress + 0.005f) return;
                cancelDrain();
            }
            if (next < progress - 0.02f) {
                drainTo(next);
                return;
            }
            progress = next;
            invalidateSelf();
        }

        /** Playback paused: the fill dissolves. */
        void fadeOut() {
            if (progress <= 0.001f || mist >= 1f || mistAnimator != null) return;
            cancelDrain();
            ValueAnimator animator = ValueAnimator.ofFloat(mist, 1f);
            mistAnimator = animator;
            animator.setDuration(700L);
            animator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.2f));
            animator.addUpdateListener(a -> {
                mist = (Float) a.getAnimatedValue();
                invalidateSelf();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    if (mistAnimator == animation) mistAnimator = null;
                }
            });
            animator.start();
        }

        /** The list was touched: the fill drains back to empty. */
        void reset() {
            cancelMist();
            mist = 0f;
            if (progress <= 0.001f) {
                progress = 0f;
                invalidateSelf();
                return;
            }
            drainTo(0f);
        }

        private void drainTo(float target) {
            if (drainAnimator != null && Math.abs(drainTarget - target) < 0.005f) return;
            cancelDrain();
            ValueAnimator animator = ValueAnimator.ofFloat(progress, target);
            drainAnimator = animator;
            drainTarget = target;
            // A longer fill takes a little longer to drain, never sluggishly.
            animator.setDuration(Math.round(180L + 220L * (progress - target)));
            animator.setInterpolator(new android.view.animation.PathInterpolator(0.3f, 0f, 0.1f, 1f));
            animator.addUpdateListener(a -> {
                progress = (Float) a.getAnimatedValue();
                invalidateSelf();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    if (drainAnimator == animation) {
                        drainAnimator = null;
                        drainTarget = -1f;
                    }
                }
            });
            animator.start();
        }

        private void cancelDrain() {
            ValueAnimator animator = drainAnimator;
            drainAnimator = null;
            drainTarget = -1f;
            if (animator != null) animator.cancel();
        }

        private void cancelMist() {
            ValueAnimator animator = mistAnimator;
            mistAnimator = null;
            if (animator != null) animator.cancel();
        }

        @Override public void draw(Canvas canvas) {
            bounds.set(getBounds());
            float radius = Math.min(bounds.width(), bounds.height()) * 0.5f;
            fill.setShader(null);
            fill.setColor(Color.argb(48, 255, 255, 255));
            canvas.drawRoundRect(bounds, radius, radius, fill);
            if (progress > 0f && mist < 1f) {
                // Clipped to the pill: a plain rectangle inside the clip, since rounding the
                // partial fill itself makes its left edge swell out of the button.
                clip.rewind();
                clip.addRoundRect(bounds, radius, radius, android.graphics.Path.Direction.CW);
                canvas.save();
                canvas.clipPath(clip);
                float right = bounds.left + bounds.width() * progress;
                int alpha = Math.round(35 + 25 * progress);
                if (mist <= 0f) {
                    fill.setColor(Color.argb(alpha, 255, 255, 255));
                    canvas.drawRect(bounds.left, bounds.top, right, bounds.bottom, fill);
                } else {
                    // Mist: the edge feathers out to the right as it spreads, the body thins
                    // from the left, and the whole of it fades.
                    float spread = bounds.width() * 0.45f * mist;
                    float fade = (float) Math.pow(1f - mist, 1.6f);
                    int body = Math.round(alpha * fade);
                    int thin = Math.round(alpha * fade * (1f - 0.6f * mist));
                    fog.setShader(new android.graphics.LinearGradient(
                            bounds.left, 0f, right + spread, 0f,
                            new int[]{Color.argb(thin, 255, 255, 255), Color.argb(body, 255, 255, 255),
                                    Color.argb(0, 255, 255, 255)},
                            new float[]{0f, Math.max(0.05f, Math.min(0.95f,
                                    (right - bounds.left) / Math.max(1f, right + spread - bounds.left)
                                            * (1f - 0.5f * mist))), 1f},
                            android.graphics.Shader.TileMode.CLAMP));
                    canvas.drawRect(bounds.left, bounds.top, right + spread, bounds.bottom, fog);
                }
                canvas.restore();
            }
            canvas.drawRoundRect(bounds, radius, radius, stroke);
        }

        @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); stroke.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {
            fill.setColorFilter(filter); fog.setColorFilter(filter); stroke.setColorFilter(filter);
        }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
