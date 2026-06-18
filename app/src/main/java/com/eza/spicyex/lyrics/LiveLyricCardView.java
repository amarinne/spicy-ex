package com.eza.spicyex.lyrics;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;

/**
 * In-player live-lyric renderer that replaces Spotify's {@code lyrics_element} — a single current
 * line with the Spicy karaoke gradient wash. One line fits Spotify's snippet slot on every
 * now-playing template, so it never clips or fights the album-art layout (multi-line overflow was
 * unreliable across templates). Font size + bold follow the shared lyric config. Driven by
 * {@code NowPlayingLyricController}.
 */
public final class LiveLyricCardView extends LinearLayout {
    private final SpicyAnimatedTextView current;
    private String last = "";

    public LiveLyricCardView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        // Centre vertically in the gap, but keep the line left-aligned to the content margin
        // (CENTER also centred it horizontally, pushing it off the title/progress-bar margin).
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(dp(64));

        LyricsShellSettings cfg = new LyricsShellSettings(context, SpotifyPlusConfig.from(context));
        float sizeMult = cfg.liveCardTextSizeMultiplier();
        String weight = cfg.lyricWeight();

        current = new SpicyAnimatedTextView(context);
        current.setTextSize(19 * sizeMult);
        current.setMaxLines(3);
        current.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // Inherit Spotify's own face at the chosen weight, same as the fullscreen renderer.
        current.setTypeface(lyricTypeface(context, weight));
        current.setSelfGlow(true);
        current.setTextColor(0xFFFFFFFF);
        current.setIncludeFontPadding(true);
        // Full width + left-aligned text so the line starts exactly at the content margin.
        addView(current, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    /** Set the current lyric line with a slide-up cross-fade: old line slides up + fades out, the
     *  new line slides up into place + fades in. */
    public void setLine(String text) {
        final String s = text == null ? "" : text;
        if (s.equals(last)) return;
        last = s;
        current.animate().cancel();
        float rise = dp(16);
        current.animate().translationY(-rise).alpha(0f).setDuration(130).withEndAction(() -> {
            current.setText(s);
            current.setVisibility(isBlank(s) ? GONE : VISIBLE);
            current.setTranslationY(rise);
            current.setAlpha(0f);
            current.animate().translationY(0f).alpha(1f).setDuration(210).start();
        }).start();
    }

    /** Karaoke wash — gradientPosition in Spicy's -20..100 space. */
    public void setGradient(float gradientPosition, float glow) {
        current.setGradientPosition(gradientPosition, glow);
    }

    /** Instrumental gap: show a bright indicator on the single line. */
    public void setInterlude(boolean note) {
        last = note ? "♪" : "• • •";
        current.animate().cancel();
        current.setText(last);
        current.setVisibility(VISIBLE);
        current.setGradientPosition(100f, 0.2f);
        current.setTranslationY(0f);
        current.setAlpha(1f);
    }

    public void clear() {
        last = "";
        current.animate().cancel();
        current.setText("");
        current.setTranslationY(0f);
        current.setAlpha(1f);
        current.setVisibility(GONE);
    }

    private static Typeface lyricTypeface(Context context, String weight) {
        String font = "Regular".equals(weight) ? "spotify_mix_ui_regular"
                : "Bold".equals(weight) ? "spotify_mix_ui_title_extrabold"
                : "spotify_mix_ui_bold"; // Medium
        // Inherit Spotify's own font (we run in its process); fall back to bundled.
        try {
            android.content.res.Resources res = context.getResources();
            int id = res.getIdentifier(font, "font", context.getPackageName());
            if (id != 0) {
                Typeface tf = res.getFont(id);
                if (tf != null) return tf;
            }
        } catch (Throwable ignored) {
        }
        try {
            return Typeface.createFromAsset(context.getAssets(),
                    "Regular".equals(weight) ? "fonts/spotifymix-medium.ttf" : "fonts/sf-pro-display-bold.ttf");
        } catch (Throwable t) {
            return "Regular".equals(weight) ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
