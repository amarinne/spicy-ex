package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeIconButtons.createRoundIconButton;
import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import com.eza.spicyex.ui.ActionIconDrawable;

/** Owns the floating "skip intro" / "next track" icon button shown during the instrumental
 * stretches before the first synced line and after the last one. Sits just above the
 * "jump to current line" button (see LyricsJumpToCurrentController) so both live in one spot. */
final class LyricsSkipController {
    private final ImageButton button;
    private Runnable onTap;

    private LyricsSkipController(ImageButton button) {
        this.button = button;
    }

    static LyricsSkipController attach(Activity activity, FrameLayout parent) {
        float density = activity.getResources().getDisplayMetrics().density;
        ImageButton button = createRoundIconButton(activity,
                new ActionIconDrawable(ActionIconDrawable.Kind.CHEVRONS_RIGHT,
                        Color.rgb(232, 232, 238), density),
                "Skip", 40, 10);
        button.setAlpha(0f);
        button.setVisibility(View.GONE);
        button.setElevation(dp(8));

        // Stacked directly above LyricsJumpToCurrentController's 44dp circle (margin 14/24) with
        // a small gap, so both floating affordances read as one group instead of two unrelated
        // widgets fighting for the same bottom-right corner.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(40), dp(40), Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, dp(16), dp(24 + 44 + 10));
        parent.addView(button, lp);
        LyricsSkipController controller = new LyricsSkipController(button);
        button.setOnClickListener(v -> {
            if (controller.onTap != null) controller.onTap.run();
            controller.hide();
        });
        return controller;
    }

    void show(String labelText, Runnable onTap) {
        // labelText ("Skip Intro" / "Next Track") no longer renders as text - the icon alone
        // covers both cases identically (fast-forward past whatever's currently silent/instrumental).
        this.onTap = onTap;
        if (button.getVisibility() != View.VISIBLE) {
            button.animate().cancel();
            button.setVisibility(View.VISIBLE);
            button.animate().alpha(0.92f).setDuration(220L).start();
        }
    }

    void hide() {
        if (button.getVisibility() == View.GONE) return;
        button.animate().cancel();
        button.animate().alpha(0f).setDuration(140L)
                .withEndAction(() -> button.setVisibility(View.GONE)).start();
    }
}
