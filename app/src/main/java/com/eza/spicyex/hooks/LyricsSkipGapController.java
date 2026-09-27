package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeIconButtons.createRoundIconButton;
import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import com.eza.spicyex.ui.ActionIconDrawable;
import com.eza.spicyex.ui.Motion;

/** Owns the floating "skip intro/outro gap" affordance, stacked above jump-to-current. */
final class LyricsSkipGapController {
    /** Vertical distance between the two stacked chips: 44dp chip + 8dp gap. */
    static final int STACK_OFFSET_DP = 52;

    private final ImageButton button;
    private boolean shown;

    private LyricsSkipGapController(ImageButton button) {
        this.button = button;
    }

    static LyricsSkipGapController attach(
            Activity activity,
            FrameLayout parent,
            Runnable onClick
    ) {
        float density = activity.getResources().getDisplayMetrics().density;
        ImageButton view = createRoundIconButton(activity,
                new ActionIconDrawable(ActionIconDrawable.Kind.CHEVRONS_RIGHT,
                        Color.rgb(232, 232, 238), density),
                "Skip intro/outro", 44, 11);
        view.setAlpha(0f);
        view.setVisibility(View.GONE);
        LyricsSkipGapController controller = new LyricsSkipGapController(view);
        view.setOnClickListener(v -> {
            controller.update(false);
            if (onClick != null) onClick.run();
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(44),
                dp(44),
                Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, dp(14), dp(24 + STACK_OFFSET_DP));
        parent.addView(view, lp);
        return controller;
    }

    void update(boolean show) {
        if (show == shown) return;
        shown = show;
        button.setEnabled(show);

        button.animate().cancel();
        if (!Motion.animationsEnabled()) {
            button.setVisibility(show ? View.VISIBLE : View.GONE);
            button.setAlpha(show ? 0.92f : 0f);
            button.setScaleX(1f);
            button.setScaleY(1f);
            return;
        }

        if (show) {
            if (button.getVisibility() != View.VISIBLE) {
                button.setAlpha(0f);
                button.setScaleX(0.9f);
                button.setScaleY(0.9f);
            }
            button.setVisibility(View.VISIBLE);
            button.animate()
                    .alpha(0.92f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(Motion.dur(Motion.REVEAL))
                    .withEndAction(null)
                    .start();
        } else {
            button.animate()
                    .alpha(0f)
                    .setDuration(Motion.dur(Motion.EXIT))
                    .withEndAction(() -> {
                        if (!shown) {
                            button.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
    }

    /** Keeps the stack above the bottom track-info readout; mirrors the jump chip margin. */
    void setBottomMarginDp(int jumpMarginDp) {
        ViewGroup.LayoutParams lp = button.getLayoutParams();
        if (!(lp instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
        int target = dp(jumpMarginDp + STACK_OFFSET_DP);
        if (flp.bottomMargin != target) {
            flp.bottomMargin = target;
            button.setLayoutParams(flp);
        }
    }
}
