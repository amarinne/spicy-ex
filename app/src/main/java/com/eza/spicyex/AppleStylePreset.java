package com.eza.spicyex;

final class AppleStylePreset {
    private AppleStylePreset() {
    }

    static void apply(SettingsStore store, boolean enabled) {
        if (enabled) {
            store.put(Settings.APPLE_STYLE_PRIOR_SLIDE, store.get(Settings.LINE_SLIDE_ANIMATION));
            store.put(Settings.APPLE_STYLE_PRIOR_BOUNCE_STYLE, store.get(Settings.WORD_BOUNCE_STYLE));
            store.put(Settings.APPLE_STYLE_PRIOR_LINE_BLUR, store.get(Settings.ENABLE_LINE_BLUR));
            store.put(Settings.APPLE_STYLE_PRIOR_FONT, store.get(Settings.LYRICS_FONT));

            store.put(Settings.LINE_SLIDE_ANIMATION, true);
            store.put(Settings.WORD_BOUNCE_STYLE, "Apple lift");
            store.put(Settings.ENABLE_LINE_BLUR, true);
            store.put(Settings.LYRICS_FONT, "apple");
            store.put(Settings.APPLE_EDGE_MELT_TOP, true);
            store.put(Settings.APPLE_EDGE_MELT_BOTTOM, true);
            store.put(Settings.APPLE_STRONG_DISTANCE_BLUR, true);
            store.put(Settings.APPLE_FADE_PASSED_LINES, true);
            store.put(Settings.APPLE_RELEASE_BLUR_ON_TOUCH, true);
            store.put(Settings.APPLE_COMPACT_TEXT, true);
            store.put(Settings.APPLE_CJK_WRAP_FIX, true);
            return;
        }
        if (!store.contains(Settings.APPLE_STYLE_PRIOR_SLIDE)) return;
        store.put(Settings.LINE_SLIDE_ANIMATION, store.get(Settings.APPLE_STYLE_PRIOR_SLIDE));
        store.put(Settings.WORD_BOUNCE_STYLE, store.get(Settings.APPLE_STYLE_PRIOR_BOUNCE_STYLE));
        store.put(Settings.ENABLE_LINE_BLUR, store.get(Settings.APPLE_STYLE_PRIOR_LINE_BLUR));
        store.put(Settings.LYRICS_FONT, store.get(Settings.APPLE_STYLE_PRIOR_FONT));
    }
}
