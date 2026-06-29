package com.eza.spicyex;

/** Shared normalization/label helpers for settings values that affect multiple render surfaces. */
public final class SettingsValueNormalizer {
    private SettingsValueNormalizer() {
    }

    public static String normalizeTextWeight(String weight) {
        if ("Regular".equals(weight) || "Bold".equals(weight)) return weight;
        return "Medium";
    }

    public static String normalizeTextSizeMode(String mode) {
        if ("small".equals(mode) || "normal".equals(mode) || "large".equals(mode) || "xlarge".equals(mode)) {
            return mode;
        }
        return "normal";
    }

    public static float textSizeMultiplierFor(String mode) {
        switch (normalizeTextSizeMode(mode)) {
            case "small": return 0.88f;
            case "large": return 1.2f;
            case "xlarge": return 1.45f;
            default: return 1.0f;
        }
    }

    public static String textSizeMultiplierLabel(String mode) {
        switch (normalizeTextSizeMode(mode)) {
            case "small": return "0.88";
            case "large": return "1.2";
            case "xlarge": return "1.45";
            default: return "1.0";
        }
    }
}
