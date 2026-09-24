package com.eza.spicyex.settings;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsValueNormalizer;

/**
 * Pure value formatting for settings rows and option dialogs.
 *
 * <p>Moved verbatim out of {@code SettingsPanel}. User-facing sentences stay behind
 * {@link PanelStrings} so the locale XML contract holds; number and symbol shaping here
 * is locale-independent by design.
 */
public final class SettingLabels {
    private SettingLabels() {
    }

    /** Multiplier table behind magnitude-based selectors; null means "use the option label". */
    public static String multiplierFor(String key, String value) {
        if ("line_spacing".equals(key)) {
            switch (value) {
                case "compact": return "0.8";
                case "default": return "1.1";
                case "spacious": return "1.5";
                case "more": return "2.0";
                case "max": return "2.5";
                default: return null;
            }
        }
        if ("lyrics_text_size".equals(key) || "lyrics_live_card_text_size".equals(key)) {
            switch (value) {
                case "small":
                case "normal":
                case "large":
                case "xlarge":
                    return SettingsValueNormalizer.textSizeMultiplierLabel(value);
                default: return null;
            }
        }
        return null;
    }

    public static String formatStepper(Settings.IntegerSetting setting, int value) {
        if (setting == Settings.EXTRA_DARK_BACKGROUND) {
            return value + "%";
        }
        if (setting == Settings.BACKGROUND_RENDER_QUALITY) {
            return value + "%";
        }
        if (setting == Settings.LYRICS_TEXT_SIZE_CUSTOM || setting == Settings.LINE_SPACING_CUSTOM
                || setting == Settings.LIVE_CARD_TEXT_SIZE_CUSTOM
                || setting == Settings.TRACK_INFO_TEXT_SIZE_CUSTOM) {
            return String.format(java.util.Locale.US, "×%.2f", value / 100f);
        }
        return formatOffset(value);
    }

    public static String formatOffset(int offsetMs) {
        if (offsetMs == 0) return "0.0s";
        return String.format(java.util.Locale.US, "%+.1fs", offsetMs / 1000f);
    }

    /** Inline glyph preview for symbol-valued options; empty means no preview. */
    public static String optionPreview(String settingKey, String value) {
        if ("lyric_interlude_icon".equals(settingKey)) {
            if ("dots".equals(value)) return "• • •";
            if ("note".equals(value)) return "♪";
        }
        return "";
    }

    /** "<label> · <usage> used" for the cache size row, through the locale pattern. */
    public static String cacheUsageSummary(PanelStrings strings, String optionLabel, String usageText) {
        return strings.format("settings_cache_size_usage_suffix", "%1$s · %2$s used",
                optionLabel, usageText);
    }
}
