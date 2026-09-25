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
        if (setting == Settings.EXTRA_DARK_BACKGROUND
                || setting == Settings.LYRICS_FOCUS_POSITION_CUSTOM_PERCENT
                || setting == Settings.LYRICS_BLUR_INTENSITY) {
            return value + "%";
        }
        if (setting == Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP) {
            return value + "dp";
        }
        if (setting == Settings.AUTO_RESUME_FOLLOW_DELAY_SECONDS) {
            return value + "s";
        }
        if (setting == Settings.BACKGROUND_RENDER_QUALITY) {
            return value + "%";
        }
        if (setting == Settings.APPLE_CASCADE_SPEED || setting == Settings.APPLE_SPRING_STRENGTH) {
            return value + "%";
        }
        if (setting == Settings.FURIGANA_BRIGHTNESS || setting == Settings.FURIGANA_POSITION_PERCENT) {
            return value + "%";
        }
        if (setting == Settings.LYRICS_TEXT_SIZE_CUSTOM || setting == Settings.LINE_SPACING_CUSTOM
                || setting == Settings.LIVE_CARD_TEXT_SIZE_CUSTOM
                || setting == Settings.TRACK_INFO_TEXT_SIZE_CUSTOM) {
            return String.format(java.util.Locale.US, "×%.2f", value / 100f);
        }
        // Falls through to a millisecond-offset display (e.g. "+0.1s") - only correct for
        // SYNC_OFFSET_MS. Any new stepper-style IntegerSetting must be added above, or it will
        // silently render as a bogus seconds value here instead of failing to compile.
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

    /** "Spanish (es)" → "Spanish": the picker shows the code on its own line instead. */
    public static String withoutCode(String label) {
        if (label == null) return "";
        return label.replaceFirst("\\s*\\([A-Za-z]{2,3}(-[A-Za-z0-9]+)?\\)\\s*$", "").trim();
    }

    /** Locale display names come lower case in many languages ("español"); a list reads better capitalized. */
    public static String capitalized(String value, java.util.Locale locale) {
        if (value == null || value.isEmpty()) return "";
        int first = value.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(first)))
                + value.substring(Character.charCount(first));
    }

    /**
     * The translation target that serves a phone or UI locale, or null. Chinese splits by
     * script (Taiwan, Hong Kong and Macau read Traditional), and Android's legacy codes map to
     * the ones the list uses.
     */
    public static String translationTargetFor(java.util.Locale locale, java.util.List<String> targets) {
        if (locale == null || targets == null) return null;
        String language = locale.getLanguage();
        if ("iw".equals(language)) language = "he";
        else if ("in".equals(language)) language = "id";
        else if ("nb".equals(language) || "nn".equals(language)) language = "no";
        if ("zh".equals(language)) {
            String region = locale.getCountry();
            boolean traditional = "Hant".equals(locale.getScript())
                    || "TW".equals(region) || "HK".equals(region) || "MO".equals(region);
            if (traditional && targets.contains("zh-TW")) return "zh-TW";
        }
        return targets.contains(language) ? language : null;
    }
}
