package com.eza.spicyex;

import com.eza.spicyex.lyrics.KoreanDisplayMode;
import com.eza.spicyex.lyrics.LyricsBackgroundStyle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Owns the settings schema: keys, types, defaults, sections, and allowed values.
 * Does not own persistence, normalized runtime reads, or UI row construction.
 * Only non-INTERNAL settings are shown in the in-Spotify panel; INTERNAL ones are fixed defaults.
 */
public final class Settings {
    public static final List<Setting<?>> ALL = new ArrayList<>();

    // --- Sections ---
    public static final Section LYRICS = new Section("Behavior", "lyrics");
    public static final Section LYRICS_SOURCES = new Section("Lyrics Sources", "lyrics_sources");
    public static final Section TRANSLITERATION = new Section("Reading & Transliteration", "transliteration");
    public static final Section ROMANIZATION = TRANSLITERATION;
    public static final Section TRANSLATION = new Section("Translation", "translation");
    public static final Section NOW_PLAYING = new Section("Now Playing Card", "now_playing");
    public static final Section LYRICS_SCREEN = new Section("Lyrics Screen", "lyrics_screen");
    public static final Section APPLE = new Section("Apple animation style", "apple_music");
    public static final Section TEXT = LYRICS_SCREEN;
    public static final Section ANIMATION = LYRICS_SCREEN;
    public static final Section BACKGROUND = LYRICS_SCREEN;
    public static final Section AI = new Section("AI", "ai");
    public static final Section DEBUG = new Section("About & Diagnostics", "debug");
    public static final Section DISPLAY = TEXT;
    public static final Section INTERNAL = new Section("Internal", "internal");

    // ===================== USER-FACING =====================

    // NOTE: panel section order = order sections first appear here (SettingsPanel.renderSections
    // groups by declaration order in ALL). Keep each section's settings contiguous.

    // --- Lyrics ---
    public static final Setting<String> UI_LANGUAGE = stringSetting(
            "settings_ui_language", LYRICS, "Interface language",
            "en"
    );

    public static final Setting<String> TAP_SEEK_MODE = enumSetting(
            "lyric_tap_seek_mode", LYRICS, "Tap lyric to seek",
            "Double tap",
            "Off", "Single tap", "Double tap"
    );

    // When on, the lyric screen stays open across track changes (Spotify's implicit finish() on
    // song change is suppressed) and reloads for the new track; explicit back/header-close still exits.
    public static final Setting<Boolean> STAY_IN_LYRICS = boolSetting(
            "lyric_stay_on_track_change", LYRICS, "Stay in lyric screen on song change", true
    );

    public static final Setting<Boolean> AUTO_RESUME_FOLLOW = boolSetting(
            "lyric_auto_resume_follow", LYRICS, "Auto-resume lyric follow", true
    );

    // Intro/outro skip: Off hides the affordance entirely; On demand shows a chevrons-right
    // chip next to the jump-to-current control while a lyric gap is active; Auto seeks past
    // the gap with no button. New-feature default Off; no bool predecessor on private main,
    // so no migration.
    public static final Setting<String> AUTO_SKIP_INTRO_OUTRO = enumSetting(
            "lyric_auto_skip_intro_outro", LYRICS, "Auto-skip intro/outro", "Off",
            "Off", "On demand", "Auto"
    );

    // Adds a button to Spotify's persistent mini player (every non-lyrics screen) that jumps
    // straight to the native fullscreen lyrics - see LyricsActivityTakeoverHook.
    public static final Setting<Boolean> MINI_PLAYER_LYRICS_ICON = boolSetting(
            "mini_player_lyrics_icon", LYRICS, "Show lyrics icon on mini player", false
    );

    public static final IntegerSetting SYNC_OFFSET_MS = intSetting(
            "lyric_sync_offset_ms", LYRICS, "Sync offset",
            0, -5000, 5000, 100
    );

    public static final Setting<Boolean> HYPERGLOW_ENABLED = boolSetting(
            "lyrics_hyper_aod_lyrics_enabled", LYRICS, "Publish lyrics to HyperGlow", false
    );

    /** Automatic lyric source arbitration mode shared by fullscreen and now-playing. */
    public static final Setting<String> LYRICS_SOURCE_MODE = enumSetting(
            "lyrics_source_selection_mode", LYRICS_SOURCES, "Lyrics source ranking", "Auto",
            "Auto", "Source order"
    );

    /** Experimental strict source switch. "Spicy" is a retired legacy alias for Apple Music. */
    public static final Setting<String> LYRICS_SOURCE_OVERRIDE = enumSetting(
            "lyrics_source_override", LYRICS_SOURCES, "Lyrics source", "Auto",
            "Auto", "Apple Music", "Spicy", "Spotify", "LRCLIB", "NetEase", "QQ Music", "Musixmatch"
    );

    /** Optional desktop-captured Spotify token (legacy; the retired Spicy remote is no longer queried). */
    public static final Setting<String> SPICY_MANUAL_TOKEN = stringSetting(
            "lyrics_spicy_manual_token", LYRICS_SOURCES, "Spicy manual token", ""
    );

