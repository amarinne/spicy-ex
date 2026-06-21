package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.eza.spicyex.lyrics.LyricsTextFactory;

/** Owns the floating "jump back to active lyric" affordance. */
final class LyricsJumpToCurrentController {
    private final TextView button;

    private LyricsJumpToCurrentController(TextView button) {
        this.button = button;
    }

    static LyricsJumpToCurrentController attach(
            Activity activity,
            FrameLayout parent,
            LyricsTextFactory textFactory,
            Runnable onClick
    ) {
        TextView view = textFactory.createChip(activity, "↓");
        view.setTextSize(13);
        view.setAlpha(0f);
        view.setVisibility(View.GONE);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(Color.argb(210, 36, 36, 36));
        view.setBackground(bg);
        view.setElevation(dp(8));
        view.setOnClickListener(v -> {
            if (onClick != null) onClick.run();
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(44),
                Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, dp(14), dp(24));
        parent.addView(view, lp);
        return new LyricsJumpToCurrentController(view);
    }

    void update(boolean show) {
        int targetVisibility = show ? View.VISIBLE : View.GONE;
        if (button.getVisibility() != targetVisibility) {
            button.setVisibility(targetVisibility);
        }
        button.setAlpha(show ? 0.92f : 0f);
    }
}
