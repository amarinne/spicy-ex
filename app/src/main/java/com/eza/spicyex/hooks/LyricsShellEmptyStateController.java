package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.safe;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.lyrics.LyricsSkeletonView;
import com.eza.spicyex.lyrics.LyricsTextFactory;
import com.eza.spicyex.ui.ActionIconDrawable;

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
        showLoading(lyricsScroll, lyricsColumn, message, 0);
    }

    /**
     * Ads carry no lyrics: a compact card with an "AD" badge in their place, saying what happens
     * to the ad's audio (muted / replaced with music / plays) and that lyrics come back after it.
     */
    /** Break position and time left, under the ad card's title; null while no ad card shows. */
    private TextView adProgress;

    void updateAdProgress(String text) {
        TextView view = adProgress;
        if (view == null || !view.isAttachedToWindow()) return;
        String value = text == null ? "" : text;
        if (!value.contentEquals(view.getText())) view.setText(value);
        int visibility = value.isEmpty() ? View.GONE : View.VISIBLE;
        if (view.getVisibility() != visibility) view.setVisibility(visibility);
    }

    void showAdState(ScrollView lyricsScroll, LinearLayout lyricsColumn) {
        stateToken++;
        lyricsColumn.removeAllViews();
        com.eza.spicyex.SettingsUiStrings strings = com.eza.spicyex.UiLanguage.strings(activity,
                config.get(Settings.UI_LANGUAGE));

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setAlpha(0f);
        card.setTranslationY(dp(12));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0x1A000000);
        cardBg.setCornerRadius(dp(20));
        cardBg.setStroke(dp(1), 0x26FFFFFF);
        card.setBackground(cardBg);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));

        TextView badge = textFactory.createText(activity, "AD", 13, Color.rgb(20, 20, 24),
                textFactory.resolveTypeface(true));
        badge.setGravity(Gravity.CENTER);
        badge.setLetterSpacing(0.12f);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.rgb(255, 205, 80));
        badgeBg.setCornerRadius(dp(8));
        badge.setBackground(badgeBg);
        card.addView(badge, new LinearLayout.LayoutParams(dp(46), dp(26)));

        TextView label = textFactory.createText(activity,
                strings.get("lyrics_ad_title", "Advertisement"), 20, Color.WHITE,
                textFactory.resolveTypeface(true));
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(12), 0, 0);
        card.addView(label);

        adProgress = textFactory.createText(activity, "", 14, Color.rgb(220, 220, 228),
                textFactory.resolveTypeface(true));
        adProgress.setGravity(Gravity.CENTER);
        adProgress.setPadding(0, dp(6), 0, 0);
        adProgress.setVisibility(View.GONE);
        card.addView(adProgress);

        String mode = config.get(Settings.AD_MODE);
        boolean muted = Settings.AD_MODE_MUTE.equals(mode);
        boolean music = Settings.AD_MODE_MUSIC.equals(mode);
        LinearLayout statusRow = new LinearLayout(activity);
        statusRow.setGravity(Gravity.CENTER);
        if (muted) {
            ImageView muteIcon = new ImageView(activity);
            muteIcon.setImageDrawable(new ActionIconDrawable(
                    ActionIconDrawable.Kind.VOLUME_OFF, Color.rgb(204, 204, 214),
                    activity.getResources().getDisplayMetrics().density));
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(16), dp(16));
            iconLp.topMargin = dp(8);
            iconLp.rightMargin = dp(6);
            statusRow.addView(muteIcon, iconLp);
        }
        String hintText = muted ? strings.get("lyrics_ad_muted", "Ad audio muted · lyrics resume after the ad")
                : music ? strings.get("lyrics_ad_music", "Playing music instead · lyrics resume after the ad")
                : strings.get("lyrics_ad_resume", "Lyrics resume after the ad");
        TextView hint = textFactory.createText(activity, hintText, 13, Color.rgb(190, 190, 200),
                textFactory.resolveTypeface(false));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, 0);
        statusRow.addView(hint);
        card.addView(statusRow);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(96);
        cardLp.leftMargin = dp(24);
        cardLp.rightMargin = dp(24);
        lyricsColumn.addView(card, cardLp);
        card.animate().alpha(1f).translationY(0f).setDuration(220)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();

        // A slow breath on the badge; runs only while the card is on screen.
        ValueAnimator pulse = ValueAnimator.ofFloat(0.72f, 1f);
        pulse.setDuration(1300);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse.addUpdateListener(a -> badge.setAlpha((float) a.getAnimatedValue()));
        badge.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                pulse.start();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                pulse.cancel();
            }
        });
        if (badge.isAttachedToWindow()) pulse.start();
        lyricsScroll.post(() -> lyricsScroll.scrollTo(0, 0));
    }

    void showLoading(ScrollView lyricsScroll, LinearLayout lyricsColumn, String message, int horizontalInsetPx) {
        stateToken++;
        lyricsColumn.removeAllViews();
        if (config.get(Settings.SHOW_SKELETON)) {
            LyricsSkeletonView skeleton = new LyricsSkeletonView(activity);
            int horizontalPad = horizontalInsetPx > 0 ? horizontalInsetPx : dp(18);
            skeleton.setHorizontalPaddingPx(horizontalPad);
            LinearLayout.LayoutParams skeletonLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lyricsColumn.addView(skeleton, skeletonLp);
            // Height may be 0 if called before layout; defer margin calculation to
            // the first layout pass via a posted runnable (never immediate) so the
            // offset is always correct.
            alignLoadingStart(lyricsScroll, lyricsColumn, skeleton, horizontalPad);
            return;
        }
        TextView loading = textFactory.createText(
                activity,
                message,
                22,
                Color.rgb(179, 179, 179),
                textFactory.resolveTypeface(true));
        loading.setGravity(Gravity.CENTER);
        int pad = horizontalInsetPx > 0 ? horizontalInsetPx : dp(16);
        loading.setPadding(pad, dp(100), pad, dp(16));
        lyricsColumn.addView(loading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // Defer scroll reset to the layout pass so lyricsScroll.getHeight() is valid.
        lyricsScroll.post(() -> lyricsScroll.scrollTo(0, 0));
    }

    private void alignLoadingStart(
            ScrollView lyricsScroll,
            LinearLayout lyricsColumn,
            View loadingView
    ) {
        alignLoadingStart(lyricsScroll, lyricsColumn, loadingView, 0);
    }

    private void alignLoadingStart(
            ScrollView lyricsScroll,
            LinearLayout lyricsColumn,
            View loadingView,
            int horizontalMarginPx
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
            }
            int hMargin = horizontalMarginPx > 0 ? horizontalMarginPx : dp(12);
            if (params.leftMargin != hMargin) params.leftMargin = hMargin;
            if (params.rightMargin != hMargin) params.rightMargin = hMargin;
            loadingView.setLayoutParams(params);
            lyricsScroll.scrollTo(0, 0);
        };
        // Height may be 0 on first call (pre-layout). Post instead of running immediately
        // so lyricsScroll.getHeight() always returns a valid value after layout.
        lyricsScroll.post(align);
    }

    static int loadingTopMargin(int viewportHeightPx, int paddingTopPx) {
        if (viewportHeightPx <= 0) return 0;
        // The loading state should sit around the viewport midpoint; if the lyric area has
        // already shifted past halfway, keep the start flush with the top instead of overshooting.
        return paddingTopPx >= viewportHeightPx / 2 ? 0 : dp(56);
    }

    /** An instrumental track: a quiet, centred note and label in place of "No lyrics found". */
    void showInstrumental(LinearLayout lyricsColumn, java.util.function.Supplier<float[]> spectrum) {
        ++stateToken;
        lyricsColumn.removeAllViews();
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView note = textFactory.createText(activity, "\u266B", 56, Color.WHITE,
                textFactory.resolveTypeface(true));
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(16), dp(72), dp(16), dp(4));
        box.addView(note, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        String label = com.eza.spicyex.UiLanguage.strings(activity,
                config.get(com.eza.spicyex.Settings.UI_LANGUAGE))
                .get("lyrics_instrumental", "Instrumental");
        TextView title = textFactory.createText(activity, label, 22, Color.WHITE,
                textFactory.resolveTypeface(true));
        title.setGravity(Gravity.CENTER);
        title.setAlpha(0.85f);
        box.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lyricsColumn.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // A slow breath on the note, so the screen reads as music playing rather than an error.
        android.animation.ObjectAnimator breathe = android.animation.ObjectAnimator.ofFloat(
                note, View.ALPHA, 0.55f, 1f);
        breathe.setDuration(1600L);
        breathe.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        breathe.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        breathe.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
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