    /** JSON array of source ids, persisted in the desktop-compatible order. */
    public static final Setting<String> LYRICS_SOURCE_ORDER = stringSetting(
            "lyrics_source_order", LYRICS_SOURCES, "Lyrics source order", "managed"
    );

    /** Bounded JSON map of spotify track URI to source id; auto is represented by omission. */
    public static final Setting<String> LYRICS_SOURCE_OVERRIDES = internalSetting(
            "lyrics_source_overrides", "Per-track lyric sources", "{}"
    );

    // Karaoke / off-vocal / instrumental versions have no lyrics of their own; with this on the
    // text sources are searched for the original song instead (see KaraokeTitles).
    public static final Setting<Boolean> KARAOKE_ORIGINAL_LYRICS = boolSetting(
            "lyrics_karaoke_original_lyrics", LYRICS_SOURCES, "Show original lyrics for karaoke versions", false
    );

    // Stored values are the exact display labels; allocation is in CacheStoragePolicy.
    public static final StringSetting CACHE_SIZE =
            (StringSetting) enumSetting(
                    "cache_size", LYRICS_SOURCES, "Cache size limit", "128 MB",
                    "32 MB", "128 MB", "512 MB", "1024 MB", "No limit"
            );

    // --- Now Playing ---
    public static final Setting<String> LIVE_CARD_TAP_MODE = enumSetting(
            "lyrics_live_card_tap_mode", NOW_PLAYING, "Tap card to open lyrics",
            "Double tap",
            "Off", "Single tap", "Double tap"
    );

    public static final Setting<String> LIVE_CARD_TAP_TARGET = enumSetting(
            "lyrics_live_card_tap_target", NOW_PLAYING, "Card tap target",
            "Fullscreen",
            "Fullscreen", "Artwork"
    );

    public static final Setting<String> LIVE_CARD_WEIGHT = enumSetting(
            "lyrics_live_card_weight", NOW_PLAYING, "Lyric weight",
            "Medium",
            "Regular", "Medium", "Bold"
    );

    public static final Setting<String> LIVE_CARD_TEXT_SIZE = enumSetting(
            "lyrics_live_card_text_size", NOW_PLAYING, "Text size",
            "normal",
            "small", "normal", "large", "xlarge", "custom"
    );

    // Multiplier x100 for the live card's "custom" text size mode (0.0-5.0 in 0.05 steps).
    public static final IntegerSetting LIVE_CARD_TEXT_SIZE_CUSTOM = intSetting(
            "lyrics_live_card_text_size_custom", NOW_PLAYING, "Custom size",
            100, 0, 500, 5
    );

    public static final Setting<String> LIVE_CARD_SECONDARY_MODE = enumSetting(
            "lyrics_live_card_secondary_mode", NOW_PLAYING, "Extra line",
            "Main only",
            "Main only", "Transliteration", "Translation", "Both"
    );

    public static final Setting<String> LIVE_CARD_ANIMATION = enumSetting(
            "lyrics_live_card_animation", NOW_PLAYING, "Animation",
            "Karaoke fill",
            "Minimal", "Karaoke fill", "Spotlight word"
    );

    public static final Setting<String> LIVE_CARD_GLOW = enumSetting(
            "lyrics_live_card_glow", NOW_PLAYING, "Glow",
            "Off",
            "Off", "Word only", "Subtle line"
    );

    public static final Setting<String> LIVE_CARD_LINE_SYNC_FILL = enumSetting(
            "lyrics_live_card_line_sync_fill", NOW_PLAYING, "Fill direction",
            "Top to bottom",
            "Top to bottom", "Left to right (block)", "Left to right (sentence)"
    );

    public static final Setting<String> LIVE_CARD_OVERFLOW = enumSetting(
            "lyrics_live_card_overflow", NOW_PLAYING, "Overflow",
            "Wrap",
            "Wrap", "Scroll with lyric", "Clip"
    );

    public static final Setting<String> LIVE_CARD_SCROLL_SCOPE = enumSetting(
            "lyrics_live_card_scroll_scope", NOW_PLAYING, "Scroll scope",
            "Grouped",
            "Grouped", "Individual lines"
    );

    public static final Setting<String> LIVE_CARD_TRANSITION = enumSetting(
            "lyrics_live_card_transition", NOW_PLAYING, "Transition",
            "Fade up",
            "Fade up", "Crossfade", "None"
    );

    // --- Text ---
    public static final Setting<Boolean> ADAPTIVE_SECTIONING = boolSetting(
            "lyric_adaptive_sectioning", TEXT, "Adaptive sectioning", true
    );

    // Scales the vertical gap between lyric rows (sentences); wrapped lines inside one sentence
    // keep a fixed 1.18 line-height (LyricsTextFactory).
    public static final Setting<String> LINE_SPACING = enumSetting(
            "line_spacing", TEXT, "Sentence spacing",
            "spacious",
            "compact", "default", "spacious", "more", "max", "custom"
    );

    // Multiplier x100 for the "custom" line spacing mode (0.0-5.0 in 0.1 steps).
    public static final IntegerSetting LINE_SPACING_CUSTOM = intSetting(
            "line_spacing_custom", TEXT, "Custom spacing",
            150, 0, 500, 5
    );

