package com.eza.spicyex;

import com.eza.spicyex.lyrics.language.KoreanDisplayMode;
import com.eza.spicyex.settings.values.AiButtonBehavior;
import com.eza.spicyex.settings.values.AiDeepSeekReasoning;
import com.eza.spicyex.settings.values.AiPronunciationMode;
import com.eza.spicyex.settings.values.AiPronunciationSource;
import com.eza.spicyex.settings.values.AiProviderChoice;
import com.eza.spicyex.settings.values.AiTranslationMode;
import com.eza.spicyex.settings.values.AnimationStyle;
import com.eza.spicyex.settings.values.AutoSkipIntroOutro;
import com.eza.spicyex.settings.values.BackgroundQuality;
import com.eza.spicyex.settings.values.BackgroundStyle;
import com.eza.spicyex.settings.values.BackgroundEffect;
import com.eza.spicyex.settings.values.BackgroundEffectPool;
import com.eza.spicyex.settings.values.ChineseMode;
import com.eza.spicyex.settings.values.ChromeClusterLayout;
import com.eza.spicyex.settings.values.ChromeClusterPosition;
import com.eza.spicyex.settings.values.ChipPosition;
import com.eza.spicyex.settings.values.ChipStyle;
import com.eza.spicyex.settings.values.CyrillicMode;
import com.eza.spicyex.settings.values.DisplayMode;
import com.eza.spicyex.settings.values.FollowChipIcon;
import com.eza.spicyex.settings.values.InterludeIcon;
import com.eza.spicyex.settings.values.JapaneseReadingMode;
import com.eza.spicyex.settings.values.LastChineseCycleMode;
import com.eza.spicyex.settings.values.LastCyrillicCycleMode;
import com.eza.spicyex.settings.values.LastJapaneseCycleMode;
import com.eza.spicyex.settings.values.LikedSongsButton;
import com.eza.spicyex.settings.values.LineSpacingMode;
import com.eza.spicyex.settings.values.LineSyncFill;
import com.eza.spicyex.settings.values.LiveCardAnimation;
import com.eza.spicyex.settings.values.LiveCardGlow;
import com.eza.spicyex.settings.values.LiveCardOverflow;
import com.eza.spicyex.settings.values.LiveCardScrollScope;
import com.eza.spicyex.settings.values.LiveCardTapMode;
import com.eza.spicyex.settings.values.LiveCardTapTarget;
import com.eza.spicyex.settings.values.LiveCardTransition;
import com.eza.spicyex.settings.values.LyricsFocusPosition;
import com.eza.spicyex.settings.values.LyricsFont;
import com.eza.spicyex.settings.values.LyricsSourceMode;
import com.eza.spicyex.settings.values.LyricsSourceOverride;
import com.eza.spicyex.settings.values.PanelMediaMode;
import com.eza.spicyex.settings.values.PipFocus;
import com.eza.spicyex.settings.values.PipShape;
import com.eza.spicyex.settings.values.SecondaryTextMode;
import com.eza.spicyex.settings.values.SourceLanguageMode;
import com.eza.spicyex.settings.values.StatusBarHiddenMode;
import com.eza.spicyex.settings.values.StoredValue;
import com.eza.spicyex.settings.values.StoredValues;
import com.eza.spicyex.settings.values.TapSeekMode;
import com.eza.spicyex.settings.values.TextSizeMode;
import com.eza.spicyex.settings.values.TextWeight;
import com.eza.spicyex.settings.values.TrackInfoArtSize;
import com.eza.spicyex.settings.values.TrackInfoBackground;
import com.eza.spicyex.settings.values.TrackInfoPosition;
import com.eza.spicyex.settings.values.TrackInfoTextAlign;
import com.eza.spicyex.settings.values.TrackInfoTextOverflow;
import com.eza.spicyex.settings.values.TrackInfoTextSize;
import com.eza.spicyex.settings.values.TransitionFeel;
import com.eza.spicyex.settings.values.TranslationBackend;
import com.eza.spicyex.settings.values.TranslationBrightness;
import com.eza.spicyex.settings.values.WordBounceMode;
import com.eza.spicyex.settings.values.WordBounceStyle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Owns the settings schema: keys, types, defaults, sections, allowed values, and orientation scope.
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
    public static final Section LYRICS_SCREEN = new Section("Layout editor", "lyrics_screen");
    public static final Section APPLE = new Section("Apple animation style", "apple_music");
    public static final Section TEXT = LYRICS_SCREEN;
    public static final Section ANIMATION = LYRICS_SCREEN;
    public static final Section BACKGROUND = LYRICS_SCREEN;
    public static final Section AI = new Section("AI", "ai");
    public static final Section ANDROID_AUTO = new Section("Android Auto", "android_auto");
    public static final Section PIP = new Section("Picture-in-picture", "pip");
    public static final Section DEBUG = new Section("About & Diagnostics", "debug");
    public static final Section DISPLAY = TEXT;
    public static final Section INTERNAL = new Section("Internal", "internal");

    // ===================== USER-FACING =====================

    // NOTE: the panel's section order and row order live in the control registry
    // (settings/registry/ControlRegistry), not in this declaration order. Keep each section's
    // settings contiguous anyway, so the schema reads like the panel.

    // --- Android Auto ---
    public static final Setting<Boolean> AUTO_ENABLED = boolSetting(false,
            "auto_enabled", ANDROID_AUTO, "Enable Android Auto lyrics", false);
    public static final Setting<String> AUTO_SECONDARY_TEXT = enumSetting(false,
            "auto_secondary_text", ANDROID_AUTO, "Secondary text", SecondaryTextMode.class);

    // --- Lyrics ---
    public static final Setting<String> UI_LANGUAGE = stringSetting(false,
            "settings_ui_language", LYRICS, "Interface language",
            "en"
    );

    public static final Setting<String> TAP_SEEK_MODE = enumSetting(false,
            "lyric_tap_seek_mode", LYRICS, "Tap lyric to seek",
            TapSeekMode.class
    );

    // When on, the lyric screen stays open across track changes (Spotify's implicit finish() on
    // song change is suppressed) and reloads for the new track; explicit back/header-close still exits.
    public static final Setting<Boolean> STAY_IN_LYRICS = boolSetting(false,
            "lyric_stay_on_track_change", LYRICS, "Stay in lyric screen on song change", true
    );

    // Manual scrolls suspend follow until the floating jump button is tapped. Auto-resume returns
    // follow by itself after the delay below.
    public static final Setting<Boolean> AUTO_RESUME_FOLLOW = boolSetting(false,
            "lyric_auto_resume_follow", LYRICS, "Auto-resume lyric follow", true
    );

    // Cooldown, in seconds, after a manual scroll settles before auto-resuming follow - see
    // NativeSpicyShellViewImpl#maybeAutoResumeFollow. 3s is close to the old hardcoded 2.5s,
    // rounded to a whole second so the stepper reads cleanly.
    public static final IntegerSetting AUTO_RESUME_FOLLOW_DELAY_SECONDS = intSetting(false,
            "lyric_auto_resume_follow_delay_seconds", LYRICS, "Auto-resume follow delay",
            3, 1, 10, 1
    );

    // Intro/outro skip: Off hides the affordance entirely; On demand shows a chevrons-right
    // chip next to the jump-to-current control while a lyric gap is active; Auto seeks past
    // the gap with no button. New-feature default Off; no bool predecessor on private main,
    // so no migration.
    public static final Setting<String> AUTO_SKIP_INTRO_OUTRO = enumSetting(false,
            "lyric_auto_skip_intro_outro", LYRICS, "Auto-skip intro/outro",
            AutoSkipIntroOutro.class
    );

    // How the "On demand" skip chip presents itself: a plain icon, a permanently-labelled pill,
    // or Auto (opens as a labelled pill so an unexplained chevron icon doesn't have to speak for
    // itself, then collapses to the icon after a few seconds so it stops competing with the
    // lyrics for attention).
    public static final Setting<String> SKIP_CHIP_STYLE = enumSetting(false,
            "lyric_skip_chip_style", INTERNAL, "Skip chip style", ChipStyle.class
    );

    // Which side the skip chip floats above the jump-to-current control at (always along the
    // bottom edge). The two only stack vertically when both share the same horizontal anchor -
    // see FOLLOW_CHIP_POSITION.
    public static final Setting<String> SKIP_CHIP_POSITION = enumSetting(true,
            "lyric_skip_chip_position", INTERNAL, "Skip chip position", ChipPosition.class
    );

    // Which side the "Follow lyrics" jump-to-current chip floats along the bottom edge at.
    // Edited from the layout editor's Follow-lyrics element; hidden from the normal settings
    // panel so the editor remains the single place a user can move this chip visually.
    public static final Setting<String> FOLLOW_CHIP_POSITION = enumSetting(true,
            "lyric_follow_chip_position", INTERNAL, "Follow-lyrics chip position", ChipPosition.class
    );

    // How the "Follow lyrics" chip presents itself - same three states as SKIP_CHIP_STYLE
    // (a plain icon, a permanently-labelled pill, or Auto: labelled pill that collapses to the
    // icon after a few seconds). Edited from the layout editor's Follow-lyrics element.
    public static final Setting<String> FOLLOW_CHIP_STYLE = enumSetting(false,
            "lyric_follow_chip_style", INTERNAL, "Follow-lyrics chip style", ChipStyle.class
    );

    // Which glyph the "Follow lyrics" chip shows - independent of FOLLOW_CHIP_STYLE above
    // (that one owns Icon/Label/Auto presentation; this one owns which icon). Adaptive arrow
    // (default) points down when the viewport sits above the current lyric target and up when
    // below; Static arrow keeps the old downward arrow; Waveform keeps the audio-bars glyph.
    // Edited from the layout editor's Follow-lyrics element.
    public static final Setting<String> FOLLOW_CHIP_ICON = enumSetting(false,
            "lyric_follow_chip_icon", INTERNAL, "Follow-lyrics chip icon", FollowChipIcon.class
    );

    // Adds a button to Spotify's persistent mini player (every non-lyrics screen) that jumps
    // straight to the native fullscreen lyrics - see LyricsActivityTakeoverHook.
    public static final Setting<Boolean> MINI_PLAYER_LYRICS_ICON = boolSetting(false,
            "mini_player_lyrics_icon", LYRICS, "Show lyrics icon on mini player", true
    );

    // Which orientations keep the status bar hidden on the lyrics screen. A swipe from the
    // edge still shows it for a moment. Migrated from the portrait/landscape bool pair, so
    // existing choices carry over.
    public static final Setting<String> STATUS_BAR_HIDDEN_MODE = enumSetting(false,
            "lyrics_status_bar_hidden_mode", LYRICS, "Hide status bar",
            StatusBarHiddenMode.class
    );

    static final String LEGACY_STATUS_BAR_HIDDEN_PORTRAIT = "lyrics_status_bar_hidden_portrait";
    static final String LEGACY_STATUS_BAR_HIDDEN_LANDSCAPE = "lyrics_status_bar_hidden_landscape";

    // Holding a lyric line opens a share card for it (see LyricsShareCardController). Off by
    // default: long-pressing the lyrics did nothing before, and a held finger while reading
    // should not start throwing up sheets unexpectedly.
    public static final Setting<Boolean> LONG_PRESS_SHARE = boolSetting(false,
            "lyrics_long_press_share", LYRICS, "Long-press a line to share", false
    );

    public static final IntegerSetting SYNC_OFFSET_MS = intSetting(false,
            "lyric_sync_offset_ms", LYRICS, "Sync offset",
            0, -5000, 5000, 100
    );

    // Publishes lyric state to the owner's HyperGlow companion over its bridge service, and adds
    // the background polling demand that does so while Spotify UI is idle. Off by default, so a
    // HyperGlow install never turns the bridge on without a stored choice.
    public static final Setting<Boolean> HYPERGLOW_ENABLED = boolSetting(false,
            "lyrics_hyper_aod_lyrics_enabled", LYRICS, "Publish lyrics to HyperGlow", false
    );

    /** Automatic lyric source arbitration mode shared by fullscreen and now-playing. */
    public static final Setting<String> LYRICS_SOURCE_MODE = enumSetting(false,
            "lyrics_source_selection_mode", LYRICS_SOURCES, "Lyrics source selection",
            LyricsSourceMode.class
    );

    /** Experimental strict source switch. "Spicy" is a retired legacy alias for Apple Music. */
    public static final Setting<String> LYRICS_SOURCE_OVERRIDE = enumSetting(false,
            "lyrics_source_override", LYRICS_SOURCES, "Lyrics source",
            LyricsSourceOverride.class
    );

    /** Composite marker only. The personal client key lives in SpicyOrgKeyStore. */
    public static final Setting<String> SPICY_ORG_CLIENT_KEY = stringSetting(false,
            "lyrics_spicy_org_client_key", LYRICS_SOURCES, "SpicyLyrics.org client key", ""
    );

    /** JSON array of source ids, persisted in the desktop-compatible order. */
    public static final Setting<String> LYRICS_SOURCE_ORDER = stringSetting(false,
            "lyrics_source_order", LYRICS_SOURCES, "Lyrics source order", "managed"
    );

    /** Search the original song title for karaoke/off-vocal releases on search-based sources. */
    public static final Setting<Boolean> KARAOKE_ORIGINAL_LYRICS = boolSetting(false,
            "lyrics_karaoke_original_lyrics", LYRICS_SOURCES,
            "Show original lyrics for karaoke versions", false
    );

    /** Bounded JSON map of spotify track URI to source id; auto is represented by omission. */
    public static final Setting<String> LYRICS_SOURCE_OVERRIDES = internalSetting(false,
            "lyrics_source_overrides", "Per-track lyric sources", "{}"
    );

    // Stored values are the exact display labels; allocation is in CacheStoragePolicy.
    public static final StringSetting CACHE_SIZE =
            (StringSetting) enumSetting(false,
                    "cache_size", LYRICS_SOURCES, "Cache size limit", "128 MB",
                    "32 MB", "128 MB", "512 MB", "1024 MB", "No limit"
            );

    // --- Now Playing ---
    public static final Setting<String> LIVE_CARD_TAP_MODE = enumSetting(false,
            "lyrics_live_card_tap_mode", LYRICS_SCREEN, "Tap card to open lyrics",
            LiveCardTapMode.class
    );

    public static final Setting<String> LIVE_CARD_TAP_TARGET = enumSetting(false,
            "lyrics_live_card_tap_target", LYRICS_SCREEN, "Card tap target",
            LiveCardTapTarget.class
    );

    public static final Setting<String> LIVE_CARD_WEIGHT = enumSetting(false,
            "lyrics_live_card_weight", INTERNAL, "Lyric weight",
            TextWeight.class
    );

    public static final Setting<String> LIVE_CARD_TEXT_SIZE = enumSetting(false,
            "lyrics_live_card_text_size", INTERNAL, "Text size",
            TextSizeMode.class
    );

    // Multiplier x100 for the live card's "custom" text size mode (0.0-5.0 in 0.05 steps).
    public static final IntegerSetting LIVE_CARD_TEXT_SIZE_CUSTOM = intSetting(false,
            "lyrics_live_card_text_size_custom", INTERNAL, "Custom size",
            100, 0, 500, 5
    );

    public static final Setting<String> LIVE_CARD_SECONDARY_MODE = enumSetting(false,
            "lyrics_live_card_secondary_mode", INTERNAL, "Extra line",
            SecondaryTextMode.class
    );

    public static final Setting<String> LIVE_CARD_ANIMATION = enumSetting(false,
            "lyrics_live_card_animation", INTERNAL, "Animation",
            LiveCardAnimation.class
    );

    public static final Setting<String> LIVE_CARD_GLOW = enumSetting(false,
            "lyrics_live_card_glow", INTERNAL, "Glow",
            LiveCardGlow.class
    );

    public static final Setting<String> LIVE_CARD_LINE_SYNC_FILL = enumSetting(false,
            "lyrics_live_card_line_sync_fill", INTERNAL, "Fill direction",
            LineSyncFill.TOP_TO_BOTTOM.storedLabel(),
            LineSyncFill.TOP_TO_BOTTOM.storedLabel(),
            LineSyncFill.BLOCK.storedLabel(),
            LineSyncFill.SENTENCE.storedLabel()
    );

    public static final Setting<String> LIVE_CARD_OVERFLOW = enumSetting(false,
            "lyrics_live_card_overflow", INTERNAL, "Overflow",
            LiveCardOverflow.class
    );

    public static final Setting<String> LIVE_CARD_SCROLL_SCOPE = enumSetting(false,
            "lyrics_live_card_scroll_scope", INTERNAL, "Scroll scope",
            LiveCardScrollScope.class
    );

    public static final Setting<String> LIVE_CARD_TRANSITION = enumSetting(false,
            "lyrics_live_card_transition", INTERNAL, "Transition",
            LiveCardTransition.class
    );

    // --- Text ---
    public static final Setting<Boolean> ADAPTIVE_SECTIONING = boolSetting(false,
            "lyric_adaptive_sectioning", INTERNAL, "Adaptive sectioning", true
    );

    // Scales the vertical gap between lyric rows (sentences); wrapped lines inside one sentence
    // keep a fixed 1.18 line-height (LyricsTextFactory).
    public static final Setting<String> LINE_SPACING = enumSetting(true,
            "line_spacing", INTERNAL, "Sentence spacing",
            LineSpacingMode.class
    );

    // Multiplier x100 for the "custom" line spacing mode (0.0-5.0 in 0.1 steps).
    public static final IntegerSetting LINE_SPACING_CUSTOM = intSetting(true,
            "line_spacing_custom", INTERNAL, "Custom spacing",
            150, 0, 500, 5
    );

    // Lyric font weight (Spotify's own faces): "Medium" (default) = spotify_mix_ui_bold,
    // "Bold" = the heavy title-extrabold (was the old default — too thick for some), "Regular".
    public static final Setting<String> LYRICS_WEIGHT = enumSetting(false,
            "lyrics_weight", INTERNAL, "Lyric weight",
            TextWeight.class
    );

    // Stored value "default" (the old alias for the Spotify font) coerces to "spotify" via the
    // allowed-values check, so existing configs migrate silently.
    public static final Setting<String> LYRICS_FONT = enumSetting(false,
            "lyrics_font", INTERNAL, "Lyric font",
            LyricsFont.class
    );

    // Absolute file path to a user-supplied .ttf/.otf, used when LYRICS_FONT == "custom". Entered
    // by hand (see PanelDialogs#promptLyricsFontPath) rather than a system file picker - this
    // module has no Activity of its own to receive a picker result from inside Spotify's process.
    // A custom font is validated on save (see LyricsFontValidator) so the user is warned up front
    // about which of the app's supported scripts it doesn't cover; unsupported scripts still fall
    // back correctly at render time regardless (LyricsTextFactory's per-script fallback chain).
    public static final Setting<String> LYRICS_FONT_CUSTOM_PATH = stringSetting(false,
            "lyrics_font_custom_path", INTERNAL, "Custom font file", ""
    );

    public static final Setting<String> LYRICS_TEXT_SIZE = enumSetting(true,
            "lyrics_text_size", INTERNAL, "Lyric text size",
            TextSizeMode.class
    );

    // Multiplier x100 for the "custom" text size mode (0.0-5.0 in 0.1 steps).
    public static final IntegerSetting LYRICS_TEXT_SIZE_CUSTOM = intSetting(true,
            "lyrics_text_size_custom", INTERNAL, "Custom size",
            100, 0, 500, 5
    );

    // When on, long lines shrink (23-28sp by length) so they fit; when off, every line
    // uses the same base size and long lines wrap instead.
    public static final Setting<Boolean> LYRICS_ADAPTIVE_TEXT_SIZE = boolSetting(false,
            "lyrics_adaptive_text_size", INTERNAL, "Adaptive text size", true
    );

    public static final Setting<String> INTERLUDE_ICON = enumSetting(false,
            "lyric_interlude_icon", INTERNAL, "Interlude indicator", InterludeIcon.class
    );

    public static final Setting<String> LIKED_SONGS_BUTTON = enumSetting(false,
            "lyric_liked_songs_button", INTERNAL, "Add to Liked Songs button", LikedSongsButton.class
    );

    // Which edge the top chrome cluster (transliteration/translation/like/settings) and the Back
    // control anchor to. Internal order among the cluster's own icons is unaffected - this only
    // mirrors which side of the header they sit on (or which vertical rail, in Top mode).
    // The layout editor is the only place this is positioned; the standard panel intentionally
    // does not render it as a regular setting row.
    public static final Setting<String> CHROME_CLUSTER_POSITION = enumSetting(true,
            "lyrics_chrome_cluster_position", INTERNAL, "Top controls position",
            ChromeClusterPosition.class
    );

    /** Top controls as a vertical rail or a horizontal row. Auto keeps the rail with a Top readout
     *  and the row otherwise; each orientation keeps its own value. */
    public static final Setting<String> CHROME_CLUSTER_LAYOUT = enumSetting(true,
            "lyrics_chrome_cluster_layout", INTERNAL, "Top controls direction",
            ChromeClusterLayout.class
    );

    public static final Setting<Boolean> SHOW_FULLSCREEN_BACK_BUTTON = boolSetting(false,
            "lyrics_show_fullscreen_back_button", INTERNAL, "Show back button", true
    );

    static final String LEGACY_SHOW_SAVE_BUTTON = "lyric_show_save_button";
    static final String LEGACY_SAVE_BUTTON_ICON = "lyric_save_button_icon";

    // Stored as a string to preserve the existing preference key and legacy values while allowing
    // every whole-second timeout from 1 through 30. Canonical values are "1 second", "N seconds",
    // and "Always"; LyricsShellSettings also accepts the former "Always on" value.
    public static final Setting<String> FULLSCREEN_CONTROLS = stringSetting(false,
            "lyrics_fullscreen_controls", TEXT, "Fullscreen controls", "Always"
    );

    // Where the active lyric line rests vertically in the viewport. Auto keeps the existing
    // behavior (raised when the Apple-style line-slide animation is on and the screen is
    // portrait, center otherwise); Top/Center/Bottom pin it explicitly regardless of that
    // animation setting; Custom unlocks LYRICS_FOCUS_POSITION_CUSTOM_PERCENT (set by the layout
    // editor's focus-point drag handle). See LyricsScrollController's anchor fractions.
    public static final Setting<String> LYRICS_FOCUS_POSITION = enumSetting(true,
            "lyrics_focus_position", INTERNAL, "Lyrics focus point",
            LyricsFocusPosition.class
    );

    // 0 = top edge, 100 = bottom edge, for LYRICS_FOCUS_POSITION == "Custom".
    public static final IntegerSetting LYRICS_FOCUS_POSITION_CUSTOM_PERCENT = intSetting(true,
            "lyrics_focus_position_custom_percent", INTERNAL, "Custom focus point",
            50, 0, 100, 5
    );
    // Position of the fullscreen track-info readout (artwork + title/artist). Off hides the
    // readout, its metadata, and its artwork gestures; back, config toggles, and the floating
    // cluster stay. New-feature rule: default Off for all installs, no migration. This is a
    // layout-editor-only control and should not appear in the standard settings panel.
    public static final Setting<String> TRACK_INFO_POSITION = enumSetting(true,
            "lyrics_track_info_position", INTERNAL, "Track info position", TrackInfoPosition.class
    );

    // What sits behind the readout. Gradient is the original edge scrim, which lets lyrics
    // show through the dock; Solid fills the dock so nothing reads through it; None draws nothing.
    // Editable from the layout editor's Track text element.
    public static final Setting<String> TRACK_INFO_BACKGROUND = enumSetting(true,
            "lyrics_track_info_background", INTERNAL, "Track info background", TrackInfoBackground.class
    );

    // Readout title/artist size. Applies live; default Normal matches the original readout.
    public static final Setting<String> TRACK_INFO_TEXT_SIZE = enumSetting(true,
            "lyrics_track_info_text_size", INTERNAL, "Track info text size", TrackInfoTextSize.class
    );

    // Multiplier x100 for the readout's "Custom" text size mode (0.5-4.0 in 0.05 steps).
    // 100 = Normal (title 15sp, artist 12sp). Applies live.
    public static final IntegerSetting TRACK_INFO_TEXT_SIZE_CUSTOM = intSetting(true,
            "lyrics_track_info_text_size_custom", INTERNAL, "Custom size",
            100, 50, 400, 5
    );

    // How long track title/artist text behaves when it does not fit the readout width.
    // Clip = single line with end ellipsis; Wrap = up to two lines with end ellipsis;
    // Scroll = single-line marquee. Applies live to top, bottom, and side readouts.
    public static final Setting<String> TRACK_INFO_TEXT_OVERFLOW = enumSetting(true,
            "lyrics_track_info_text_overflow", INTERNAL, "Track info overflow", TrackInfoTextOverflow.class
    );

    // Readout artwork size (bottom = value, top portrait = value − 24; landscape top stays 54dp
    // for test-build parity; the side panel is container-driven and unaffected). Default Normal.
    // Custom unlocks TRACK_INFO_ART_SIZE_CUSTOM_DP (set by the layout editor's resize handle).
    public static final Setting<String> TRACK_INFO_ART_SIZE = enumSetting(true,
            "lyrics_track_info_art_size", INTERNAL, "Track info art size", TrackInfoArtSize.class
    );

    // Art dp for TRACK_INFO_ART_SIZE == "Custom", set by the layout editor's corner grip. The
    // readout placements clamp it to TrackInfoReadoutController#READOUT_MAX_ART_DP; the two-column
    // cover can grow up to the room its column has, so the stored range is wider than either.
    public static final IntegerSetting TRACK_INFO_ART_SIZE_CUSTOM_DP = intSetting(true,
            "lyrics_track_info_art_size_custom_dp", INTERNAL, "Custom art size",
            96, 48, 400, 4
    );

    // Corner radius (dp) for the readout artwork and its dock/scrim, in every placement
    // (top/bottom/side). Applies live.
    public static final IntegerSetting TRACK_INFO_ART_RADIUS = intSetting(true,
            "lyrics_track_info_art_radius", INTERNAL, "Track info art corner radius",
            0, 0, 32, 2
    );

    // Vertical alignment of the title/artist text block within its row, for Top/Bottom/Header
    // placements (Side stacks text below the artwork instead of beside it, so this has no effect
    // there). Default Center matches the readout's original hardcoded behavior.
    public static final Setting<String> TRACK_INFO_TEXT_ALIGN = enumSetting(true,
            "lyrics_track_info_text_align", INTERNAL, "Track info text alignment", TrackInfoTextAlign.class
    );

    // When on, title/artist text size is derived proportionally from the current artwork size
    // (TRACK_INFO_ART_SIZE / TRACK_INFO_ART_SIZE_CUSTOM_DP) instead of TRACK_INFO_TEXT_SIZE's
    // manual value - so dragging the layout editor's resize handle scales the text along with the
    // artwork. See TrackInfoReadoutController#applyTextSize().
    // Which fields the track info readout renders, independent of position/size. Album is off by
    // default (it previously had no display path on the lyrics screen at all outside the
    // landscape two-column left panel). Editable from the layout editor's Track text element.
    public static final Setting<Boolean> TRACK_INFO_SHOW_TITLE = boolSetting(true,
            "lyrics_track_info_show_title", INTERNAL, "Show title", true
    );

    public static final Setting<Boolean> TRACK_INFO_SHOW_ARTIST = boolSetting(true,
            "lyrics_track_info_show_artist", INTERNAL, "Show artist", true
    );

    public static final Setting<Boolean> TRACK_INFO_SHOW_ALBUM = boolSetting(true,
            "lyrics_track_info_show_album", INTERNAL, "Show album", false
    );

    public static final Setting<Boolean> TRACK_INFO_TEXT_SIZE_ADAPTIVE = boolSetting(true,
            "lyrics_track_info_text_size_adaptive", INTERNAL, "Adaptive track info text size", false
    );

    // Separate landscape mode from the Off/Top/Bottom readout: when on and the screen is
    // genuinely wide (landscape with width/height >= 1.2, so near-square foldable screens stay
    // stacked), the fullscreen lyrics use a two-column layout with an artwork panel on the
    // left and the lyrics column on the right. The readout overlays stand down while it is
    // engaged. Applies live, including while the layout editor is open.
    public static final Setting<Boolean> ADAPTIVE_LANDSCAPE_LAYOUT = boolSetting(false,
            "lyrics_adaptive_landscape_layout", INTERNAL, "Two-column on wide screens", true
    );

    // Media controls for artwork (two-column panel + readout art, same behavior): Off
    // disables tap gestures and swipe; Single tap opens the play/pause overlay on tap;
    // Double tap toggles play/pause directly with a brief icon pulse, skipping the overlay.
    // Applies live, no reopen needed. Stored booleans migrate in SettingsStore.
    public static final Setting<String> PANEL_MEDIA_CONTROLS = enumSetting(false,
            "lyrics_panel_media_controls", INTERNAL, "Panel media controls", PanelMediaMode.class
    );

    // --- Animation ---
    // "Gradient wash" = the karaoke fill sweeps each line (classic Spicy look).
    // "Spotlight" = no fill; the active line/word zooms + glows instead (gradient direction ignored).
    // "Apple Music" = Apple-owned motion/blur/fade stack below; every Apple sub-setting applies
    // only while this style is selected and no shared key is ever rewritten (no preset flips).
    public static final Setting<String> ANIMATION_STYLE = enumSetting(true,
            "lyric_animation_style", ANIMATION, "Animation style",
            AnimationStyle.class
    );

    // Chrome transition pacing, separate from lyric playback timing. Instant skips UI
    // transitions (fastest, least motion); Fast halves the standard durations; Relaxed
    // keeps full durations. Playback-driven highlighting never consults this setting.
    public static final Setting<String> TRANSITION_FEEL = enumSetting(false,
            "lyrics_transition_feel", ANIMATION, "Transition feel",
            TransitionFeel.class
    );

    // Apple-owned sub-section (R3). Visible only while ANIMATION_STYLE is Apple Music; each key
    // is read only under that style, so switching styles never migrates or resets user values.
    public static final Setting<Boolean> APPLE_FADE_PASSED_LINES = boolSetting(true,
            "lyric_apple_fade_passed_lines", INTERNAL, "Fade passed lines", true
    );

    public static final Setting<Boolean> APPLE_COMPACT_TEXT = boolSetting(false,
            "lyric_apple_compact_text", APPLE, "Apple-style text proportions", true
    );

    // Row-scroll cascade. Apple-owned: rendered only inside the Apple sub-section.
    public static final Setting<Boolean> LINE_SLIDE_ANIMATION = boolSetting(true,
            "lyric_line_slide_animation", INTERNAL, "Apple Music-style slide", false
    );

    // Apple-owned lift motion. This is the only Apple lift entry: WORD_BOUNCE_STYLE deliberately
    // carries no competing "Apple lift" value; the renderer reads this key under Apple Music.
    public static final Setting<Boolean> APPLE_LIFT = boolSetting(true,
            "lyric_apple_lift", INTERNAL, "Apple lift", true
    );

    // Apple-owned: a one-shot reveal for the first render of a freshly loaded document (opening
    // the lyrics screen, or a track/source change) - rows rise up from below and fade in instead
    // of appearing instantly. Distinct from LINE_SLIDE_ANIMATION, which is the per-scroll-step
    // cascade; this plays once per document, not on every active-line change.
    public static final Setting<Boolean> LOAD_LIFT_ANIMATION = boolSetting(true,
            "lyric_load_lift_animation", INTERNAL, "Rise in on load", false
    );

    // Speed multiplier for row cascade and load-lift animations (100 = normal, 50 = half,
    // 200 = double). Applies to all animation styles.
    public static final IntegerSetting APPLE_CASCADE_SPEED = intSetting(true,
            "apple_cascade_speed", INTERNAL, "Slide speed", 100, 50, 200, 5
    );

    public static final IntegerSetting APPLE_SPRING_STRENGTH = intSetting(true,
            "apple_spring_strength", INTERNAL, "Spring strength", 100, 50, 200, 5
    );

    // One selector owns both the bounce gate and its scope.
    public static final Setting<String> WORD_BOUNCE = enumSetting(true,
            "lyric_word_bounce_mode", INTERNAL, "Word bounce",
            WordBounceMode.class
    );

    public static final Setting<String> WORD_BOUNCE_STYLE = enumSetting(true,
            "lyric_word_bounce_style", INTERNAL, "Bounce style",
            WordBounceStyle.WORD_ZOOM.storedLabel(), WordBounceStyle.class
    );


    public static final Setting<Boolean> ENABLE_GLOW_BLUR = boolSetting(true,
            "lyric_enable_glow_blur", INTERNAL, "Text glow", true
    );

    // One blur strength replaces the retired Off/Slight/Heavy selector and its separate
    // intensity percentage. 0 is off. 1-250 is the standard distance curve at that percent;
    // 251-500 is the legacy "Slight" distance curve at (value - 250) percent. The two bands
    // exist because the legacy selector chose a different distance curve, not a different
    // magnitude: no single percentage renders both, so the stored value carries the curve
    // and every migrated pair keeps rendering exactly as it did. See BlurStrength.
    public static final IntegerSetting LYRICS_BLUR_STRENGTH = intSetting(true,
            "lyrics_blur_strength", INTERNAL, "Blur distant lines", 0, 0, 500, 5
    );

    // Direction the karaoke gradient fills each line as it plays. Applies under "Gradient wash".
    public static final Setting<String> LINE_SYNC_FILL = enumSetting(true,
            "lyric_line_sync_fill", INTERNAL, "Lyric fill direction",
            LineSyncFill.SENTENCE.storedLabel(), LineSyncFill.class
    );

    // --- Background ---
    public static final Setting<String> BACKGROUND_STYLE = enumSetting(false,
            "lyric_background_style", INTERNAL, "Background style",
            BackgroundStyle.class
    );

    public static final Setting<String> BACKGROUND_EFFECT = enumSetting(false,
            "lyric_background_effect", INTERNAL, "Background effect",
            BackgroundEffect.defaultStableId(),
            BackgroundEffect.allowedLabels().toArray(new String[0]));
    public static final Setting<String> BACKGROUND_RANDOM_EFFECTS = stringSetting(false,
            "lyric_background_random_effects", INTERNAL, "Effects in Random",
            BackgroundEffectPool.DEFAULT.encode());

    // One darkening value replaces the retired force switch and its separate level: 0 is off,
    // 1-100 darkens the background by that percent. See LyricsAmbientController#backgroundBrightness.
    public static final IntegerSetting LYRICS_DARKENING = intSetting(false,
            "lyrics_darkening", INTERNAL, "Darken background", 35, 0, 100, 5
    );

    // --- Romanization (transliteration controls) ---
    public static final Setting<Boolean> TRANSLITERATION_ENABLED = boolSetting(false,
            "lyrics_transliteration_enabled", TRANSLITERATION, "Transliterate lyrics", false
    );

    // Global romanization layout — aligned under each word (great for language learners comparing
    // word-by-word) vs a single line. Applies to every romanizable script, not just one language.
    public static final Setting<Boolean> ALIGNED_PER_WORD_ROMAJI = boolSetting(false,
            "lyric_aligned_per_word_romaji", TRANSLITERATION, "Attach transliteration under each word", false
    );

    // "cycle" = the in-screen chip cycles the modes on tap; a fixed value locks to that mode.
    public static final Setting<String> JAPANESE_READING_MODE = enumSetting(false,
            "lyrics_japanese_reading_mode", TRANSLITERATION, "Japanese reading",
            JapaneseReadingMode.class
    );

    // Design controls for the furigana reading itself (color/position), separate from
    // JAPANESE_READING_MODE (which reading mode is active at all - a function choice, not a
    // design one). See FuriganaText#applySettings. Default 59% reproduces the previous hardcoded
    // gray (150,150,150).
    public static final IntegerSetting FURIGANA_BRIGHTNESS = intSetting(false,
            "lyrics_furigana_brightness", TRANSLITERATION, "Furigana brightness", 59, 20, 100, 5
    );

    // Percent scale on the gap between the furigana reading and the kanji it annotates (100 =
    // the previous fixed spacing). Below 100 pulls the reading closer; above pushes it further up.
    public static final IntegerSetting FURIGANA_POSITION_PERCENT = intSetting(false,
            "lyrics_furigana_position_percent", TRANSLITERATION, "Furigana position", 100, 40, 200, 10
    );

    public static final Setting<String> CHINESE_MODE = enumSetting(false,
            "lyrics_chinese_mode", TRANSLITERATION, "Chinese reading",
            ChineseMode.class
    );

    public static final Setting<String> CHINESE_CHARACTER_FORM = enumSetting(false,
            "lyrics_chinese_character_form", TRANSLITERATION, "Convert Chinese characters",
            "original", "original", "simplified", "traditional"
    );

    public static final Setting<String> KOREAN_ROMANIZATION = enumSetting(false,
            "lyrics_korean_romanization", TRANSLITERATION, "Korean reading",
            KoreanDisplayMode.RR_STANDARD.value,
            KoreanDisplayMode.OFF.value,
            KoreanDisplayMode.RR_STANDARD.value,
            KoreanDisplayMode.WORD_TRANSLIT.value,
            KoreanDisplayMode.RR_PRONUNCIATION.value,
            KoreanDisplayMode.VN_PRONUNCIATION.value,
            "cycle"
    );

    // On = Mandarin pinyin tone marks (zhōng guó) + Cantonese jyutping tone numbers (nei5).
    // Off (default) = no tone marks / no jyutping tone numbers — cleaner lyric display.
    public static final Setting<Boolean> CHINESE_TONES = boolSetting(false,
            "lyrics_chinese_tones", TRANSLITERATION, "Show Chinese tones", false
    );

    // Cyrillic source language — shared glyphs differ (Russian г→g, и→i vs Ukrainian г→h, и→y),
    // so one global map can't serve both (see SpicyRomanizer / ROMANIZATION_AUDIT_BACKLOG CY-4).
    public static final Setting<String> CYRILLIC_MODE = enumSetting(false,
            "lyrics_cyrillic_mode", TRANSLITERATION, "Cyrillic reading",
            CyrillicMode.class
    );

    // Off (default) = drop ь/ъ for readability; On = keep them as BGN/PCGN prime marks (ʹ/ʺ).
    public static final Setting<Boolean> CYRILLIC_KEEP_SIGNS = boolSetting(false,
            "lyrics_cyrillic_keep_signs", TRANSLITERATION, "Keep Cyrillic soft/hard signs", false
    );

    // --- Translation ---
    public static final Setting<Boolean> TRANSLATION_ENABLED = boolSetting(false,
            "lyrics_translation_enabled", TRANSLATION, "Translate lyrics", false
    );

    public static final Setting<String> TRANSLATION_BACKEND = internalEnumSetting(false,
            "lyrics_translation_backend", "Translation backend", TranslationBackend.class
    );

    public static final Setting<String> TRANSLATION_TARGET = enumSetting(false,
            "lyrics_translation_target", TRANSLATION, "Target language",
            "en",
            "en", "es", "fr", "de", "it", "pt", "nl", "sv", "no", "da",
            "fi", "pl", "cs", "sk", "hu", "ro", "el", "tr", "uk", "ru",
            "ja", "ko", "zh", "zh-TW", "th", "vi", "id", "ms", "hi", "bn",
            "ta", "ar", "he", "fa"
    );

    public static final Setting<String> TRANSLATION_BRIGHTNESS = enumSetting(false,
            "lyrics_translation_brightness", TRANSLATION, "Translation brightness",
            TranslationBrightness.class
    );

    // --- AI ---
    // One gate owns the whole family: off means no requests and no cached AI overlay, while every
    // paid result stays exactly where it is, waiting to be switched back on.
    public static final Setting<Boolean> AI_ENABLED = boolSetting(false,
            "ai_enabled", AI, "AI features", false
    );

    public static final Setting<String> AI_PROVIDER = enumSetting(false,
            "ai_provider", AI, "Provider", AiProviderChoice.class
    );

    public static final Setting<String> AI_DEEPSEEK_REASONING = enumSetting(false,
            "ai_deepseek_reasoning", AI, "DeepSeek reasoning",
            AiDeepSeekReasoning.class
    );

    public static final Setting<String> AI_TRANSLATION_MODE = enumSetting(false,
            "ai_translation_mode", AI, "AI translation trigger",
            AiTranslationMode.class
    );

    // Three Meaning display flows sharing one stored key: preview shows Google while a raw-lyrics
    // AI request runs, draft sends Google to the model for refinement, AI-only never asks Google.
    // "Google draft" stays the default until device comparison proves otherwise, and the two older
    // values must keep coercing so an existing choice never silently changes paid-request input.
    public static final Setting<String> AI_TRANSLATION_PIPELINE = enumSetting(false,
            "ai_translation_pipeline", AI, "AI translation flow",
            "Google draft", "Google preview", "Google draft", "AI only"
    );

    public static final Setting<String> AI_PRONUNCIATION_MODE = enumSetting(false,
            "ai_pronunciation_mode", AI, "AI pronunciation trigger",
            AiPronunciationMode.class
    );

    public static final Setting<String> AI_PRONUNCIATION_SOURCE = enumSetting(false,
            "ai_pronunciation_source", AI, "AI pronunciation pipeline",
            AiPronunciationSource.class
    );

    public static final Setting<String> AI_BUTTON_BEHAVIOR = enumSetting(false,
            "ai_button_behavior", AI, "Translation button",
            AiButtonBehavior.class
    );

    // The explicit button opens lyrics in PiP. Closing or backing out keeps its normal behavior.
    // Off by default; the rest of the PiP section applies only while this is on.
    public static final Setting<Boolean> PIP_ENABLED = boolSetting(false,
            "lyrics_pip_enabled", PIP, "Show picture-in-picture button", false
    );

    // Window shape - see pipShapeRatio().
    public static final Setting<String> PIP_SHAPE = enumSetting(false,
            "lyrics_pip_shape", PIP, "Window shape", PipShape.class
    );

    /** Width and height of a PIP_SHAPE value (the window and its layout share them). */
    public static int[] pipShapeRatio(String shape) {
        return StoredValues.fromStored(PipShape.class, shape).ratio();
    }

    // Previous / play-pause / next in the window's own controls.
    public static final Setting<Boolean> PIP_CONTROLS = boolSetting(false,
            "lyrics_pip_controls", PIP, "Playback controls", true
    );

    // Where the current line rests: the lyrics screen's focus position, taken as a height on the
    // window, or the middle of the lyrics area.
    public static final Setting<String> PIP_FOCUS = enumSetting(false,
            "lyrics_pip_focus", PIP, "Current line position", PipFocus.class
    );

    // Starting PiP sends Spotify to the background, back to the app used before it.
    public static final Setting<Boolean> PIP_LEAVE_SPOTIFY = boolSetting(false,
            "lyrics_pip_leave_spotify", PIP, "Return to the previous app", true
    );

    // Album artwork in PiP. Off by default: every PiP shape is lyrics-only, with no
    // metadata and no blank column. On restores the former landscape artwork + metadata
    // column only; portrait stays lyrics-only per the existing PiP layout contract.
    public static final Setting<Boolean> PIP_ALBUM_ART = boolSetting(false,
            "lyrics_pip_album_art", PIP, "Show album art", false
    );

    // ===================== INTERNAL (fixed defaults, not shown) =====================

    // Legacy composer-owned flag. New builds persist AI_TRANSLATION_PIPELINE; this remains only so
    // an existing explicit choice can migrate without silently changing paid-request input.
    public static final Setting<Boolean> AI_TRANSLATION_REFINE_GOOGLE = internalBoolSetting(false,
            "ai_translation_refine_google", "Refine Google translation with AI", false
    );

    // Chosen explicitly by the owner from live discovery, never picked for them: a model swapped
    // underneath would change output and quietly invalidate every paid result keyed to the old one.
    public static final Setting<String> AI_MODEL = internalSetting(false,
            "ai_model", "AI model", ""
    );

    /** Kept per provider choice: Custom and official OpenAI can legitimately use different keys/models. */
    public static final Setting<String> AI_MODEL_GEMINI = internalSetting(false,
            "ai_model_gemini", "Gemini AI model", ""
    );

    public static final Setting<String> AI_MODEL_OPENAI = internalSetting(false,
            "ai_model_openai", "OpenAI AI model", ""
    );

    public static final Setting<String> AI_MODEL_OPENROUTER = internalSetting(false,
            "ai_model_openrouter", "OpenRouter AI model", ""
    );

    public static final Setting<String> AI_MODEL_DEEPSEEK = internalSetting(false,
            "ai_model_deepseek", "DeepSeek AI model", ""
    );

    public static final Setting<String> AI_MODEL_CUSTOM = internalSetting(false,
            "ai_model_custom", "Custom AI model", ""
    );

    public static final Setting<String> AI_INSTRUCTIONS_MEANING = internalSetting(false,
            "ai_instructions_meaning", "AI translation instructions", ""
    );

    public static final Setting<String> AI_INSTRUCTIONS_SOUND = internalSetting(false,
            "ai_instructions_sound", "AI pronunciation instructions", ""
    );

    // The active instruction may be one of the built-in presets. Keep the owner's custom text in
    // a separate slot so switching presets never destroys prompt work they expect to find later.
    public static final Setting<String> AI_CUSTOM_INSTRUCTIONS_MEANING = internalSetting(false,
            "ai_custom_instructions_meaning", "Custom AI translation instructions", ""
    );

    public static final Setting<String> AI_CUSTOM_INSTRUCTIONS_SOUND = internalSetting(false,
            "ai_custom_instructions_sound", "Custom AI pronunciation instructions", ""
    );

    // Normalized base URL for an OpenAI-compatible endpoint. Part of config identity, so switching
    // endpoints cannot serve another endpoint's answers.
    public static final Setting<String> AI_ENDPOINT = internalSetting(false,
            "ai_endpoint", "AI endpoint", ""
    );

    // Last structured-output probe per provider scope: endpoint host, model name, measured output
    // tokens, failure token. Written by the model test and the one-time readiness check; read by
    // the planner to size the reasoning headroom. Never holds a credential.
    public static final Setting<String> AI_PROBE_GEMINI = internalSetting(false,
            "ai_probe_gemini", "Gemini AI probe", ""
    );

    public static final Setting<String> AI_PROBE_OPENAI = internalSetting(false,
            "ai_probe_openai", "OpenAI AI probe", ""
    );

    public static final Setting<String> AI_PROBE_OPENROUTER = internalSetting(false,
            "ai_probe_openrouter", "OpenRouter AI probe", ""
    );

    public static final Setting<String> AI_PROBE_DEEPSEEK = internalSetting(false,
            "ai_probe_deepseek", "DeepSeek AI probe", ""
    );

    public static final Setting<String> AI_PROBE_CUSTOM = internalSetting(false,
            "ai_probe_custom", "Custom AI probe", ""
    );

    public static final Setting<String> DISPLAY_MODE = internalEnumSetting(false,
            "lyrics_display_mode", "Display mode",
            DisplayMode.class
    );

    // The native lyrics feature is always on — disabling it removes every entry path (button + this
    // very settings panel), so it's not a user choice.
    public static final Setting<Boolean> NATIVE_SPICY_ENABLED = internalBoolSetting(false,
            "native_spicy_enabled", "Enable native Spicy lyrics screen", true
    );

    public static final Setting<Boolean> SEND_TOKEN = internalBoolSetting(false,
            "lyrics_send_token", "Send token", true
    );

    public static final Setting<Boolean> NATIVE_SPICY_ROMANIZATION = internalBoolSetting(false,
            "native_spicy_romanization", "Enable Spicy romanization", false
    );

    public static final Setting<Boolean> NATIVE_SPICY_TRANSLATION = internalBoolSetting(false,
            "native_spicy_translation", "Enable Spicy translation", false
    );

    public static final Setting<Boolean> LIVE_CARD_SHOW_TRANSLITERATION = internalBoolSetting(false,
            "lyrics_live_card_show_transliteration", "Now-playing transliteration", false
    );

    public static final Setting<String> LAST_JAPANESE_CYCLE_MODE = internalEnumSetting(false,
            "lyrics_last_japanese_cycle_mode", "Last Japanese cycle mode",
            LastJapaneseCycleMode.class
    );

    public static final Setting<String> LAST_CHINESE_CYCLE_MODE = internalEnumSetting(false,
            "lyrics_last_chinese_cycle_mode", "Last Chinese cycle mode",
            LastChineseCycleMode.class
    );

    public static final Setting<String> LAST_KOREAN_CYCLE_MODE = internalEnumSetting(false,
            "lyrics_last_korean_cycle_mode", "Last Korean cycle mode",
            KoreanDisplayMode.RR_STANDARD.value,
            KoreanDisplayMode.RR_STANDARD.value,
            KoreanDisplayMode.WORD_TRANSLIT.value,
            KoreanDisplayMode.RR_PRONUNCIATION.value,
            KoreanDisplayMode.VN_PRONUNCIATION.value
    );

    public static final Setting<String> LAST_CYRILLIC_CYCLE_MODE = internalEnumSetting(false,
            "lyrics_last_cyrillic_cycle_mode", "Last Cyrillic cycle mode",
            LastCyrillicCycleMode.class
    );

    public static final Setting<String> SOURCE_LANGUAGE_MODE = internalEnumSetting(false,
            "lyrics_source_language_mode", "Source language mode",
            SourceLanguageMode.class
    );

    public static final Setting<String> SOURCE_LANGUAGE = internalEnumSetting(false,
            "lyrics_source_language", "Manual source language",
            "auto",
            "auto", "ja", "zh", "ko", "ru", "uk", "bg", "sr", "mk", "be",
            "el", "ar", "fa", "ur", "he", "th", "hi", "bn", "ta", "te",
            "ka", "hy", "am", "my", "km", "lo", "other"
    );

    public static final Setting<String> BACKGROUND_QUALITY = internalEnumSetting(false,
            "lyric_background_quality", "Render quality (background & blur)",
            BackgroundQuality.class
    );

    /** Removed user-facing boolean. Read only when migrating to {@link #BACKGROUND_STYLE}. */
    public static final Setting<Boolean> ENABLE_BACKGROUND = internalBoolSetting(false,
            "lyric_enable_background", "Legacy animated background", false
    );

    public static final Setting<Boolean> ENABLE_LINE_GRADIENT = internalBoolSetting(false,
            "lyric_enable_line_gradient", "Line gradient/glow", true
    );

    public static final Setting<Boolean> TOGGLE_PROGRESS_RING = internalBoolSetting(false,
            "lyric_toggle_progress_ring", "Progress ring on toggle buttons", true
    );

    public static final Setting<Boolean> SHOW_SKELETON = internalBoolSetting(false,
            "lyric_show_skeleton", "Skeleton placeholder while loading", true
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
        private final boolean orientationSpecific;

        Setting(boolean orientationSpecific, String key, Section section, String label, T defaultValue, List<T> allowedValues) {
            this.orientationSpecific = orientationSpecific;
            this.key = key;
            this.section = section;
            this.label = label;
            this.defaultValue = defaultValue;
            this.allowedValues = allowedValues != null ? Collections.unmodifiableList(allowedValues) : null;
            ALL.add(this);
        }

        /** Whether this setting keeps an independent landscape value. */
        public final boolean isOrientationSpecific() { return orientationSpecific; }

        public abstract T coerce(Object value);
    }

    public static final class BooleanSetting extends Setting<Boolean> {
        BooleanSetting(boolean orientationSpecific, String key, Section section, String label, Boolean defaultValue) {
            super(orientationSpecific, key, section, label, defaultValue, null);
        }

        @Override
        public Boolean coerce(Object value) {
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof String) return Boolean.parseBoolean((String) value);
            return defaultValue;
        }
    }

    public static final class StringSetting extends Setting<String> {
        StringSetting(boolean orientationSpecific, String key, Section section, String label, String defaultValue, List<String> allowedValues) {
            super(orientationSpecific, key, section, label, defaultValue, allowedValues);
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

        IntegerSetting(boolean orientationSpecific, String key, Section section, String label, Integer defaultValue,
                       int minValue, int maxValue, int stepValue) {
            super(orientationSpecific, key, section, label, defaultValue, null);
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

    private static Setting<Boolean> boolSetting(boolean orientationSpecific, String key, Section section, String label, boolean defaultValue) {
        return new BooleanSetting(orientationSpecific, key, section, label, defaultValue);
    }

    private static IntegerSetting intSetting(boolean orientationSpecific, String key, Section section, String label,
                                             int defaultValue, int minValue, int maxValue, int stepValue) {
        return new IntegerSetting(orientationSpecific, key, section, label, defaultValue, minValue, maxValue, stepValue);
    }

    private static Setting<Boolean> internalBoolSetting(boolean orientationSpecific, String key, String label, boolean defaultValue) {
        return new BooleanSetting(orientationSpecific, key, INTERNAL, label, defaultValue);
    }

    private static Setting<String> enumSetting(boolean orientationSpecific, String key, Section section, String label, String defaultValue, String... allowed) {
        return new StringSetting(orientationSpecific, key, section, label, defaultValue, Arrays.asList(allowed));
    }

    /**
     * A setting whose values are a stored-label enum: the enum owns the labels and the default,
     * so the schema and the code read one list.
     */
    private static <E extends Enum<E> & StoredValue> Setting<String> enumSetting(boolean orientationSpecific,
            String key, Section section, String label, Class<E> type) {
        return new StringSetting(orientationSpecific, key, section, label,
                StoredValues.defaultLabel(type), StoredValues.labels(type));
    }

    private static <E extends Enum<E> & StoredValue> Setting<String> enumSetting(boolean orientationSpecific,
            String key, Section section, String label, String defaultLabel, Class<E> type) {
        return new StringSetting(orientationSpecific, key, section, label,
                defaultLabel, StoredValues.labels(type));
    }

    private static Setting<String> stringSetting(boolean orientationSpecific, String key, Section section, String label, String defaultValue) {
        return new StringSetting(orientationSpecific, key, section, label, defaultValue, null);
    }

    private static Setting<String> internalEnumSetting(boolean orientationSpecific, String key, String label, String defaultValue, String... allowed) {
        return new StringSetting(orientationSpecific, key, INTERNAL, label, defaultValue, Arrays.asList(allowed));
    }

    /** An internal setting whose values are a stored-label enum. */
    private static <E extends Enum<E> & StoredValue> Setting<String> internalEnumSetting(boolean orientationSpecific,
            String key, String label, Class<E> type) {
        return new StringSetting(orientationSpecific, key, INTERNAL, label,
                StoredValues.defaultLabel(type), StoredValues.labels(type));
    }

    private static Setting<String> internalSetting(boolean orientationSpecific, String key, String label, String defaultValue) {
        return new StringSetting(orientationSpecific, key, INTERNAL, label, defaultValue, null);
    }

    /**
     * Where a landscape value of {@code setting} is stored, or null when the shared key applies
     * (portrait, or a setting that is not orientation-specific). Portrait keeps the plain key, so
     * existing values carry over; landscape follows portrait until it is changed while landscape.
     */
    public static String landscapeKey(android.content.Context context, Setting<?> setting) {
        if (context == null || !setting.isOrientationSpecific()) return null;
        try {
            return context.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    ? setting.key + "_ls" : null;
        } catch (RuntimeException noResources) {
            // JVM fakes and exotic host contexts cannot serve resources; fall back to the
            // shared portrait key rather than crashing the read path.
            return null;
        }
    }
}
