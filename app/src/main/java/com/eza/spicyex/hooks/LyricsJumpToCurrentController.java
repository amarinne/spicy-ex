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
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.lyrics.LyricsTextFactory;
import com.eza.spicyex.ui.ActionIconDrawable;

/** Owns the floating "jump back to active lyric" affordance. Which side it floats at is
 *  {@link Settings#FOLLOW_CHIP_POSITION}; which of icon/label/auto-collapse it presents as is
 *  {@link Settings#FOLLOW_CHIP_STYLE} (same three states and behavior as
 *  {@link Settings#SKIP_CHIP_STYLE} - see {@link LyricsSkipGapController}, which stacks above
 *  this one only when both share the same horizontal anchor. */
final class LyricsJumpToCurrentController {
    /** How long the label stays before the pill collapses to its icon, in "Auto". */
    private static final long COLLAPSE_DELAY_MS = 3200L;
    private static final long COLLAPSE_DURATION_MS = 260L;
    private static final long ENTER_DURATION_MS = 220L;
    private static final long EXIT_DURATION_MS = 200L;
    private static final float ENTER_SCALE = 0.985f;
    private static final float EXIT_SCALE = 0.995f;
    private static final int MOTION_OFFSET_DP = 8;
    private static final int ICON_SIZE_DP = 40;
    private static final int TEXT_COLOR = Color.rgb(232, 232, 238);
    private static final int SIDE_MARGIN_DP = 16;

    private final SpotifyPlusConfig config;
    private final LinearLayout pill;
    private final TextView label;
    private final FrameLayout.LayoutParams lp;
    private final PillProgressDrawable progressDrawable = new PillProgressDrawable();
    private final Runnable collapse = this::collapseToIcon;
    private String position = Settings.FOLLOW_CHIP_POSITION.defaultValue;
    private String style = Settings.FOLLOW_CHIP_STYLE.defaultValue;
    private boolean shown;
    private boolean collapsed;
    private ValueAnimator widthAnimator;
    /** Set only by {@link #showForEditing()}, so {@link #restoreAfterEditing()} never hides a
     *  chip real follow-state put up on its own. */
    private boolean editingForcedVisible;

    boolean isVisible() {
        return shown || editingForcedVisible;
    }

    private LyricsJumpToCurrentController(SpotifyPlusConfig config, LinearLayout pill,
            TextView label, FrameLayout.LayoutParams lp) {
        this.config = config;
        this.pill = pill;
        this.label = label;
        this.lp = lp;
    }

    static LyricsJumpToCurrentController attach(
            Activity activity,
            FrameLayout parent,
            LyricsTextFactory textFactory,
            SpotifyPlusConfig config,
            Runnable onClick
    ) {
        float density = activity.getResources().getDisplayMetrics().density;

        LinearLayout pill = new LinearLayout(activity);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setBackground(pillBackground());
        pill.setPadding(dp(12), 0, dp(14), 0);
        pill.setClickable(true);
        pill.setFocusable(true);
        pill.setAlpha(0f);
        pill.setVisibility(View.GONE);
        pill.setElevation(dp(200));
        applyPressScale(pill);

        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(new ActionIconDrawable(
                ActionIconDrawable.Kind.AUDIO_LINES, TEXT_COLOR, density));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        pill.addView(icon, iconLp);

        TextView label = new TextView(activity);
        label.setText("Follow lyrics");
        label.setTextColor(TEXT_COLOR);
        label.setTextSize(13f);
        label.setSingleLine(true);
        label.setIncludeFontPadding(false);
        label.setTypeface(textFactory.resolveTypeface(false));
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = dp(6);
        pill.addView(label, labelLp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(ICON_SIZE_DP),
                Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, dp(16), dp(24));
        parent.addView(pill, lp);

        LyricsJumpToCurrentController controller =
                new LyricsJumpToCurrentController(config, pill, label, lp);
        pill.setOnClickListener(v -> {
            controller.update(false);
            if (onClick != null) onClick.run();
        });
        controller.onPreferenceChanged();
        return controller;
    }

