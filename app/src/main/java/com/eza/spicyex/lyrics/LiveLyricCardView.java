package com.eza.spicyex.lyrics;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Layout;
import android.text.TextPaint;
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
    private final SpicyAnimatedTextView secondary;
    private final float density;
    private boolean minimalAnimation;
    private String last = "";
    private String lastSecondary = "";

    public LiveLyricCardView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        // Centre vertically in the gap, but keep the line left-aligned to the content margin
        // (CENTER also centred it horizontally, pushing it off the title/progress-bar margin).
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(dp(64));
        setClipToPadding(false);
        setWillNotDraw(false);

        LyricsShellSettings cfg = new LyricsShellSettings(context, SpotifyPlusConfig.from(context));
        float sizeMult = cfg.liveCardTextSizeMultiplier();
        String weight = cfg.liveCardWeight();
        String font = SpotifyPlusConfig.from(context).get(Settings.LYRICS_FONT);

        current = new SpicyAnimatedTextView(context);
        current.setTextSize(19 * sizeMult);
        current.setMaxLines(3);
        current.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // Inherit Spotify's own face at the chosen weight, same as the fullscreen renderer.
        current.setTypeface(lyricTypeface(context, weight, font));
        current.setTextColor(0xFFFFFFFF);
        current.setIncludeFontPadding(true);
        // Full width + left-aligned text so the line starts exactly at the content margin.
        addView(current, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        secondary = new SpicyAnimatedTextView(context);
        secondary.setTextSize(Math.max(12f, 14f * sizeMult));
        secondary.setMaxLines(2);
        secondary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        secondary.setTypeface(lyricTypeface(context, "Regular", font));
        secondary.setAlpha(0.74f);
        secondary.setIncludeFontPadding(true);
        secondary.setGradientPosition(100f, 0f);
        secondary.setVisibility(GONE);
        LayoutParams secondaryLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        secondaryLp.topMargin = dp(-2);
        addView(secondary, secondaryLp);
    }

    public void applyConfig(LyricsRenderConfig config) {
        if (config == null) return;
        current.setTextSize(19 * config.liveCardTextSizeMultiplier);
        current.setTypeface(lyricTypeface(getContext(), config.liveCardWeight, config.lyricsFont));
        secondary.setTextSize(Math.max(12f, 14f * config.liveCardTextSizeMultiplier));
        secondary.setTypeface(lyricTypeface(getContext(), "Regular", config.lyricsFont));
        setMinimalAnimation(config.liveCardMinimalAnimation);
        requestLayout();
        invalidateForFrame();
    }

    public void setMinimalAnimation(boolean minimal) {
        if (minimalAnimation == minimal) {
            if (!minimal) return;
        }
        minimalAnimation = minimal;
        if (minimal) {
            current.setGradientPosition(100f, 0f);
            secondary.setGradientPosition(100f, 0f);
            current.setBrightnessMultiplier(1f);
            secondary.setBrightnessMultiplier(1f);
            setSharedScale(1f);
        }
        invalidateForFrame();
    }

    /** Set the current lyric line with a slide-up cross-fade: old line slides up + fades out, the
     *  new line slides up into place + fades in. */
    public void setLine(CharSequence text) {
        setLine(text, "");
    }

    public void setLine(CharSequence text, String secondaryText) {
        final CharSequence displayText = text == null ? "" : text;
        final String s = displayText.toString();
        final String sub = secondaryText == null ? "" : secondaryText;
        if (s.equals(last) && sub.equals(lastSecondary)) return;
        last = s;
        lastSecondary = sub;
        animate().cancel();
        float rise = dp(16);
        animate().translationY(-rise).alpha(0f).setDuration(130).withEndAction(() -> {
            current.setText(displayText);
            current.setVisibility(isBlank(s) ? GONE : VISIBLE);
            secondary.setText(sub);
            secondary.setGradientPosition(100f, 0f);
            current.setBrightnessMultiplier(1f);
            secondary.setBrightnessMultiplier(1f);
            secondary.setVisibility(isBlank(sub) ? GONE : VISIBLE);
            current.setTranslationY(0f);
            secondary.setTranslationY(0f);
            current.setAlpha(1f);
            secondary.setAlpha(isBlank(sub) ? 0f : 0.74f);
            setTranslationY(rise);
            setAlpha(0f);
            animate().translationY(0f).alpha(1f).setDuration(210).start();
        }).start();
    }

    /** Karaoke wash — gradientPosition in Spicy's -20..100 space. */
    public void setGradient(float gradientPosition, float glow) {
        setGradient(gradientPosition, glow, 1f);
    }

    /** Karaoke wash plus text brightness. Brightness is separate from blur/glow intensity. */
    public void setGradient(float gradientPosition, float glow, float brightness) {
        if (minimalAnimation) {
            current.setBrightnessMultiplier(1f);
            secondary.setBrightnessMultiplier(1f);
            current.setGradientPosition(100f, 0f);
            secondary.setGradientPosition(100f, 0f);
        } else {
            current.setBrightnessMultiplier(brightness);
            secondary.setBrightnessMultiplier(brightness);
            current.setGradientPosition(gradientPosition, glow);
            secondary.setGradientPosition(gradientPosition, glow * 0.85f);
        }
        invalidateForFrame();
    }

    /** Spotlight zoom target shared with the fullscreen line-level renderer. */
    public void setScaleTarget(float scale) {
        if (minimalAnimation) {
            setSharedScale(1f);
            invalidateForFrame();
            return;
        }
        float bounded = Math.max(0.95f, Math.min(1.04f, scale));
        setSharedScale(bounded);
        invalidateForFrame();
    }

    private void setSharedScale(float scale) {
        current.setPivotX(0f);
        current.setPivotY(current.getHeight() > 0 ? current.getHeight() * 0.5f : 0f);
        current.setScaleX(scale);
        current.setScaleY(scale);
        secondary.setPivotX(0f);
        secondary.setPivotY(secondary.getHeight() > 0 ? secondary.getHeight() * 0.5f : 0f);
        secondary.setScaleX(scale);
        secondary.setScaleY(scale);
    }

    /** Instrumental gap: show a bright indicator on the single line. */
    public void setInterlude(boolean note) {
        setLine(note ? "♪" : "• • •", "");
        current.setBrightnessMultiplier(1f);
        current.setGradientPosition(100f, 0.2f);
        current.setTranslationY(0f);
        current.setScaleX(1f);
        current.setScaleY(1f);
        current.setAlpha(1f);
        invalidateForFrame();
    }

    public void clear() {
        last = "";
        lastSecondary = "";
        animate().cancel();
        current.animate().cancel();
        secondary.animate().cancel();
        current.setText("");
        secondary.setText("");
        setTranslationY(0f);
        setAlpha(1f);
        current.setTranslationY(0f);
        current.setScaleX(1f);
        current.setScaleY(1f);
        current.setAlpha(1f);
        current.setBrightnessMultiplier(1f);
        secondary.setBrightnessMultiplier(1f);
        current.setVisibility(GONE);
        secondary.setVisibility(GONE);
        invalidateForFrame();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (!minimalAnimation) {
            drawGlow(canvas, current, 1f);
            drawGlow(canvas, secondary, 0.85f);
        }
        super.dispatchDraw(canvas);
    }

    private void drawGlow(Canvas canvas, SpicyAnimatedTextView view, float strength) {
        if (view.getVisibility() != VISIBLE || view.getAlpha() <= 0.01f) return;
        float g = Math.max(0f, Math.min(1f, view.getGlow() * strength));
        if (g <= 0.02f) return;
        Layout layout = view.getLayout();
        if (layout == null) return;

        TextPaint paint = view.getPaint();
        int savedColor = paint.getColor();
        Shader savedShader = paint.getShader();
        int alpha = Math.round(255f * Math.min(1f, 1.1f * g) * view.getAlpha());
        int glowColor = Color.argb(alpha, 255, 255, 255);
        paint.setShader(null);
        paint.setColor(glowColor);
        paint.setShadowLayer((6f + 16f * g) * density, 0f, 0f, glowColor);

        int save = canvas.save();
        canvas.translate(view.getLeft() + view.getTranslationX() + view.getPivotX(),
                view.getTop() + view.getTranslationY() + view.getPivotY());
        canvas.scale(view.getScaleX(), view.getScaleY());
        canvas.translate(-view.getPivotX() + view.getTotalPaddingLeft(),
                -view.getPivotY() + view.getTotalPaddingTop());
        layout.draw(canvas);
        canvas.restoreToCount(save);

        paint.clearShadowLayer();
        paint.setColor(savedColor);
        paint.setShader(savedShader);
    }

    private void invalidateForFrame() {
        if (Build.VERSION.SDK_INT >= 16) postInvalidateOnAnimation();
        else invalidate();
    }

    private static Typeface lyricTypeface(Context context, String weight, String family) {
        if ("apple".equalsIgnoreCase(family)) {
            try {
                return Typeface.createFromAsset(context.getAssets(),
                        "Regular".equals(weight) ? "fonts/spotifymix-medium.ttf" : "fonts/sf-pro-display-bold.ttf");
            } catch (Throwable ignored) {
                return "Regular".equals(weight) ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD;
            }
        }
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
