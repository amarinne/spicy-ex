package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.ui.ActionIconDrawable;

import java.util.function.Supplier;

/**
 * Owns the floating "skip intro" / "skip" / "next track" affordance shown during the gaps before
 * the first synced line, between sections, and after the last one. Stacks just above the
 * "jump to current line" button (see LyricsJumpToCurrentController) when both share the same
 * horizontal anchor, so the pair reads as one group; otherwise each sits at its own baseline.
 *
 * <p>Opens as a labelled pill, because an unexplained double-chevron in the corner of a lyrics
 * screen does not tell anyone what it will do. Left alone it then collapses to the icon so it
 * stops competing with the lyrics for attention - the label has done its job by then. Which of
 * those two states it settles in is {@link Settings#SKIP_CHIP_STYLE}: a fixed icon, a fixed
 * label, or the collapse. Which corner it floats at is {@link Settings#SKIP_CHIP_POSITION};
 * the jump-to-current chip's own corner is {@link Settings#FOLLOW_CHIP_POSITION}.
 */
final class LyricsSkipGapController {
    /** How long the label stays before the pill collapses to its icon, in "Auto". */
    private static final long COLLAPSE_DELAY_MS = 3200L;
    private static final long COLLAPSE_DURATION_MS = 260L;
    private static final long HIDE_DURATION_MS = 240L;
    private static final int ICON_SIZE_DP = 40;
    private static final int TEXT_COLOR = Color.rgb(232, 232, 238);
    private static final int SIDE_MARGIN_DP = 16;
    /** Vertical distance between the two stacked chips: 40dp chip + 8dp gap. */
    private static final int STACK_OFFSET_DP = 48;

    private final SpotifyPlusConfig config;
    private final LinearLayout pill;
    private final TextView label;
    private final FrameLayout.LayoutParams lp;
    private final Runnable onTap;
    private final Runnable collapse = this::collapseToIcon;
    /** The jump-to-current chip's current horizontal anchor - stacking only applies when this
     *  matches {@link #position}. */
    private final Supplier<String> jumpPositionSupplier;

    private String style = Settings.SKIP_CHIP_STYLE.defaultValue;
    private String position = Settings.SKIP_CHIP_POSITION.defaultValue;
    private String shownLabel = "";
    private boolean collapsed;
    // Tracked separately from the view's own visibility, which lags: hide() fades out over 140ms
    // and only then sets GONE, so a show() landing inside that window used to read the pill as
    // still visible, decline to reset it, and leave a half-faded collapsed circle on screen.
    private boolean visible;
    private ValueAnimator widthAnimator;
    private int bottomMarginDp = 24 + STACK_OFFSET_DP;
    /** Set only by {@link #showForEditing()}, so {@link #restoreAfterEditing()} never hides a
     *  chip a real skip gap put up on its own. */
    private boolean editingForcedVisible;

    private LyricsSkipGapController(SpotifyPlusConfig config, LinearLayout pill, TextView label,
            FrameLayout.LayoutParams lp, Runnable onTap, Supplier<String> jumpPositionSupplier) {
        this.config = config;
        this.pill = pill;
        this.label = label;
        this.lp = lp;
        this.onTap = onTap;
        this.jumpPositionSupplier = jumpPositionSupplier;
    }

    static LyricsSkipGapController attach(
            Activity activity,
            FrameLayout parent,
            SpotifyPlusConfig config,
            Supplier<String> jumpPositionSupplier,
            Runnable onClick
    ) {
        float density = activity.getResources().getDisplayMetrics().density;

        LinearLayout pill = new LinearLayout(activity);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        // CENTER, not CENTER_VERTICAL: collapsed, the pill is a 40dp box holding a 20dp icon,
        // and vertical-only centering leaves that icon pinned to the left edge.
        pill.setGravity(Gravity.CENTER);
        pill.setBackground(pillBackground());
        pill.setPadding(dp(10), 0, dp(10), 0);
        pill.setClickable(true);
        pill.setFocusable(true);
        pill.setAlpha(0f);
        pill.setVisibility(View.GONE);
        pill.setElevation(dp(8));
        NativeIconButtons.applyPressScale(pill);

        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(new ActionIconDrawable(
                ActionIconDrawable.Kind.CHEVRONS_RIGHT, TEXT_COLOR, density));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        pill.addView(icon, iconLp);

        TextView label = new TextView(activity);
        label.setTextColor(TEXT_COLOR);
        label.setTextSize(13f);
        label.setSingleLine(true);
        label.setIncludeFontPadding(false);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = dp(7);
        pill.addView(label, labelLp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(ICON_SIZE_DP), Gravity.BOTTOM | Gravity.END);
        parent.addView(pill, lp);

        LyricsSkipGapController controller = new LyricsSkipGapController(
                config, pill, label, lp, onClick, jumpPositionSupplier);
        pill.setOnClickListener(v -> {
            if (controller.onTap != null) controller.onTap.run();
            controller.hide();
        });
        controller.onPreferenceChanged();
        return controller;
    }

