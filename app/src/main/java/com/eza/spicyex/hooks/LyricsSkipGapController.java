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

/** Owns the floating "skip intro/outro gap" affordance, stacked above jump-to-current. */
final class LyricsSkipGapController {
    /** Vertical distance between the two stacked chips: 44dp chip + 8dp gap. */
    static final int STACK_OFFSET_DP = 52;

    private final ImageButton button;

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
        view.setOnClickListener(v -> {
            if (onClick != null) onClick.run();
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(44),
                dp(44),
                Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, dp(14), dp(24 + STACK_OFFSET_DP));
        parent.addView(view, lp);
        return new LyricsSkipGapController(view);
    }

    void update(boolean show) {
        int targetVisibility = show ? View.VISIBLE : View.GONE;
        if (button.getVisibility() != targetVisibility) {
            button.setVisibility(targetVisibility);
        }
        button.setAlpha(show ? 0.92f : 0f);
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