    // Lyric font weight (Spotify's own faces): "Medium" (default) = spotify_mix_ui_bold,
    // "Bold" = the heavy title-extrabold (was the old default — too thick for some), "Regular".
    public static final Setting<String> LYRICS_WEIGHT = enumSetting(
            "lyrics_weight", TEXT, "Lyric weight",
            "Medium",
            "Regular", "Medium", "Bold"
    );

    // Stored value "default" (the old alias for the Spotify font) coerces to "spotify" via the
    // allowed-values check, so existing configs migrate silently.
    public static final Setting<String> LYRICS_FONT = enumSetting(
            "lyrics_font", TEXT, "Lyric font",
            "spotify",
            "spotify", "apple"
    );

    public static final Setting<String> LYRICS_TEXT_SIZE = enumSetting(
            "lyrics_text_size", TEXT, "Lyric text size",
            "normal",
            "small", "normal", "large", "xlarge", "custom"
    );

    // Multiplier x100 for the "custom" text size mode (0.0-5.0 in 0.1 steps).
    public static final IntegerSetting LYRICS_TEXT_SIZE_CUSTOM = intSetting(
            "lyrics_text_size_custom", TEXT, "Custom size",
            100, 0, 500, 5
    );

    // When on, long lines shrink (23-28sp by length) so they fit; when off, every line
    // uses the same base size and long lines wrap instead.
    public static final Setting<Boolean> LYRICS_ADAPTIVE_TEXT_SIZE = boolSetting(
            "lyrics_adaptive_text_size", TEXT, "Adaptive text size", true
    );

    public static final Setting<String> INTERLUDE_ICON = enumSetting(
            "lyric_interlude_icon", TEXT, "Interlude indicator", "note",
            "dots", "note"
    );

    public static final Setting<String> LIKED_SONGS_BUTTON = enumSetting(
            "lyric_liked_songs_button", TEXT, "Add to Liked Songs button", "Off",
            "Off", "Heart", "Star"
    );

    static final String LEGACY_SHOW_SAVE_BUTTON = "lyric_show_save_button";
    static final String LEGACY_SAVE_BUTTON_ICON = "lyric_save_button_icon";

    public static final Setting<String> FULLSCREEN_CONTROLS = enumSetting(
            "lyrics_fullscreen_controls", TEXT, "Fullscreen controls", "Always on",
            "5 seconds", "10 seconds", "30 seconds", "Always on"
    );

    // Where the active lyric line rests vertically in the viewport. Auto keeps the existing
    // behavior (raised when the Apple-style line-slide animation is on and the screen is
    // portrait, center otherwise); Top/Center/Bottom pin it explicitly regardless of that
    // animation setting; Custom unlocks LYRICS_FOCUS_POSITION_CUSTOM_PERCENT (set by the layout
    // editor's focus-point drag handle). See LyricsScrollController's anchor fractions.
    public static final Setting<String> LYRICS_FOCUS_POSITION = enumSetting(
            "lyrics_focus_position", INTERNAL, "Lyrics focus point", "Auto",
            "Auto", "Top", "Center", "Bottom", "Custom"
    );

    // 0 = top edge, 100 = bottom edge, for LYRICS_FOCUS_POSITION == "Custom".
    public static final IntegerSetting LYRICS_FOCUS_POSITION_CUSTOM_PERCENT = intSetting(
            "lyrics_focus_position_custom_percent", INTERNAL, "Custom focus point",
            50, 0, 100, 5
    );
    // Position of the fullscreen track-info readout (artwork + title/artist). Off hides the
    // readout, its metadata, and its artwork gestures; back, config toggles, and the floating
    // cluster stay. New-feature rule: default Off for all installs, no migration.
    public static final Setting<String> TRACK_INFO_POSITION = enumSetting(
            "lyrics_track_info_position", TEXT, "Track info position", "Off",
            "Off", "Top", "Bottom"
    );

    // Readout title/artist size. Applies live; default Normal matches the original readout.
    public static final Setting<String> TRACK_INFO_TEXT_SIZE = enumSetting(
            "lyrics_track_info_text_size", TEXT, "Track info text size", "Normal",
            "Small", "Normal", "Large", "XLarge", "Custom"
    );

    // Multiplier x100 for the readout's "Custom" text size mode (0.5-2.0 in 0.05 steps).
    // 100 = Normal (title 15sp, artist 12sp). Applies live.
    public static final IntegerSetting TRACK_INFO_TEXT_SIZE_CUSTOM = intSetting(
            "lyrics_track_info_text_size_custom", TEXT, "Custom size",
            100, 50, 200, 5
    );

    // How long track title/artist text behaves when it does not fit the readout width.
    // Clip = single line with end ellipsis; Wrap = up to two lines with end ellipsis;
    // Scroll = single-line marquee. Applies live to top, bottom, and side readouts.
    public static final Setting<String> TRACK_INFO_TEXT_OVERFLOW = enumSetting(
            "lyrics_track_info_text_overflow", TEXT, "Track info overflow", "Wrap",
            "Clip", "Wrap", "Scroll"
    );

