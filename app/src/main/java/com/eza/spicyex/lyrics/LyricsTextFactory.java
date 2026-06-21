package com.eza.spicyex.lyrics;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.TextView;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/** Text, font, and chip factory for the native lyrics shell. */
public final class LyricsTextFactory {
    private final Activity activity;
    private final SpotifyPlusConfig config;
    private final Map<String, Typeface> typefaceCache = new LinkedHashMap<>();

    public LyricsTextFactory(Activity activity, SpotifyPlusConfig config) {
        this.activity = activity;
        this.config = config;
    }

    public Typeface resolveTypeface(boolean bold) {
        // Inherit SPOTIFY'S OWN font (we run inside Spotify): SpotifyMixUI title-extrabold for the
        // thick bold lyric, regular for non-bold — Spotify's real Circular-family weights, far
        // heavier/cleaner than the bundled SF-Pro/medium mismatch. Falls back to bundled if the host
        // font resource can't be resolved.
        String key = bold ? "|bold" : "|regular";
        Typeface cached = typefaceCache.get(key);
        if (cached != null) return cached;
        Typeface resolved = loadSpotifyFont(bold ? "spotify_mix_ui_title_extrabold" : "spotify_mix_ui_regular");
        if (resolved == null) {
            try {
                resolved = Typeface.createFromAsset(activity.getAssets(),
                        bold ? "fonts/sf-pro-display-bold.ttf" : "fonts/spotifymix-medium.ttf");
            } catch (Throwable t) {
                resolved = bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
            }
        }
        typefaceCache.put(key, resolved);
        return resolved;
    }

    /** Lyric typeface for a user weight choice: Regular / Medium (Spotify bold) / Bold (extrabold). */
    public Typeface resolveLyricTypeface(String weight) {
        return resolveLyricTypeface(weight, config == null ? "default" : config.get(Settings.LYRICS_FONT));
    }

    /** Lyric typeface for a user weight + family choice. */
    public Typeface resolveLyricTypeface(String weight, String family) {
        String normalizedFamily = safe(family).toLowerCase(Locale.ROOT);
        if ("apple".equals(normalizedFamily)) {
            String key = "lyric|apple|" + safe(weight);
            Typeface cached = typefaceCache.get(key);
            if (cached != null) return cached;
            Typeface resolved;
            try {
                resolved = Typeface.createFromAsset(activity.getAssets(),
                        "Regular".equals(weight) ? "fonts/spotifymix-medium.ttf" : "fonts/sf-pro-display-bold.ttf");
            } catch (Throwable t) {
                resolved = "Regular".equals(weight) ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD;
            }
            typefaceCache.put(key, resolved);
            return resolved;
        }

        String font = "Regular".equals(weight) ? "spotify_mix_ui_regular"
                : "Bold".equals(weight) ? "spotify_mix_ui_title_extrabold"
                : "spotify_mix_ui_bold"; // Medium — Spotify's bold reads as a clean medium next to extrabold
        String key = "lyric|" + normalizedFamily + "|" + safe(weight);
        Typeface cached = typefaceCache.get(key);
        if (cached != null) return cached;
        Typeface resolved = loadSpotifyFont(font);
        if (resolved == null) {
            try {
                resolved = Typeface.createFromAsset(activity.getAssets(),
                        "Regular".equals(weight) ? "fonts/spotifymix-medium.ttf" : "fonts/sf-pro-display-bold.ttf");
            } catch (Throwable t) {
                resolved = "Regular".equals(weight) ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD;
            }
        }
        typefaceCache.put(key, resolved);
        return resolved;
    }

    /** Load a font resource from the host (Spotify) package by name, e.g. "spotify_mix_ui_bold". */
    private Typeface loadSpotifyFont(String name) {
        try {
            android.content.res.Resources res = activity.getResources();
            int id = res.getIdentifier(name, "font", activity.getPackageName());
            if (id != 0) return res.getFont(id);
        } catch (Throwable ignored) {
        }
        return null;
    }

    public void emphasizePrimaryLyric(TextView view) {
        if (view == null) return;
        // Real extrabold face now carries the weight — no synthetic fake-bold (which muddied glyphs).
    }

    public TextView createChip(Context context, String value) {
        TextView view = createText(context, value, 14, Color.WHITE, resolveTypeface(true));
        view.setGravity(Gravity.CENTER);
        view.setMinWidth(dp(44));
        view.setMinHeight(dp(44));
        view.setIncludeFontPadding(false);
        view.setPadding(0, 0, 0, dp(1));
        return view;
    }

    public void styleChip(TextView view, boolean enabled) {
        if (view == null) return;
        view.setTextColor(enabled ? Color.rgb(245, 245, 248) : Color.rgb(178, 178, 186));
        view.setAlpha(enabled ? 1.0f : 0.78f);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(enabled ? Color.argb(78, 255, 255, 255) : Color.argb(30, 255, 255, 255));
        bg.setStroke(dp(1), enabled ? Color.argb(88, 255, 255, 255) : Color.argb(38, 255, 255, 255));
        view.setBackground(bg);
    }

    public void styleIconChip(ImageButton view, boolean enabled) {
        if (view == null) return;
        view.setColorFilter(enabled ? Color.rgb(245, 245, 248) : Color.rgb(178, 178, 186));
        view.setAlpha(enabled ? 0.96f : 0.72f);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(enabled ? Color.argb(48, 255, 255, 255) : Color.argb(22, 255, 255, 255));
        bg.setStroke(dp(1), enabled ? Color.argb(58, 255, 255, 255) : Color.argb(30, 255, 255, 255));
        view.setBackground(bg);
    }

    public TextView createText(Context context, String value, int sp, int color, Typeface typeface) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(typeface);
        view.setIncludeFontPadding(true);
        view.setLineSpacing(0f, 1.18f); // Spicy lyric line-height parity (Mixed.css 1.1818)
        return view;
    }

    public SpicyAnimatedTextView createSecondaryAnimatedText(Context context, String value, int sp, Typeface typeface) {
        SpicyAnimatedTextView view = new SpicyAnimatedTextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTypeface(typeface);
        view.setIncludeFontPadding(true);
        view.setLineSpacing(0f, 1.04f);
        view.setGradientPosition(-20f, 0f);
        return view;
    }

    private int dp(int value) {
        float density = activity == null ? 1f : activity.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

}
