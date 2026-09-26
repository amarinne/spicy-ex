package com.eza.spicyex.hooks;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.widget.TextView;

import com.eza.spicyex.References;
import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.SpicyTextDetection;

import java.util.Locale;

/**
 * Stateless rendering/formatting helpers shared by {@link NativeSpicyLyricsHook} and the native
 * lyrics shell. Pulled out of the hook so the shell can live in its own file without reaching into
 * the hook's privates. Consumers use {@code import static ...NativeLyricsUtils.*} so call sites stay
 * unqualified ({@code dp(8)}, {@code isBlank(x)}).
 */
final class NativeLyricsUtils {

    private NativeLyricsUtils() {
    }

    static String sourceProviderLabel(String provider) {
        String value = safe(provider).trim();
        if (value.toLowerCase(Locale.ROOT).endsWith(" cache")) {
            value = value.substring(0, value.length() - " cache".length()).trim();
        }
        return value.isEmpty() ? "unknown" : value;
    }

    static boolean hasJapaneseReading(AppliedLine line) {
        return line != null && line.japaneseReading != null && line.japaneseReading.furigana != null && !line.japaneseReading.furigana.isEmpty();
    }

    static boolean isChineseLine(AppliedLine line) {
        return line != null && SpicyTextDetection.itemChineseTest(line.text);
    }

    static float progress01(long positionMs, long startMs, long endMs) {
        if (endMs <= startMs) return positionMs >= endMs ? 1f : 0f;
        return Math.max(0f, Math.min(1f, (positionMs - startMs) / (float) (endMs - startMs)));
    }

    static float spToPx(float sp) {
        Activity activity = References.currentActivity();
        float scaledDensity = activity == null ? 1f : activity.getResources().getDisplayMetrics().scaledDensity;
        return sp * scaledDensity;
    }

    static int sideSystemPadding(Context context) {
        boolean landscape = false;
        try {
            landscape = context.getResources().getDisplayMetrics().widthPixels > context.getResources().getDisplayMetrics().heightPixels;
        } catch (Throwable ignored) {
        }
        return landscape ? dp(72) : dp(20);
    }

    /** Whether the lyrics screen hides the status bar in the current orientation. */
    static boolean statusBarHidden(Context context) {
        try {
            boolean landscape = context.getResources().getDisplayMetrics().widthPixels
                    > context.getResources().getDisplayMetrics().heightPixels;
            com.eza.spicyex.SpotifyPlusConfig config = com.eza.spicyex.SpotifyPlusConfig.from(context);
            return Boolean.TRUE.equals(config.get(landscape
                    ? com.eza.spicyex.Settings.STATUS_BAR_HIDDEN_LANDSCAPE
                    : com.eza.spicyex.Settings.STATUS_BAR_HIDDEN_PORTRAIT));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Top clearance for the lyrics screen's chrome, in the content area's own coordinates: the
     * chrome gap below where the status bar is, pinned to the same place on screen whether or not
     * the bar is showing.
     *
     * <p>Hiding the bar used to drop its height here, and hiding also lets the window's content
     * start at the very top of the screen, so buttons, artwork and lyrics jumped up. Measuring
     * where the content area actually starts ({@link #contentScreenTop}) and clearing the bar from
     * there puts everything at the same screen position in both states, never below it.
     */
    static int topSystemPadding(Context context) {
        return statusBarClearance(context) + dp(28);
    }

    /** How much of the status bar's height overlaps the content area: none when it starts below. */
    static int statusBarClearance(Context context) {
        int status = 0;
        try {
            int resId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (resId > 0) status = context.getResources().getDimensionPixelSize(resId);
        } catch (Throwable ignored) {
        }
        return Math.max(0, status - contentScreenTop);
    }

    /** Where the activity's content area starts on screen, kept by the lyrics shell. */
    static volatile int contentScreenTop;

    static int dp(int value) {
        Activity activity = References.currentActivity();
        float density = activity == null ? 1f : activity.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (!isBlank(value)) return value;
        }
        return "";
    }

    static boolean isBlank(String value) {
        return com.eza.spicyex.lyrics.LyricUtils.isBlank(value);
    }

    static String safe(String value) {
        return com.eza.spicyex.lyrics.LyricUtils.safe(value);
    }

    static String emptyFallback(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    // TextView.setText always rebuilds the layout, even for identical text; these labels are
    // refreshed from the per-frame loop, so skip the call when nothing changed.
    static void setTextIfChanged(TextView view, CharSequence text) {
        if (view == null) return;
        if (TextUtils.equals(view.getText(), text)) return;
        view.setText(text);
    }

    static String trackIdFromUri(String uri) {
        return com.eza.spicyex.lyrics.LyricUtils.trackIdFromUri(uri);
    }

    static String shortTrackId(String uri) {
        String id = trackIdFromUri(uri);
        return id.isEmpty() ? safe(uri) : id;
    }

    static String formatMs(long ms) {
        if (ms < 0) return "--:--";
        long total = ms / 1000;
        long minutes = total / 60;
        long seconds = total % 60;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }
}