    // Readout artwork size (bottom = value, top portrait = value − 24; landscape top stays 54dp
    // for test-build parity; the side panel is container-driven and unaffected). Default Normal.
    public static final Setting<String> TRACK_INFO_ART_SIZE = enumSetting(
            "lyrics_track_info_art_size", TEXT, "Track info art size", "Normal",
            "Small", "Normal", "Large"
    );

    // Separate landscape mode from the Off/Top/Bottom readout: when on and the screen is
    // genuinely wide (landscape with width/height >= 1.2, so near-square foldable screens stay
    // stacked), the fullscreen lyrics use a two-column layout with an artwork panel on the
    // left and the lyrics column on the right. The readout overlays stand down while it is
    // engaged. Takes effect when the lyrics screen is (re)opened.
    public static final Setting<Boolean> ADAPTIVE_LANDSCAPE_LAYOUT = boolSetting(
            "lyrics_adaptive_landscape_layout", TEXT, "Adaptive landscape layout", true
    );

    // Media controls for artwork (two-column panel + readout art, same behavior): Off
    // disables tap gestures and swipe; Single tap opens the play/pause overlay on tap;
    // Double tap toggles play/pause directly with a brief icon pulse, skipping the overlay.
    // Applies live, no reopen needed. Stored booleans migrate in SettingsStore.
    public static final Setting<String> PANEL_MEDIA_CONTROLS = enumSetting(
            "lyrics_panel_media_controls", TEXT, "Panel media controls", "Single tap",
            "Off", "Single tap", "Double tap"
    );

    // --- Animation ---
    // "Gradient wash" = the karaoke fill sweeps each line (classic Spicy look).
    // "Spotlight" = no fill; the active line/word zooms + glows instead (gradient direction ignored).
    // "Apple Music" = Apple-owned motion/blur/fade stack below; every Apple sub-setting applies
    // only while this style is selected and no shared key is ever rewritten (no preset flips).
    public static final Setting<String> ANIMATION_STYLE = enumSetting(
            "lyric_animation_style", ANIMATION, "Animation style",
            "Gradient wash",
            "Gradient wash", "Spotlight", "Apple Music"
    );

    // Apple-owned sub-section (R3). Visible only while ANIMATION_STYLE is Apple Music; each key
    // is read only under that style, so switching styles never migrates or resets user values.
    public static final Setting<Boolean> APPLE_FADE_PASSED_LINES = boolSetting(
            "lyric_apple_fade_passed_lines", APPLE, "Fade passed lines", true
    );

    public static final Setting<Boolean> APPLE_COMPACT_TEXT = boolSetting(
            "lyric_apple_compact_text", APPLE, "Compact text size", true
    );

    public static final Setting<Boolean> APPLE_CJK_WRAP_FIX = boolSetting(
            "lyric_apple_cjk_wrap_fix", APPLE, "Wrap long CJK words", true
    );

    // Row-scroll cascade. Apple-owned: rendered only inside the Apple sub-section.
    public static final Setting<Boolean> LINE_SLIDE_ANIMATION = boolSetting(
            "lyric_line_slide_animation", APPLE, "Apple Music-style slide", false
    );

    // Apple-owned lift motion. This is the only Apple lift entry: WORD_BOUNCE_STYLE deliberately
    // carries no competing "Apple lift" value; the renderer reads this key under Apple Music.
    public static final Setting<Boolean> APPLE_LIFT = boolSetting(
            "lyric_apple_lift", APPLE, "Apple lift", true
    );

    // Apple-owned: a one-shot reveal for the first render of a freshly loaded document (opening
    // the lyrics screen, or a track/source change) - rows rise up from below and fade in instead
    // of appearing instantly. Distinct from LINE_SLIDE_ANIMATION, which is the per-scroll-step
    // cascade; this plays once per document, not on every active-line change.
    public static final Setting<Boolean> LOAD_LIFT_ANIMATION = boolSetting(
            "lyric_load_lift_animation", APPLE, "Rise in on load", false
    );

    // Speed multiplier for row cascade and load-lift animations (100 = normal, 50 = half,
    // 200 = double). Applies to all animation styles.
    public static final IntegerSetting APPLE_CASCADE_SPEED = intSetting(
            "apple_cascade_speed", APPLE, "Slide speed", 100, 50, 200, 5
    );

    public static final IntegerSetting APPLE_SPRING_STRENGTH = intSetting(
            "apple_spring_strength", APPLE, "Spring strength", 100, 50, 200, 5
    );

    // One selector owns both the bounce gate and its scope.
    public static final Setting<String> WORD_BOUNCE = enumSetting(
            "lyric_word_bounce_mode", ANIMATION, "Word bounce",
            "Word/syllable synced only", "Off", "Word/syllable synced only", "All synced rows"
    );

    public static final Setting<String> WORD_BOUNCE_STYLE = enumSetting(
            "lyric_word_bounce_style", ANIMATION, "Bounce style",
            "Phrase zoom", "Phrase zoom", "Word zoom", "Phrase lift", "Word lift", "Apple lift"
    );


    public static final Setting<Boolean> ENABLE_GLOW_BLUR = boolSetting(
            "lyric_enable_glow_blur", ANIMATION, "Text glow", true
    );