    /** Re-reads {@link Settings#SKIP_CHIP_STYLE} and {@link Settings#SKIP_CHIP_POSITION} (call
     *  at mount and from the preference listener). */
    void onPreferenceChanged() {
        String nextStyle = config.get(Settings.SKIP_CHIP_STYLE);
        style = nextStyle == null ? Settings.SKIP_CHIP_STYLE.defaultValue : nextStyle;
        if (pill.getVisibility() == View.VISIBLE) applyStyle(shownLabel, false);

        String nextPosition = config.get(Settings.SKIP_CHIP_POSITION);
        position = nextPosition == null ? Settings.SKIP_CHIP_POSITION.defaultValue : nextPosition;
        applyPosition();

        // Ensure bottom margins are re-calculated based on the new position constraint.
        // We use the baseline bottom margin value derived from its current state.
        int jumpMargin = bottomMarginDp - (isStacked() ? STACK_OFFSET_DP : 0);
        setBottomMarginDp(Math.max(24, jumpMargin));
    }

    /** True when this chip and the jump-to-current chip share the same horizontal anchor, so they
     *  stack vertically instead of each sitting at its own baseline. */
    private boolean isStacked() {
        String jumpPosition = jumpPositionSupplier == null ? null : jumpPositionSupplier.get();
        return position.equals(jumpPosition);
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

    /** Keeps the stack above the bottom track-info readout; mirrors the jump chip margin. */
    void setBottomMarginDp(int jumpMarginDp) {
        // Only stack vertically when both chips share the same horizontal anchor. Otherwise the
        // skip chip stays pinned to its own baseline rather than floating in space.
        int target = isStacked() ? jumpMarginDp + STACK_OFFSET_DP : jumpMarginDp;
        if (target == bottomMarginDp && lp.bottomMargin == dp(target)) return;
        bottomMarginDp = target;
        lp.bottomMargin = dp(target);
        pill.setLayoutParams(lp);
    }

    /** The real on-screen chip, for the layout editor's tap/outline overlay. */
    View view() {
        return pill;
    }

    /** Layout editor preview: shows the chip with a placeholder label so its position can be
     *  edited even with no real skip gap active right now. A no-op if a real gap already has it
     *  showing - {@link #restoreAfterEditing()} must never hide that. */
    void showForEditing() {
        if (visible) return;
        editingForcedVisible = true;
        show(com.eza.spicyex.lyrics.SkipGapPolicy.defaultLabel(
                com.eza.spicyex.lyrics.SkipGapPolicy.GapKind.LEADING));
        pill.removeCallbacks(collapse);
    }

    /** Pairs with {@link #showForEditing()}: hides the chip again only if this controller was
     *  the one that forced it visible. */
    void restoreAfterEditing() {
        if (!editingForcedVisible) return;
        editingForcedVisible = false;
        hide();
    }

    /** Shows the chip with the given label, or updates the label of an already-shown chip
     *  without replaying the appear animation. */
    void show(String labelText) {
        String text = labelText == null ? "" : labelText;
        boolean appearing = !visible;
        if (!appearing && text.equals(shownLabel)) return;

        visible = true;
        shownLabel = text;
        if (appearing) {
            pill.animate().cancel();
            pill.setVisibility(View.VISIBLE);
            pill.setAlpha(0f);
            pill.setScaleX(0.82f);
            pill.setScaleY(0.82f);
            pill.animate().alpha(0.92f).scaleX(1f).scaleY(1f).setDuration(220L).start();
        }
        applyStyle(text, appearing);
    }

    private void applyStyle(String text, boolean appearing) {
        cancelWidthAnimation();
        pill.removeCallbacks(collapse);
        label.setText(text);

        if ("Icon".equals(style)) {
            setCollapsed(true);
            return;
        }
        setCollapsed(false);
        if (!"Label".equals(style)) {
            // "Auto": only worth collapsing if it was ever expanded; re-showing the same label
            // mid-life restarts the timer rather than snapping it shut.
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
        pill.setPadding(value ? 0 : dp(10), 0, value ? 0 : dp(10), 0);
        pill.setBackground(value ? NativeIconButtons.createRoundButtonBackground() : pillBackground());
    }

    /**
     * Animates the pill down to a plain circle. Width is animated explicitly rather than left to
     * a layout transition, because the pill lives in a FrameLayout that nothing else is
     * animating - a WRAP_CONTENT change there just snaps.
     */
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

    void hide() {
        pill.removeCallbacks(collapse);
        if (!visible) return;
        visible = false;
        shownLabel = "";
        cancelWidthAnimation();
        pill.animate().cancel();
        // No geometry reset here: forcing setCollapsed(false) on an already-collapsed icon right
        // as it starts hiding made it visibly snap back to full label width for a frame before
        // shrinking away - an unwanted "expand, then vanish" instead of a clean fade from
        // whatever state it was already in. The next show() resets geometry itself via
        // applyStyle() before the entrance fade-in even starts (while alpha is still 0), so
        // nothing here needs to pre-empt that.
        // Shrinks slightly as it goes rather than only fading. A control that just dims out reads
        // as the screen dropping frames; one that pulls back reads as the control retiring itself.
        pill.animate().alpha(0f).scaleX(0.82f).scaleY(0.82f)
                .setDuration(HIDE_DURATION_MS)
                .withEndAction(() -> {
                    if (!visible) {
                        pill.setVisibility(View.GONE);
                        pill.setScaleX(1f);
                        pill.setScaleY(1f);
                    }
                }).start();
    }

    private void cancelWidthAnimation() {
        if (widthAnimator != null) {
            widthAnimator.cancel();
            widthAnimator = null;
        }
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
