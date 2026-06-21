package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeIconButtons.createRoundIconButton;
import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.sideSystemPadding;
import static com.eza.spicyex.hooks.NativeLyricsUtils.topSystemPadding;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.R;
import com.eza.spicyex.lyrics.ChipSpinnerDrawable;
import com.eza.spicyex.lyrics.GlyphIconDrawable;
import com.eza.spicyex.lyrics.LyricsTextFactory;

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
            Runnable onRomanToggle,
            Runnable onTranslationToggle,
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
        back.setOnClickListener(v -> onBack.run());
        header.addView(back, new LinearLayout.LayoutParams(dp(chromeButtonDp), dp(chromeButtonDp)));

        TextView headerTitle = textFactory.createText(activity, "", 15, Color.WHITE, textFactory.resolveTypeface(true));
        headerTitle.setAlpha(0f);
        header.addView(headerTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton romanToggle = createRoundIconButton(activity, R.drawable.ic_spicy_romanization,
                "Toggle transliteration", chromeButtonDp, landscape ? 11 : 12);
        romanToggle.setImageDrawable(romanGlyph);
        romanToggle.setOnClickListener(v -> onRomanToggle.run());
        header.addView(romanToggle, new LinearLayout.LayoutParams(dp(chromeButtonDp), dp(chromeButtonDp)));

        ImageButton translationToggle = createRoundIconButton(activity, R.drawable.ic_spicy_translation,
                "Toggle translation", chromeButtonDp, landscape ? 9 : 10);
        translationToggle.setOnClickListener(v -> onTranslationToggle.run());
        LinearLayout.LayoutParams transLp = new LinearLayout.LayoutParams(dp(chromeButtonDp), dp(chromeButtonDp));
        transLp.leftMargin = dp(landscape ? 6 : 8);
        header.addView(translationToggle, transLp);

        ImageButton settingsButton = createRoundIconButton(activity, R.drawable.ic_spicy_romanization,
                "Spicy EX settings", chromeButtonDp, landscape ? 11 : 12);
        settingsButton.setImageDrawable(new GlyphIconDrawable("⚙", android.graphics.Typeface.DEFAULT));
        settingsButton.setOnClickListener(v -> onSettings.run());
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(dp(chromeButtonDp), dp(chromeButtonDp));
        settingsLp.leftMargin = dp(landscape ? 6 : 8);
        header.addView(settingsButton, settingsLp);

        romanToggle.setForeground(romanSpinner);
        translationToggle.setForeground(translationSpinner);
        return new ChromeViews(romanToggle, translationToggle);
    }

    static final class ChromeViews {
        final ImageButton romanToggle;
        final ImageButton translationToggle;

        ChromeViews(ImageButton romanToggle, ImageButton translationToggle) {
            this.romanToggle = romanToggle;
            this.translationToggle = translationToggle;
        }
    }
}