    // Shared distance-blur level (was a bool; true migrates to Slight). Slight is the legacy
    // 1.0/1.8px curve, Heavy the strong 5/8px curve. Apple melt/blur read this same level.
    public static final Setting<String> ENABLE_LINE_BLUR = enumSetting(
            "lyric_enable_line_blur", ANIMATION, "Blur distant lines", "Off",
            "Off", "Slight", "Heavy"
    );

    // Direction the karaoke gradient fills each line as it plays: down the line ("Top to bottom")
    // or word-by-word ("Left to right"). Applies under "Gradient wash" only.
    public static final Setting<String> LINE_SYNC_FILL = enumSetting(
            "lyric_line_sync_fill", ANIMATION, "Lyric fill direction",
            "Top to bottom",
            "Top to bottom", "Left to right (block)", "Left to right (sentence)"
    );

    // --- Background ---
    public static final Setting<String> BACKGROUND_STYLE = enumSetting(
            "lyric_background_style", BACKGROUND, "Background style",
            LyricsBackgroundStyle.GRADIENT,
            LyricsBackgroundStyle.GRADIENT,
            LyricsBackgroundStyle.STATIC_TEXTURE,
            LyricsBackgroundStyle.ANIMATED_TEXTURE
    );

    public static final Setting<Boolean> FORCE_DARK_BACKGROUND = boolSetting(
            "lyric_force_dark_background", BACKGROUND, "Force dark background", true
    );

    public static final IntegerSetting EXTRA_DARK_BACKGROUND = intSetting(
            "lyric_extra_dark_background", BACKGROUND, "Darken background", 35, 0, 100, 5
    );

    // Only meaningful when BACKGROUND_STYLE is ANIMATED_TEXTURE - the AGSL noise shader renders
    // into a downsampled offscreen surface (see AmbientArtworkBackgroundView#setRenderScale) and
    // upscales it, since its cost is per output pixel and a full-res shader running continuously
    // for the whole lyrics session is a real sustained heat source on weaker GPUs. Lower values
    // trade a softer/grainier look for less GPU load; higher values render crisper at more cost.
    public static final IntegerSetting BACKGROUND_RENDER_QUALITY = intSetting(
            "lyric_background_render_quality", BACKGROUND, "Background render quality", 35, 15, 100, 5
    );

    public static final Setting<Boolean> DOWNLOAD_LANGUAGE_MODELS = boolSetting(
            "download_language_models", TRANSLITERATION, "Download language models", false
    );

    // --- Romanization (transliteration controls) ---
    public static final Setting<Boolean> TRANSLITERATION_ENABLED = boolSetting(
            "lyrics_transliteration_enabled", TRANSLITERATION, "Transliterate lyrics", false
    );

    // Global romanization layout — aligned under each word (great for language learners comparing
    // word-by-word) vs a single line. Applies to every romanizable script, not just one language.
    public static final Setting<Boolean> ALIGNED_PER_WORD_ROMAJI = boolSetting(
            "lyric_aligned_per_word_romaji", TRANSLITERATION, "Attach transliteration under each word", false
    );

    // "cycle" = the in-screen chip cycles the modes on tap; a fixed value locks to that mode.
    public static final Setting<String> JAPANESE_READING_MODE = enumSetting(
            "lyrics_japanese_reading_mode", TRANSLITERATION, "Japanese reading",
            "romaji_only",
            "off", "furigana_only", "furigana_romaji", "romaji_only", "cycle"
    );

    public static final Setting<String> CHINESE_MODE = enumSetting(
            "lyrics_chinese_mode", TRANSLITERATION, "Chinese reading",
            "pinyin",
            "off", "pinyin", "jyutping", "cycle"
    );

    public static final Setting<String> KOREAN_ROMANIZATION = enumSetting(
            "lyrics_korean_romanization", TRANSLITERATION, "Korean reading",
            KoreanDisplayMode.RR_STANDARD.value,
            KoreanDisplayMode.RR_STANDARD.value,
            KoreanDisplayMode.WORD_TRANSLIT.value,
            KoreanDisplayMode.RR_PRONUNCIATION.value,
            KoreanDisplayMode.VN_PRONUNCIATION.value,
            "cycle"
    );

    // On = Mandarin pinyin tone marks (zhōng guó) + Cantonese jyutping tone numbers (nei5).
    // Off (default) = no tone marks / no jyutping tone numbers — cleaner lyric display.
    public static final Setting<Boolean> CHINESE_TONES = boolSetting(
            "lyrics_chinese_tones", TRANSLITERATION, "Show Chinese tones", false
    );

    // Cyrillic source language — shared glyphs differ (Russian г→g, и→i vs Ukrainian г→h, и→y),
    // so one global map can't serve both (see SpicyRomanizer / ROMANIZATION_AUDIT_BACKLOG CY-4).
    public static final Setting<String> CYRILLIC_MODE = enumSetting(
            "lyrics_cyrillic_mode", TRANSLITERATION, "Cyrillic reading",
            "Russian",
            "Off", "Russian", "Ukrainian", "cycle"
    );

    // Off (default) = drop ь/ъ for readability; On = keep them as BGN/PCGN prime marks (ʹ/ʺ).
    public static final Setting<Boolean> CYRILLIC_KEEP_SIGNS = boolSetting(
            "lyrics_cyrillic_keep_signs", TRANSLITERATION, "Keep Cyrillic soft/hard signs", false
    );

