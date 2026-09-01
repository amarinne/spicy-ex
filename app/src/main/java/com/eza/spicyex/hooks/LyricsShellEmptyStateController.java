package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.safe;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.lyrics.LyricsSkeletonView;
import com.eza.spicyex.lyrics.LyricsTextFactory;

/** Builds transient loading and error rows for the fullscreen lyric surface. */
final class LyricsShellEmptyStateController {
    private static final long ERROR_DISPLAY_MS = 10_000L;
    private final Activity activity;
    private final SpotifyPlusConfig config;
    private final LyricsTextFactory textFactory;
    private int stateToken;

    LyricsShellEmptyStateController(
            Activity activity,
            SpotifyPlusConfig config,
            LyricsTextFactory textFactory
    ) {
        this.activity = activity;
        this.config = config;
        this.textFactory = textFactory;
    }

    void showLoading(ScrollView lyricsScroll, LinearLayout lyricsColumn, String message) {
        stateToken++;
        lyricsColumn.removeAllViews();
        if (config.get(Settings.SHOW_SKELETON)) {
            LyricsSkeletonView skeleton = new LyricsSkeletonView(activity);
            LinearLayout.LayoutParams skeletonLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            skeletonLp.topMargin = loadingTopMargin(
                    lyricsScroll.getHeight(), lyricsScroll.getPaddingTop());
            lyricsColumn.addView(skeleton, skeletonLp);
            alignLoadingStart(lyricsScroll, lyricsColumn, skeleton);
            return;
        }
        TextView loading = textFactory.createText(
                activity,
                message,
                22,
                Color.rgb(179, 179, 179),
                textFactory.resolveTypeface(true));
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(dp(16), dp(100), dp(16), dp(16));
        lyricsColumn.addView(loading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void alignLoadingStart(
            ScrollView lyricsScroll,
            LinearLayout lyricsColumn,
            View loadingView
    ) {
        Runnable align = () -> {
            if (loadingView.getParent() != lyricsColumn) return;
            ViewGroup.LayoutParams rawParams = loadingView.getLayoutParams();
            if (!(rawParams instanceof LinearLayout.LayoutParams)) return;
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) rawParams;
            int topMargin = loadingTopMargin(
                    lyricsScroll.getHeight(), lyricsScroll.getPaddingTop());
            if (params.topMargin != topMargin) {
                params.topMargin = topMargin;
                loadingView.setLayoutParams(params);
            }
            // A song change can leave the prior document's scroll offset in place. Reset both now
            // and after layout, when ScrollView has recalculated the shorter loading content range.
            lyricsScroll.scrollTo(0, 0);
        };
        align.run();
        lyricsScroll.post(align);
    }

    static int loadingTopMargin(int viewportHeightPx, int paddingTopPx) {
        if (viewportHeightPx <= 0) return 0;
        return Math.max(0, viewportHeightPx / 2 - Math.max(0, paddingTopPx));
    }

    void showError(LinearLayout lyricsColumn, String error) {
        final int token = ++stateToken;
        lyricsColumn.removeAllViews();
        LinearLayout errorBox = new LinearLayout(activity);
        errorBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = textFactory.createText(
                activity,
                "No lyrics found",
                24,
                Color.WHITE,
                textFactory.resolveTypeface(true));
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(16), dp(80), dp(16), dp(8));
        errorBox.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView message = textFactory.createText(
                activity,
                safe(error),
                14,
                Color.rgb(179, 179, 179),
                textFactory.resolveTypeface(false));
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(16), dp(4), dp(16), dp(16));
        errorBox.addView(message, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        lyricsColumn.addView(errorBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lyricsColumn.postDelayed(() -> {
            if (token != stateToken || errorBox.getParent() != lyricsColumn) return;
            errorBox.animate().alpha(0f).setDuration(350L).withEndAction(() -> {
                if (token != stateToken || errorBox.getParent() != lyricsColumn) return;
                lyricsColumn.removeAllViews();
                showInterludeIndicator(lyricsColumn);
            }).start();
        }, ERROR_DISPLAY_MS);
    }

    private void showInterludeIndicator(LinearLayout lyricsColumn) {
        boolean noteMode = "note".equals(config.get(Settings.INTERLUDE_ICON));
        TextView indicator = textFactory.createText(
                activity, noteMode ? "♪" : "•  •  •", noteMode ? 38 : 44,
                Color.WHITE, textFactory.resolveTypeface(true));
        indicator.setGravity(Gravity.START);
        indicator.setAlpha(0f);
        indicator.setPadding(dp(16), dp(80), dp(16), dp(16));
        lyricsColumn.addView(indicator, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        indicator.animate().alpha(1f).setDuration(350L).start();
    }
}
