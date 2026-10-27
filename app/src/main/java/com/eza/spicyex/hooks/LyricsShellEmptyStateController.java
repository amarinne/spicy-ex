package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.safe;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.lyrics.LyricsSkeletonView;
import com.eza.spicyex.lyrics.LyricsTextFactory;
import com.eza.spicyex.lyrics.providers.LyricsErrorSentences;
import com.eza.spicyex.ui.SettingsUiStrings;
import com.eza.spicyex.ui.UiLanguage;
import com.eza.spicyex.xposed.XpLog;

/** Builds transient loading and error rows for the fullscreen lyric surface. */
final class LyricsShellEmptyStateController {
    private final Activity activity;
    private final SpotifyPlusConfig config;
    private final LyricsTextFactory textFactory;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final long NO_MATCH_NOTE_DELAY_MS = 15_000L;

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
        showLoading(lyricsScroll, lyricsColumn, message, 0.5f, 0);
    }

    /**
     * @param anchorFraction where the current line rests (the focus position), so the skeleton
     *                       starts where the lyrics will rather than always at the middle
     * @param sideInsetPx    the side margin the lyric rows keep
     */
    void showLoading(ScrollView lyricsScroll, LinearLayout lyricsColumn, String message,
                     float anchorFraction, int sideInsetPx) {
        cancelNoMatchNote();
        lyricsColumn.removeAllViews();
        if (config.get(Settings.SHOW_SKELETON)) {
            LyricsSkeletonView skeleton = new LyricsSkeletonView(activity);
            skeleton.setHorizontalInsetPx(sideInsetPx);
            LinearLayout.LayoutParams skeletonLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            skeletonLp.topMargin = loadingTopMargin(
                    lyricsScroll.getHeight(), lyricsScroll.getPaddingTop(), anchorFraction);
            lyricsColumn.addView(skeleton, skeletonLp);
            alignLoadingStart(lyricsScroll, lyricsColumn, skeleton, anchorFraction);
            skeleton.setAlpha(1f);
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
        loading.setAlpha(1f);
    }

    private void alignLoadingStart(
            ScrollView lyricsScroll,
            LinearLayout lyricsColumn,
            View loadingView,
            float anchorFraction
    ) {
        Runnable align = () -> {
            if (loadingView.getParent() != lyricsColumn) return;
            ViewGroup.LayoutParams rawParams = loadingView.getLayoutParams();
            if (!(rawParams instanceof LinearLayout.LayoutParams)) return;
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) rawParams;
            int topMargin = loadingTopMargin(
                    lyricsScroll.getHeight(), lyricsScroll.getPaddingTop(), anchorFraction);
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
        return loadingTopMargin(viewportHeightPx, paddingTopPx, 0.5f);
    }

    /** Top margin that starts the loading placeholder at the focus position. */
    static int loadingTopMargin(int viewportHeightPx, int paddingTopPx, float anchorFraction) {
        if (viewportHeightPx <= 0) return 0;
        float fraction = Float.isNaN(anchorFraction) ? 0.5f
                : Math.max(0f, Math.min(1f, anchorFraction));
        return Math.max(0, Math.round(viewportHeightPx * fraction) - Math.max(0, paddingTopPx));
    }

    /**
     * An instrumental track: a quiet note and its label in place of "No lyrics found", so a
     * deliberate no-lyrics track does not read as a lookup failure.
     */
    void showInstrumental(LinearLayout lyricsColumn) {
        cancelNoMatchNote();
        lyricsColumn.removeAllViews();
        SettingsUiStrings strings = UiLanguage.strings(activity,
                config.get(Settings.UI_LANGUAGE));

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView note = textFactory.createText(activity, "♫", 56, Color.WHITE,
                textFactory.resolveTypeface(true));
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(16), dp(72), dp(16), dp(4));
        box.addView(note, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = textFactory.createText(activity,
                strings.get("lyrics_instrumental", "Instrumental"), 22, Color.WHITE,
                textFactory.resolveTypeface(true));
        title.setGravity(Gravity.CENTER);
        title.setAlpha(0.85f);
        box.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lyricsColumn.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // A slow breath on the note, so the screen reads as music playing rather than an error.
        ValueAnimator breathe = ValueAnimator.ofFloat(0.55f, 1f);
        breathe.setDuration(1600);
        breathe.setRepeatCount(ValueAnimator.INFINITE);
        breathe.setRepeatMode(ValueAnimator.REVERSE);
        breathe.setInterpolator(new AccelerateDecelerateInterpolator());
        breathe.addUpdateListener(a -> note.setAlpha((float) a.getAnimatedValue()));
        note.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                breathe.start();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                breathe.cancel();
            }
        });
        if (note.isAttachedToWindow()) breathe.start();
    }

    /**
     * A terminal fetch failure: one or two short lines, what happened and, when useful, what to
     * do next. The box stays until the state changes (new track, retry, lyrics arrive). The raw
     * verdict chain is logged and reduced to a diagnostics token, never printed on the surface.
     */
    void showError(LinearLayout lyricsColumn, String error) {
        cancelNoMatchNote();
        lyricsColumn.removeAllViews();
        SettingsUiStrings strings = UiLanguage.strings(activity,
                config.get(Settings.UI_LANGUAGE));
        LyricsErrorSentences.Report report = LyricsErrorSentences.describe(error);
        XpLog.log("[SpotifyPlusDiagnostics] lyrics_fetch error_surface verdict="
                + report.verdict.token() + " chain=" + safe(error));
        Diagnostics.event("lyrics_fetch", "error_surface",
                Diagnostics.context("result", report.verdict.token(),
                        "reason", LyricsErrorSentences.diagnosticToken(error)));

        LinearLayout errorBox = new LinearLayout(activity);
        errorBox.setOrientation(LinearLayout.VERTICAL);
        addErrorLine(errorBox, strings, report.title, 24, Color.WHITE, true, dp(80), dp(8));
        addErrorLine(errorBox, strings, report.detail, 14, Color.rgb(179, 179, 179), false,
                dp(4), dp(10));
        addErrorLine(errorBox, strings, report.action, 14, Color.rgb(179, 179, 179), false,
                0, dp(16));
        lyricsColumn.addView(errorBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        errorBox.setAlpha(1f);
        if (report.verdict == LyricsErrorSentences.Verdict.NO_MATCH) {
            // A confirmed miss is a normal song state. Keep the first report concise, then let it
            // settle into the same quiet music-note presentation used for instrumental tracks.
            main.postDelayed(() -> {
                if (errorBox.getParent() == lyricsColumn) showInstrumental(lyricsColumn);
            }, NO_MATCH_NOTE_DELAY_MS);
        }
    }

    private void cancelNoMatchNote() {
        main.removeCallbacksAndMessages(null);
    }

    /** Adds one sentence line, or nothing when the verdict omits that line. */
    private void addErrorLine(
            LinearLayout errorBox,
            SettingsUiStrings strings,
            LyricsErrorSentences.Line line,
            int sizeSp,
            int color,
            boolean bold,
            int topPaddingPx,
            int bottomPaddingPx
    ) {
        if (line == null) return;
        TextView view = textFactory.createText(
                activity,
                strings.get(line.resource, line.fallback),
                sizeSp,
                color,
                textFactory.resolveTypeface(bold));
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(16), topPaddingPx, dp(16), bottomPaddingPx);
        errorBox.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }
}