    void update(boolean show) {
        if (editingForcedVisible && !show) return;
        boolean wasShown = shown;
        if (show == wasShown) {
            // Already in the correct target state. Do nothing except ensuring the alpha
            // stays applied if it's currently visible (to cover edge cases where 
            // setAlpha might have been externally modified).
            if (show && pill.getVisibility() == View.VISIBLE && pill.getAlpha() < 0.9f) {
                pill.setAlpha(0.92f);
            }
            return;
        }

        shown = show;
        boolean appearing = show && !wasShown;
        boolean animationEnabled = config != null && config.get(Settings.FOLLOW_CHIP_ANIMATION);

        if (show) {
            // A new show can arrive while the previous exit animation is still running. Cancel
            // that animator before restoring the chip, otherwise its old end action can hide the
            // newly shown chip a frame later.
            pill.animate().cancel();
            if (pill.getVisibility() != View.VISIBLE) {
                pill.setVisibility(View.VISIBLE);

                if (animationEnabled) {
                    // Dynamic entrance: pop in from 0.8x scale with overshoot for that Apple feel.
                    pill.setScaleX(0.8f);
                    pill.setScaleY(0.8f);
                    pill.setAlpha(0f);
                    pill.setTranslationY(dp(MOTION_OFFSET_DP));
                    pill.animate()
                            .alpha(0.92f)
                            .translationY(0f)
                            .scaleX(1f).scaleY(1f)
                            .setDuration(300)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f))
                            .start();
                } else {
                    pill.setScaleX(1f);
                    pill.setScaleY(1f);
                    pill.setAlpha(0f);
                    pill.setTranslationY(0f);
                    pill.animate().alpha(0.92f).setDuration(150).start();
                }
            } else {
                pill.animate().alpha(0.92f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(180).start();
            }
            if (appearing) applyStyle(true);
        } else {
            pill.removeCallbacks(collapse);
            if (pill.getVisibility() == View.VISIBLE) {
                pill.animate().cancel();
                if (animationEnabled) {
                    // Dynamic exit: shrink away quickly (anticipate-style) to feel responsive.
                    pill.animate()
                            .alpha(0f)
                            .scaleX(0.7f)
                            .scaleY(0.7f)
                            .translationY(dp(MOTION_OFFSET_DP))
                            .setDuration(220)
                            .setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f))
                            .withEndAction(() -> {
                                pill.setVisibility(View.GONE);
                                pill.setScaleX(1f);
                                pill.setScaleY(1f);
                                pill.setTranslationY(0f);
                            })
                            .start();
                } else {
                    pill.animate()
                            .alpha(0f)
                            .setDuration(150)
                            .withEndAction(() -> {
                                pill.setVisibility(View.GONE);
                                pill.setTranslationY(0f);
                            })
                            .start();
                }
            }
        }
    }

    /** Updates the visual countdown fill. Externally driven (see NativeSpicyShellViewImpl)
     *  to match the actual auto-resume cooldown state. */
    void setProgress(float value) {

        if (config != null && config.get(Settings.FOLLOW_CHIP_PROGRESS) && !"Icon".equals(style)) {
            // Re-apply the progress background if it was replaced by setCollapsed()'s 
            // plain-button reset during a style or visibility transition.
            if (pill.getBackground() != progressDrawable) {
                pill.setBackground(progressDrawable);
            }
            progressDrawable.setProgress(value);
        }
    }

    /** Draws the auto-collapse progress inside the chip without rebuilding its background every frame. */
    void fadeProgress() {
        progressDrawable.fadeOut();
    }

    /** Quickly but smoothly returns the countdown fill to empty when the lyric list is touched. */
    void resetProgress() {
        if (config != null && config.get(Settings.FOLLOW_CHIP_PROGRESS) && !"Icon".equals(style)) {
            if (pill.getBackground() != progressDrawable) pill.setBackground(progressDrawable);
            progressDrawable.reset();
        }
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

    /** Re-reads {@link Settings#FOLLOW_CHIP_POSITION} and {@link Settings#FOLLOW_CHIP_STYLE}
     *  (call at mount and from the preference listener). */
    void onPreferenceChanged() {
        String nextPosition = config.get(Settings.FOLLOW_CHIP_POSITION);
        position = nextPosition == null ? Settings.FOLLOW_CHIP_POSITION.defaultValue : nextPosition;
        applyPosition();

        String nextStyle = config.get(Settings.FOLLOW_CHIP_STYLE);
        style = nextStyle == null ? Settings.FOLLOW_CHIP_STYLE.defaultValue : nextStyle;
        if (pill.getVisibility() == View.VISIBLE) applyStyle(false);
    }

    /** The setting's current value - queried by {@link LyricsSkipGapController} to decide whether
     *  it shares this chip's horizontal anchor and should stack above it. */
    String position() {
        return position;
    }

    private void applyPosition() {
        int gravity;
        int leftMargin;
        int rightMargin;
        if ("Left".equals(position)) {
            gravity = Gravity.BOTTOM | Gravity.START;
            leftMargin = dp(SIDE_MARGIN_DP);
            rightMargin = 0;
        } else if ("Center".equals(position)) {
            gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            leftMargin = 0;
            rightMargin = 0;
        } else {
            gravity = Gravity.BOTTOM | Gravity.END;
            leftMargin = 0;
            rightMargin = dp(SIDE_MARGIN_DP);
        }
        boolean changed = lp.gravity != gravity || lp.leftMargin != leftMargin
                || lp.rightMargin != rightMargin;
        lp.gravity = gravity;
        lp.leftMargin = leftMargin;
        lp.rightMargin = rightMargin;
        if (changed) pill.setLayoutParams(lp);
    }

    private void applyStyle(boolean appearing) {
        cancelWidthAnimation();
        pill.removeCallbacks(collapse);
        if ("Icon".equals(style)) {
            setCollapsed(true);
            return;
        }
        setCollapsed(false);
        if (!"Label".equals(style)) {
            // "Auto": only worth collapsing if it was ever expanded; a preference refresh mid-life
            // restarts the timer rather than snapping it shut.
            pill.postDelayed(collapse, appearing ? COLLAPSE_DELAY_MS : COLLAPSE_DELAY_MS / 2);
        }
    }

    private void setCollapsed(boolean value) {
        collapsed = value;
        label.setVisibility(value ? View.GONE : View.VISIBLE);
        label.setAlpha(value ? 0f : 1f);
        ViewGroup.LayoutParams pillLp = pill.getLayoutParams();
        if (pillLp != null) {
            pillLp.width = value ? dp(ICON_SIZE_DP) : ViewGroup.LayoutParams.WRAP_CONTENT;
            pill.setLayoutParams(pillLp);
        }
        pill.setPadding(value ? 0 : dp(12), 0, value ? 0 : dp(14), 0);
        pill.setBackground(value ? NativeIconButtons.createRoundButtonBackground() : pillBackground());
    }

    /** Animates the pill down to a plain circle - mirrors
     *  {@link LyricsSkipGapController#collapseToIcon()}. */
    private void collapseToIcon() {
        if (collapsed || pill.getVisibility() != View.VISIBLE) return;
        int startWidth = pill.getWidth();
        int endWidth = dp(ICON_SIZE_DP);
        if (startWidth <= endWidth) {
            setCollapsed(true);
            return;
        }
        ValueAnimator animator = ValueAnimator.ofInt(startWidth, endWidth);
        animator.setDuration(COLLAPSE_DURATION_MS);
        animator.addUpdateListener(a -> {
            int width = (Integer) a.getAnimatedValue();
            ViewGroup.LayoutParams pillLp = pill.getLayoutParams();
            if (pillLp == null) return;
            pillLp.width = width;
            pill.setLayoutParams(pillLp);
            float progress = (float) (startWidth - width) / (float) Math.max(1, startWidth - endWidth);
            label.setAlpha(Math.max(0f, 1f - progress * 1.6f));
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (pill.getVisibility() == View.VISIBLE) setCollapsed(true);
            }
        });
        widthAnimator = animator;
        animator.start();
    }

    private void cancelWidthAnimation() {
        if (widthAnimator != null) {
            widthAnimator.cancel();
            widthAnimator = null;
        }
    }

    /** Raises the chip above the bottom track-info readout (bottom mode) or restores it. */
    void setBottomMarginDp(int marginDp) {
        int target = dp(marginDp);
        if (lp.bottomMargin != target) {
            lp.bottomMargin = target;
            pill.setLayoutParams(lp);
        }
    }

    /** The real on-screen chip, for the layout editor's tap/outline overlay. */
    View view() {
        return pill;
    }

    /** Layout editor preview: shows the chip even with follow-state not actually away from the
     *  active line right now, so its position can still be edited. A no-op if real follow-state
     *  already has it showing - {@link #restoreAfterEditing()} must never hide that. */
    void showForEditing() {
        if (shown) return;
        editingForcedVisible = true;
        update(true);
    }

    /** Pairs with {@link #showForEditing()}: hides the chip again only if this controller was the
     *  one that forced it visible. */
    void restoreAfterEditing() {
        if (!editingForcedVisible) return;
        editingForcedVisible = false;
        update(false);
    }

    /** Hide the chip even if layout editor forced it visible - called when follow is manually resumed. */
    void forceHide() {
        editingForcedVisible = false;
        update(false);
    }

    private static GradientDrawable pillBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(dp(ICON_SIZE_DP) / 2f);
        background.setColor(Color.argb(48, 255, 255, 255));
        background.setStroke(dp(1), Color.argb(52, 255, 255, 255));
        return background;
    }
}