    // --- Translation ---
    public static final Setting<Boolean> TRANSLATION_ENABLED = boolSetting(
            "lyrics_translation_enabled", TRANSLATION, "Translate lyrics", false
    );

    public static final Setting<String> TRANSLATION_BACKEND = internalEnumSetting(
            "lyrics_translation_backend", "Translation backend",
            "google_unofficial", "google_unofficial", "provider"
    );

    public static final Setting<String> TRANSLATION_TARGET = enumSetting(
            "lyrics_translation_target", TRANSLATION, "Target language",
            "en",
            "en", "es", "fr", "de", "it", "pt", "nl", "sv", "no", "da",
            "fi", "pl", "cs", "sk", "hu", "ro", "el", "tr", "uk", "ru",
            "ja", "ko", "zh", "zh-TW", "th", "vi", "id", "ms", "hi", "bn",
            "ta", "ar", "he", "fa"
    );

    public static final Setting<String> TRANSLATION_BRIGHTNESS = enumSetting(
            "lyrics_translation_brightness", TRANSLATION, "Translation brightness",
            "Dimmed",
            "Dimmed", "Bright"
    );

    // --- AI ---
    // One gate owns the whole family: off means no requests and no cached AI overlay, while every
    // paid result stays exactly where it is, waiting to be switched back on.
    public static final Setting<Boolean> AI_ENABLED = boolSetting(
            "ai_enabled", AI, "AI features", false
    );

    public static final Setting<String> AI_PROVIDER = enumSetting(
            "ai_provider", AI, "Provider",
            "gemini",
            "gemini", "openai", "openrouter", "deepseek", "custom"
    );

    public static final Setting<String> AI_DEEPSEEK_REASONING = enumSetting(
            "ai_deepseek_reasoning", AI, "DeepSeek reasoning",
            "Low", "Off", "Low", "High", "Max"
    );

    public static final Setting<String> AI_TRANSLATION_MODE = enumSetting(
            "ai_translation_mode", AI, "AI translation trigger",
            "On demand", "On demand", "Always use AI"
    );

    // Three Meaning display flows sharing one stored key: preview shows Google while a raw-lyrics
    // AI request runs, draft sends Google to the model for refinement, AI-only never asks Google.
    // "Google draft" stays the default until device comparison proves otherwise, and the two older
    // values must keep coercing so an existing choice never silently changes paid-request input.
    public static final Setting<String> AI_TRANSLATION_PIPELINE = enumSetting(
            "ai_translation_pipeline", AI, "AI translation flow",
            "Google draft", "Google preview", "Google draft", "AI only"
    );

    public static final Setting<String> AI_PRONUNCIATION_MODE = enumSetting(
            "ai_pronunciation_mode", AI, "AI pronunciation trigger",
            "On demand", "On demand", "Always use AI"
    );

    public static final Setting<String> AI_PRONUNCIATION_SOURCE = enumSetting(
            "ai_pronunciation_source", AI, "AI pronunciation pipeline",
            "Layered", "Layered", "AI only"
    );

    public static final Setting<String> AI_BUTTON_BEHAVIOR = enumSetting(
            "ai_button_behavior", AI, "Translation button",
            "Generate AI output, then toggle",
            "Generate AI output, then toggle", "Toggle display only"
    );

    // ===================== INTERNAL (fixed defaults, not shown) =====================

    // Legacy composer-owned flag. New builds persist AI_TRANSLATION_PIPELINE; this remains only so
    // an existing explicit choice can migrate without silently changing paid-request input.
    public static final Setting<Boolean> AI_TRANSLATION_REFINE_GOOGLE = internalBoolSetting(
            "ai_translation_refine_google", "Refine Google translation with AI", false
    );

    // Chosen explicitly by the owner from live discovery, never picked for them: a model swapped
    // underneath would change output and quietly invalidate every paid result keyed to the old one.
    public static final Setting<String> AI_MODEL = internalSetting(
            "ai_model", "AI model", ""
    );

    /** Kept per provider choice: Custom and official OpenAI can legitimately use different keys/models. */
    public static final Setting<String> AI_MODEL_GEMINI = internalSetting(
            "ai_model_gemini", "Gemini AI model", ""
    );

    public static final Setting<String> AI_MODEL_OPENAI = internalSetting(
            "ai_model_openai", "OpenAI AI model", ""
    );

    public static final Setting<String> AI_MODEL_OPENROUTER = internalSetting(
            "ai_model_openrouter", "OpenRouter AI model", ""
    );

    public static final Setting<String> AI_MODEL_DEEPSEEK = internalSetting(
            "ai_model_deepseek", "DeepSeek AI model", ""
    );

    public static final Setting<String> AI_MODEL_CUSTOM = internalSetting(
            "ai_model_custom", "Custom AI model", ""
    );

    public static final Setting<String> AI_INSTRUCTIONS_MEANING = internalSetting(
            "ai_instructions_meaning", "AI translation instructions", ""
    );

    public static final Setting<String> AI_INSTRUCTIONS_SOUND = internalSetting(
            "ai_instructions_sound", "AI pronunciation instructions", ""
    );

