package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeIconButtons.createRoundIconButton;
import static com.eza.spicyex.hooks.NativeIconButtons.applyPressScale;
import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.sideSystemPadding;
import static com.eza.spicyex.hooks.NativeLyricsUtils.topSystemPadding;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.R;
import com.eza.spicyex.lyrics.ChipSpinnerDrawable;
import com.eza.spicyex.lyrics.GlyphIconDrawable;
import com.eza.spicyex.lyrics.LyricsTextFactory;
import com.eza.spicyex.ui.ActionIconDrawable;

/** Builds the fullscreen shell's top chrome row. */
final class LyricsShellChromeController {
    private LyricsShellChromeController() {
    }

    static ChromeViews attach(
            Activity activity,
            FrameLayout parent,
            LyricsTextFactory textFactory,
            GlyphIconDrawable romanGlyph,
            ChipSpinnerDrawable romanSpinner,
            ChipSpinnerDrawable translationSpinner,
            int chromeButtonDp,
            boolean landscape,
            Runnable onBack,
            Runnable onPlayPauseToggle,
            Runnable onRomanToggle,
            Runnable onTranslationToggle,
            Runnable onSaveToggle,
            Runnable onSettings
    ) {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClipToPadding(false);
        header.setPadding(sideSystemPadding(activity), topSystemPadding(activity), sideSystemPadding(activity), 0);
        parent.addView(header, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        TextView back = textFactory.createText(activity, "‹", landscape ? 30 : 32,
                Color.WHITE, textFactory.resolveTypeface(false));
        back.setGravity(Gravity.CENTER);
        back.setAlpha(0.92f);
        applyPressScale(back);
        back.setOnClickListener(v -> onBack.run());
        header.addView(back, new LinearLayout.LayoutParams(dp(chromeButtonDp), dp(chromeButtonDp)));

        float density = activity.getResources().getDisplayMetrics().density;
        int iconColor = Color.rgb(232, 232, 238);
        // No separate header play/pause button - the artwork's own tap-to-reveal handles it now.

        TextView headerTitle = textFactory.createText(activity, "", 15, Color.WHITE, textFactory.resolveTypeface(true));
        headerTitle.setAlpha(0f);
        header.addView(headerTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Five more icon buttons after this can overflow a narrow phone's width (confirmed: ~360dp
        // screens can't fit back+playPause+roman+translation+save+settings at once) and a
        // plain LinearLayout just clips whatever doesn't fit rather than wrapping — the clipped
        // buttons become silently unreachable. A HorizontalScrollView guarantees every button stays
        // reachable (via a swipe) instead of some disappearing off-screen with no way to tap them.
        HorizontalScrollView buttonScroll = new HorizontalScrollView(activity);
        buttonScroll.setHorizontalScrollBarEnabled(false);
        buttonScroll.setClipToPadding(false);
        header.addView(buttonScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttonScroll.addView(buttons, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageButton romanToggle = createRoundIconButton(activity, R.drawable.ic_spicy_romanization,
                "Toggle transliteration", chromeButtonDp, landscape ? 11 : 12);
        romanToggle.setImageDrawable(romanGlyph);
        romanToggle.setOnClickListener(v -> onRomanToggle.run());
        buttons.addView(romanToggle, new LinearLayout.LayoutParams(dp(chromeButtonDp), dp(chromeButtonDp)));

        ImageButton translationToggle = createRoundIconButton(activity, R.drawable.ic_spicy_translation,
                "Toggle translation", chromeButtonDp, landscape ? 9 : 10);
        translationToggle.setOnClickListener(v -> onTranslationToggle.run());
        LinearLayout.LayoutParams transLp = new LinearLayout.LayoutParams(dp(chromeButtonDp), dp(chromeButtonDp));
        transLp.leftMargin = dp(landscape ? 6 : 8);
        buttons.addView(translationToggle, transLp);

        ImageButton saveToggle = createRoundIconButton(activity,
                new ActionIconDrawable(ActionIconDrawable.Kind.STAR, iconColor, density),
                "Add to Liked Songs", chromeButtonDp, landscape ? 10 : 11);
        saveToggle.setOnClickListener(v -> onSaveToggle.run());
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(dp(chromeButtonDp), dp(chromeButtonDp));
        saveLp.leftMargin = dp(landscape ? 6 : 8);
        buttons.addView(saveToggle, saveLp);

        ImageButton settingsButton = createRoundIconButton(activity,
                new ActionIconDrawable(ActionIconDrawable.Kind.SETTINGS, iconColor, density),
                "Spicy EX settings", chromeButtonDp, landscape ? 11 : 12);
        settingsButton.setOnClickListener(v -> onSettings.run());
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(dp(chromeButtonDp), dp(chromeButtonDp));
        settingsLp.leftMargin = dp(landscape ? 6 : 8);
        buttons.addView(settingsButton, settingsLp);

        romanToggle.setForeground(romanSpinner);
        translationToggle.setForeground(translationSpinner);
        return new ChromeViews(header, null, saveToggle,
                romanToggle, translationToggle, iconColor, density);
    }

    static final class ChromeViews {
        final ImageButton playPauseButton;
        final ImageButton saveToggle;
        final ImageButton romanToggle;
        final ImageButton translationToggle;
        final int iconColor;
        final float density;
        final ViewGroup header;

        ChromeViews(ViewGroup header, ImageButton playPauseButton,
                ImageButton saveToggle, ImageButton romanToggle, ImageButton translationToggle,
                int iconColor, float density) {
            this.header = header;
            this.playPauseButton = playPauseButton;
            this.saveToggle = saveToggle;
            this.romanToggle = romanToggle;
            this.translationToggle = translationToggle;
            this.iconColor = iconColor;
            this.density = density;
        }
    }
}
