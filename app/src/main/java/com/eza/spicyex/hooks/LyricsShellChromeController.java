package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeIconButtons.createRoundIconButton;
import static com.eza.spicyex.hooks.NativeIconButtons.applyPressScale;
import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.sideSystemPadding;
import static com.eza.spicyex.hooks.NativeLyricsUtils.topSystemPadding;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.eza.spicyex.R;
import com.eza.spicyex.Settings;
import com.eza.spicyex.lyrics.ChipSpinnerDrawable;
import com.eza.spicyex.lyrics.GlyphIconDrawable;
import com.eza.spicyex.lyrics.LyricsTextFactory;
import com.eza.spicyex.ui.ActionIconDrawable;

/**
 * Builds the fullscreen shell's top chrome row.
 *
 * <p>Default control order: cog anchored top-right. Off/Bottom: Back leading at the top-left
 * corner, then title spacer, then transliteration, translation, like, settings (reads
 * right-to-left as cog, like, translation, transliteration). Top: Back is gone (art owns the
 * corner) and the controls form a vertical rail anchored right, reading top to bottom as
 * settings, like, translation, transliteration. R1 like sits second, ahead of the reading
 * toggles, only when enabled; Off reserves nothing. {@link Settings#CHROME_CLUSTER_POSITION}
 * mirrors this whole arrangement to the opposite edge (Back trails, cluster leads instead) in
 * every mode - see {@link #applyClusterPosition} - without changing the cluster's own internal
 * icon order. Rotation remounts; mode switches re-apply synchronously — nothing rewrites layout
 * from size listeners.
 */
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
            boolean topActive,
            boolean mirrored,
            Runnable onBack,
            Runnable onRomanToggle,
            Runnable onTranslationToggle,
            Runnable onSettings,
            ActionIconDrawable.Kind likeKind,
            Runnable onLike
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

        // Back leads at the top-left corner (Off/Bottom). Gone in Top, where art owns it.
        TextView back = textFactory.createText(activity, "‹", landscape ? 30 : 32,
                Color.WHITE, textFactory.resolveTypeface(false));
        back.setGravity(Gravity.CENTER);
        back.setAlpha(0.92f);
        back.setContentDescription("Back");
        applyPressScale(back);
        back.setOnClickListener(v -> onBack.run());
        back.setMinimumWidth(dp(chromeButtonDp));
        back.setMinimumHeight(dp(chromeButtonDp));
        header.addView(back, new LinearLayout.LayoutParams(dp(chromeButtonDp), dp(chromeButtonDp)));

        TextView headerTitle = textFactory.createText(activity, "", 15, Color.WHITE, textFactory.resolveTypeface(true));
        headerTitle.setAlpha(0f);
        header.addView(headerTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout configCluster = new LinearLayout(activity);
        configCluster.setOrientation(LinearLayout.HORIZONTAL);
        configCluster.setGravity(Gravity.CENTER_VERTICAL);
        configCluster.setClipToPadding(false);
        header.addView(configCluster, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageButton romanToggle = createRoundIconButton(activity, R.drawable.ic_spicy_romanization,
                "Toggle transliteration", chromeButtonDp, landscape ? 11 : 12);
        romanToggle.setImageDrawable(romanGlyph);
        romanToggle.setOnClickListener(v -> onRomanToggle.run());

        ImageButton translationToggle = createRoundIconButton(activity, R.drawable.ic_spicy_translation,
                "Toggle translation", chromeButtonDp, landscape ? 9 : 10);
        translationToggle.setOnClickListener(v -> onTranslationToggle.run());

        float density = activity.getResources().getDisplayMetrics().density;
        int iconColor = Color.rgb(232, 232, 238);
        ImageButton settingsButton = createRoundIconButton(activity,
                new ActionIconDrawable(ActionIconDrawable.Kind.SETTINGS, iconColor, density),
                "Spicy EX settings", chromeButtonDp, landscape ? 11 : 12);
        settingsButton.setOnClickListener(v -> onSettings.run());

        ImageButton likeButton = createRoundIconButton(activity,
                new ActionIconDrawable(likeKind != null ? likeKind : ActionIconDrawable.Kind.PLUS,
                        iconColor, density),
                "Add to Liked Songs", chromeButtonDp, landscape ? 11 : 12);
        if (likeKind == null) likeButton.setVisibility(View.GONE);
        if (onLike != null) likeButton.setOnClickListener(v -> onLike.run());

        configCluster.addView(romanToggle);
        configCluster.addView(translationToggle);
        configCluster.addView(likeButton);
        configCluster.addView(settingsButton);

        romanToggle.setForeground(romanSpinner);
        translationToggle.setForeground(translationSpinner);
        ChromeViews views = new ChromeViews(header, headerTitle, back, configCluster,
                romanToggle, translationToggle, settingsButton, likeButton);
        applyTopMode(views, topActive, chromeButtonDp, landscape);
        applyClusterPosition(views, mirrored);
        return views;
    }

    /** Mirrors the whole header arrangement to the opposite edge:
     *  {@link Settings#CHROME_CLUSTER_POSITION} "Left" puts the cluster (and, in Top mode's
     *  vertical rail, the rail itself) at the start instead of the end, with Back trailing
     *  instead of leading. Internal order among the cluster's own icons is unaffected - only
     *  which side of the title spacer {@code back}/{@code configCluster} sit on. Reorders
     *  {@code header}'s three children directly rather than reassigning gravity, since a
     *  horizontal LinearLayout positions a child purely by where it falls relative to the
     *  weighted title spacer. */
    static void applyClusterPosition(ChromeViews chrome, boolean mirrored) {
        if (chrome == null || chrome.header == null) return;
        View[] order = mirrored
                ? new View[]{chrome.configCluster, chrome.headerTitle, chrome.back}
                : new View[]{chrome.back, chrome.headerTitle, chrome.configCluster};
        for (View child : order) {
            if (child == null) continue;
            chrome.header.removeView(child);
            chrome.header.addView(child);
        }
    }

    /**
     * Applies the Top/Off-Bottom arrangement synchronously: call at mount and on mode
     * change only. Order follows {@code docs/FULLSCREEN_CHROME_SPEC.md}: Top is a vertical
     * right rail (settings, like, translation, transliteration); otherwise Back leads and
     * the row reads (transliteration, translation, like, settings) so right-to-left is
     * cog, like, translation, transliteration. Hidden (Lite) controls and an Off like
     * button reserve nothing; spacing follows visible order only.
     */
    static void applyTopMode(ChromeViews chrome, boolean topActive,
            int chromeButtonDp, boolean landscape) {
        if (chrome == null || chrome.header == null) return;
        int size = dp(chromeButtonDp);
        int gap = dp(landscape ? 6 : 8);
        if (chrome.back != null) {
            chrome.back.setVisibility(topActive ? View.GONE : View.VISIBLE);
        }
        if (chrome.configCluster == null) return;
        chrome.configCluster.setOrientation(
                topActive ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        chrome.configCluster.setGravity(
                topActive ? Gravity.END : Gravity.CENTER_VERTICAL);
        // Reorder without dropping LayoutParams; order is owned by
        // docs/FULLSCREEN_CHROME_SPEC.md (cog anchored top-right, like second).
        ImageButton[] order = topActive
                ? new ImageButton[]{chrome.settingsButton, chrome.likeButton, chrome.translationToggle, chrome.romanToggle}
                : new ImageButton[]{chrome.romanToggle, chrome.translationToggle, chrome.likeButton, chrome.settingsButton};
        for (ImageButton button : order) {
            if (button == null || chrome.configCluster.indexOfChild(button) < 0) continue;
            chrome.configCluster.removeView(button);
            chrome.configCluster.addView(button, new LinearLayout.LayoutParams(size, size));
        }
        // The gap is a transparent divider rather than per-button margins: LinearLayout draws a
        // middle divider only between children that are not GONE, and re-evaluates that on every
        // layout. Margins were computed from visibility at the moment this ran, so a toggle that
        // appeared or disappeared later (a new song, an ad) left two buttons touching.
        android.graphics.drawable.GradientDrawable divider = new android.graphics.drawable.GradientDrawable();
        divider.setColor(Color.TRANSPARENT);
        divider.setSize(gap, gap);
        chrome.configCluster.setDividerDrawable(divider);
        chrome.configCluster.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        for (int i = 0; i < chrome.configCluster.getChildCount(); i++) {
            View child = chrome.configCluster.getChildAt(i);
            if (!(child.getLayoutParams() instanceof LinearLayout.LayoutParams)) continue;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
            lp.width = size;
            lp.height = size;
            lp.topMargin = 0;
            lp.bottomMargin = 0;
            lp.leftMargin = 0;
            lp.rightMargin = 0;
            child.setLayoutParams(lp);
            child.setMinimumWidth(size);
            child.setMinimumHeight(size);
        }
    }

    static final class ChromeViews {
        final ViewGroup header;
        final TextView headerTitle;
        final TextView back;
        final LinearLayout configCluster;
        final ImageButton romanToggle;
        final ImageButton translationToggle;
        final ImageButton settingsButton;
        final ImageButton likeButton;

        ChromeViews(ViewGroup header, TextView headerTitle, TextView back,
                LinearLayout configCluster,
                ImageButton romanToggle, ImageButton translationToggle,
                ImageButton settingsButton, ImageButton likeButton) {
            this.header = header;
            this.headerTitle = headerTitle;
            this.back = back;
            this.configCluster = configCluster;
            this.romanToggle = romanToggle;
            this.translationToggle = translationToggle;
            this.settingsButton = settingsButton;
            this.likeButton = likeButton;
        }
    }
}