    // The active instruction may be one of the built-in presets. Keep the owner's custom text in
    // a separate slot so switching presets never destroys prompt work they expect to find later.
    public static final Setting<String> AI_CUSTOM_INSTRUCTIONS_MEANING = internalSetting(
            "ai_custom_instructions_meaning", "Custom AI translation instructions", ""
    );

    public static final Setting<String> AI_CUSTOM_INSTRUCTIONS_SOUND = internalSetting(
            "ai_custom_instructions_sound", "Custom AI pronunciation instructions", ""
    );

    // Normalized base URL for an OpenAI-compatible endpoint. Part of config identity, so switching
    // endpoints cannot serve another endpoint's answers.
    public static final Setting<String> AI_ENDPOINT = internalSetting(
            "ai_endpoint", "AI endpoint", ""
    );

    // Last structured-output probe per provider scope: endpoint host, model name, measured output
    // tokens, failure token. Written by the model test and the one-time readiness check; read by
    // the planner to size the reasoning headroom. Never holds a credential.
    public static final Setting<String> AI_PROBE_GEMINI = internalSetting(
            "ai_probe_gemini", "Gemini AI probe", ""
    );

    public static final Setting<String> AI_PROBE_OPENAI = internalSetting(
            "ai_probe_openai", "OpenAI AI probe", ""
    );

    public static final Setting<String> AI_PROBE_OPENROUTER = internalSetting(
            "ai_probe_openrouter", "OpenRouter AI probe", ""
    );

    public static final Setting<String> AI_PROBE_DEEPSEEK = internalSetting(
            "ai_probe_deepseek", "DeepSeek AI probe", ""
    );

    public static final Setting<String> AI_PROBE_CUSTOM = internalSetting(
            "ai_probe_custom", "Custom AI probe", ""
    );

    public static final Setting<String> DISPLAY_MODE = internalEnumSetting(
            "lyrics_display_mode", "Display mode",
            "original_romanized",
            "original", "romanized", "original_romanized",
            "original_translation", "original_romanized_translation"
    );

    // The native lyrics feature is always on — disabling it removes every entry path (button + this
    // very settings panel), so it's not a user choice.
    public static final Setting<Boolean> NATIVE_SPICY_ENABLED = internalBoolSetting(
            "native_spicy_enabled", "Enable native Spicy lyrics screen", true
    );

    public static final Setting<Boolean> SEND_TOKEN = internalBoolSetting(
            "lyrics_send_token", "Send token", true
    );

    public static final Setting<Boolean> NATIVE_SPICY_ROMANIZATION = internalBoolSetting(
            "native_spicy_romanization", "Enable Spicy romanization", false
    );

    public static final Setting<Boolean> NATIVE_SPICY_TRANSLATION = internalBoolSetting(
            "native_spicy_translation", "Enable Spicy translation", false
    );

    public static final Setting<Boolean> LIVE_CARD_SHOW_TRANSLITERATION = internalBoolSetting(
            "lyrics_live_card_show_transliteration", "Now-playing transliteration", false
    );

    public static final Setting<String> LAST_JAPANESE_CYCLE_MODE = internalEnumSetting(
            "lyrics_last_japanese_cycle_mode", "Last Japanese cycle mode",
            SpotifyPlusConfig.JP_READING_ROMAJI_ONLY,
            SpotifyPlusConfig.JP_READING_FURIGANA_ONLY,
            SpotifyPlusConfig.JP_READING_FURIGANA_ROMAJI
    );

    public static final Setting<String> LAST_CHINESE_CYCLE_MODE = internalEnumSetting(
            "lyrics_last_chinese_cycle_mode", "Last Chinese cycle mode",
            SpotifyPlusConfig.CHINESE_MODE_PINYIN,
            SpotifyPlusConfig.CHINESE_MODE_JYUTPING
    );

    public static final Setting<String> LAST_KOREAN_CYCLE_MODE = internalEnumSetting(
            "lyrics_last_korean_cycle_mode", "Last Korean cycle mode",
            KoreanDisplayMode.RR_STANDARD.value,
            KoreanDisplayMode.WORD_TRANSLIT.value,
            KoreanDisplayMode.RR_PRONUNCIATION.value,
            KoreanDisplayMode.VN_PRONUNCIATION.value
    );

    public static final Setting<String> LAST_CYRILLIC_CYCLE_MODE = internalEnumSetting(
            "lyrics_last_cyrillic_cycle_mode", "Last Cyrillic cycle mode",
            "Russian",
            "Ukrainian"
    );

    public static final Setting<String> SOURCE_LANGUAGE_MODE = internalEnumSetting(
            "lyrics_source_language_mode", "Source language mode",
            "auto",
            "auto", "manual"
    );

    public static final Setting<String> SOURCE_LANGUAGE = internalEnumSetting(
            "lyrics_source_language", "Manual source language",
            "auto",
            "auto", "ja", "zh", "ko", "ru", "uk", "bg", "sr", "mk", "be",
            "el", "ar", "fa", "ur", "he", "th", "hi", "bn", "ta", "te",
            "ka", "hy", "am", "my", "km", "lo", "other"
    );

    public static final Setting<String> BACKGROUND_QUALITY = internalEnumSetting(
            "lyric_background_quality", "Render quality (background & blur)",
            "high",
            "high", "mid", "low", "superLow"
    );

    /** Removed user-facing boolean. Read only when migrating to {@link #BACKGROUND_STYLE}. */
    public static final Setting<Boolean> ENABLE_BACKGROUND = internalBoolSetting(
            "lyric_enable_background", "Legacy animated background", false
    );

    public static final Setting<Boolean> ENABLE_LINE_GRADIENT = internalBoolSetting(
            "lyric_enable_line_gradient", "Line gradient/glow", true
    );

    public static final Setting<Boolean> TOGGLE_PROGRESS_RING = internalBoolSetting(
            "lyric_toggle_progress_ring", "Progress ring on toggle buttons", true
    );

    public static final Setting<Boolean> SHOW_SKELETON = internalBoolSetting(
            "lyric_show_skeleton", "Skeleton placeholder while loading", true
    );

    public static final Setting<String> LAST_CACHE_CLEAR_VERSION = internalSetting(
            "last_cache_clear_version", "last_cache_clear_version", ""
    );

    // --- Helper classes ---

    public static final class Section {
        public final String label;
        public final String id;

        Section(String label, String id) {
            this.label = label;
            this.id = id;
        }
    }

    public static abstract class Setting<T> {
        public final String key;
        public final Section section;
        public final String label;
        public final T defaultValue;
        public final List<T> allowedValues;

        Setting(String key, Section section, String label, T defaultValue, List<T> allowedValues) {
            this.key = key;
            this.section = section;
            this.label = label;
            this.defaultValue = defaultValue;
            this.allowedValues = allowedValues != null ? Collections.unmodifiableList(allowedValues) : null;
            ALL.add(this);
        }

        public abstract T coerce(Object value);
    }

    public static final class BooleanSetting extends Setting<Boolean> {
        BooleanSetting(String key, Section section, String label, Boolean defaultValue) {
            super(key, section, label, defaultValue, null);
        }

        @Override
        public Boolean coerce(Object value) {
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof String) return Boolean.parseBoolean((String) value);
            return defaultValue;
        }
    }

    public static final class StringSetting extends Setting<String> {
        StringSetting(String key, Section section, String label, String defaultValue, List<String> allowedValues) {
            super(key, section, label, defaultValue, allowedValues);
        }

        @Override
        public String coerce(Object value) {
            if (!(value instanceof String)) return defaultValue;
            String s = (String) value;
            if (KOREAN_ROMANIZATION.key.equals(key)) {
                if ("cycle".equals(s)) return s;
                return KoreanDisplayMode.valueOfSetting(s);
            }
            if (LAST_KOREAN_CYCLE_MODE.key.equals(key)) return KoreanDisplayMode.valueOfSetting(s);
            if (allowedValues != null && !allowedValues.contains(s)) return defaultValue;
            return s;
        }
    }

    public static final class IntegerSetting extends Setting<Integer> {
        public final int minValue;
        public final int maxValue;
        public final int stepValue;

        IntegerSetting(String key, Section section, String label, Integer defaultValue,
                       int minValue, int maxValue, int stepValue) {
            super(key, section, label, defaultValue, null);
            this.minValue = minValue;
            this.maxValue = maxValue;
            this.stepValue = Math.max(1, stepValue);
        }

        @Override
        public Integer coerce(Object value) {
            int result = defaultValue;
            if (value instanceof Integer) {
                result = (Integer) value;
            } else if (value instanceof Long) {
                result = (int) ((Long) value).longValue();
            } else if (value instanceof String) {
                try {
                    result = Integer.parseInt((String) value);
                } catch (NumberFormatException ignored) {
                    result = defaultValue;
                }
            }
            return Math.max(minValue, Math.min(maxValue, result));
        }
    }

    private static Setting<Boolean> boolSetting(String key, Section section, String label, boolean defaultValue) {
        return new BooleanSetting(key, section, label, defaultValue);
    }

    private static IntegerSetting intSetting(String key, Section section, String label,
                                             int defaultValue, int minValue, int maxValue, int stepValue) {
        return new IntegerSetting(key, section, label, defaultValue, minValue, maxValue, stepValue);
    }

    private static Setting<Boolean> internalBoolSetting(String key, String label, boolean defaultValue) {
        return new BooleanSetting(key, INTERNAL, label, defaultValue);
    }

    private static Setting<String> enumSetting(String key, Section section, String label, String defaultValue, String... allowed) {
        return new StringSetting(key, section, label, defaultValue, Arrays.asList(allowed));
    }

    private static Setting<String> stringSetting(String key, Section section, String label, String defaultValue) {
        return new StringSetting(key, section, label, defaultValue, null);
    }

    private static Setting<String> internalEnumSetting(String key, String label, String defaultValue, String... allowed) {
        return new StringSetting(key, INTERNAL, label, defaultValue, Arrays.asList(allowed));
    }

    private static Setting<String> internalSetting(String key, String label, String defaultValue) {
        return new StringSetting(key, INTERNAL, label, defaultValue, null);
    }
}
