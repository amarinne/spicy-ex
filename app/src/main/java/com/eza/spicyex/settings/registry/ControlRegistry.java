package com.eza.spicyex.settings.registry;

import com.eza.spicyex.Settings;
import com.eza.spicyex.lyrics.LyricsShellSettings;
import com.eza.spicyex.settings.values.AiProviderChoice;
import com.eza.spicyex.settings.values.AnimationStyle;
import com.eza.spicyex.settings.values.LiveCardOverflow;
import com.eza.spicyex.settings.values.LyricsFont;
import com.eza.spicyex.settings.values.WordBounceMode;
import com.eza.spicyex.settings.values.BackgroundStyle;
import com.eza.spicyex.settings.values.BackgroundEffect;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.eza.spicyex.settings.registry.ControlSpec.Element.*;
import static com.eza.spicyex.settings.registry.ControlSpec.Effect.NONE;
import static com.eza.spicyex.settings.registry.ControlSpec.Effect.REBUILD;
import static com.eza.spicyex.settings.registry.ControlSpec.Kind.*;

/**
 * The control registry: one entry per visible control, per surface.
 *
 * <p>The editor inventory was transcribed from LyricsLayoutEditController at a6f5a3c8 and is
 * rendered by the editor's control renderer. The panel inventory was transcribed from the panel's
 * own section order, row order, row kinds and visibility rules, and the panel renders it.
 *
 * <p>Each setting has one owner: a setting the layout editor owns has no panel row, and no
 * setting is described twice. Artwork also displays the same Track text entries, without copies.
 * Lyrics and Card list their tabs in tab order; their navigation composites retain tab ownership.
 * Gesture entries own the grip-only settings but never add an options-panel row.
 */
public final class ControlRegistry {
    private ControlRegistry() { }

    public static final ValueCodec FULLSCREEN_CONTROLS_CODEC = new ValueCodec() {
        @Override public int decode(String stored) {
            int seconds = LyricsShellSettings.parseFullscreenControlsSeconds(stored);
            return seconds == 0 ? 31 : seconds;
        }

        @Override public String encode(int value) {
            return LyricsShellSettings.fullscreenControlsValue(value == 31 ? 0 : value);
        }
    };

    private static final Visibility STACKED = context -> !context.twoColumn;
    private static final Visibility MANUAL_TRACK_SIZE = context ->
            !Boolean.TRUE.equals(context.settings.get(Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE));
    private static final Visibility CUSTOM_FONT = context ->
            context.settings.get(Settings.LYRICS_FONT, LyricsFont.class) == LyricsFont.CUSTOM;
    private static final Visibility APPLE = context ->
            context.settings.get(Settings.ANIMATION_STYLE, AnimationStyle.class).isApple();
    private static final Visibility OTHER_ANIMATION = context -> !APPLE.test(context);

    // Panel guards: the rules that decide whether a panel row shows. Each one names the state
    // it reads. They are declared before the panel inventory, which fills its rows with them.
    private static final Visibility AI_ON = context ->
            Boolean.TRUE.equals(context.settings.get(Settings.AI_ENABLED));
    /** The DeepSeek reasoning row exists only for the DeepSeek provider, under the AI switch. */
    private static final Visibility AI_DEEPSEEK = context -> AI_ON.test(context)
            && context.settings.get(Settings.AI_PROVIDER, AiProviderChoice.class)
                    == AiProviderChoice.DEEPSEEK;
    /** The translation rows follow the translation switch. */
    private static final Visibility TRANSLATION_ON = context ->
            Boolean.TRUE.equals(context.settings.get(Settings.TRANSLATION_ENABLED));
    /** The reading rows follow the transliteration switch. */
    private static final Visibility TRANSLITERATION_ON = context ->
            Boolean.TRUE.equals(context.settings.get(Settings.TRANSLITERATION_ENABLED));
    /** The Android Auto options follow the Android Auto switch. */
    private static final Visibility ANDROID_AUTO_ON = context ->
            Boolean.TRUE.equals(context.settings.get(Settings.AUTO_ENABLED));
    /** The picture-in-picture options follow the PiP switch. */
    private static final Visibility PIP_ON = context ->
            Boolean.TRUE.equals(context.settings.get(Settings.PIP_ENABLED));
    /** The Apple section exists only while the animation style is Apple Music. */
    private static final Visibility APPLE_STYLE = APPLE;
    /** The language pack is installed, so the Japanese reading rows can show. */
    private static final Visibility LANGUAGE_PACK_READY = context -> context.languagePackReady;
    /** Without the language pack the download row replaces the Japanese reading rows. */
    private static final Visibility LANGUAGE_PACK_MISSING = context -> !context.languagePackReady;
    /** The Japanese reading mode needs the language pack and the transliteration switch. */
    private static final Visibility JAPANESE_READING = context -> LANGUAGE_PACK_READY.test(context)
            && TRANSLITERATION_ON.test(context);

    private static final List<ControlSpec> EDITOR = createEditor();
    private static final List<ControlSpec> PANEL = createPanel();

    /** Unique owning entries, including the two navigation placeholders and grip-only controls. */
    public static List<ControlSpec> editorControls() { return EDITOR; }

    // --- Panel surface ---

    /** The settings panel's rows, in panel order. */
    public static List<ControlSpec> panelControls() { return PANEL; }

    /** The panel's rows of one section, in panel order. */
    public static List<ControlSpec> panelControls(Settings.Section section) {
        List<ControlSpec> controls = new ArrayList<>();
        for (ControlSpec control : PANEL) {
            if (control.section == section) controls.add(control);
        }
        return Collections.unmodifiableList(controls);
    }

    /** The panel's sections, in panel order: where each section's first row sits. */
    public static List<Settings.Section> panelSections() {
        List<Settings.Section> sections = new ArrayList<>();
        for (ControlSpec control : PANEL) {
            Settings.Section section = control.section;
            if (!sections.contains(section)) sections.add(section);
        }
        return Collections.unmodifiableList(sections);
    }

    /** The rows the panel renders for one snapshot: the visible entries, in panel order. */
    public static List<ControlSpec> visiblePanelControls(UiContext context) {
        List<ControlSpec> controls = new ArrayList<>();
        for (ControlSpec control : PANEL) {
            if (control.visibleWhen.test(context)) controls.add(control);
        }
        return Collections.unmodifiableList(controls);
    }

    /**
     * The sections the panel renders for one snapshot, in panel order.
     *
     * <p>A section without a visible row disappears. The layout editor section stays: it owns the
     * editor launch rows, so it has a card even when every setting row in it is hidden.
     */
    public static List<Settings.Section> visiblePanelSections(UiContext context) {
        List<Settings.Section> sections = new ArrayList<>();
        for (Settings.Section section : panelSections()) {
            if (section == Settings.LYRICS_SCREEN || hasVisibleRow(section, context)) {
                sections.add(section);
            }
        }
        return Collections.unmodifiableList(sections);
    }

    private static boolean hasVisibleRow(Settings.Section section, UiContext context) {
        for (ControlSpec control : PANEL) {
            if (control.section == section && control.visibleWhen.test(context)) return true;
        }
        return false;
    }

    public static List<ControlSpec> editorControls(ControlSpec.Element element) {
        List<ControlSpec> controls = new ArrayList<>();
        for (ControlSpec control : EDITOR) if (control.element == element) controls.add(control);
        return Collections.unmodifiableList(controls);
    }

    /** All tabs in tab order. The existing per-element Artwork panel also includes Track text. */
    public static List<ControlSpec> optionControls(ControlSpec.Element element, UiContext context) {
        List<ControlSpec> controls = new ArrayList<>(editorControls(element));
        if (element == ARTWORK) controls.addAll(editorControls(TRACK_TEXT));
        controls.removeIf(control -> !control.visibleWhen.test(context));
        return Collections.unmodifiableList(controls);
    }

    /**
     * The option rows of one tab, in registry order. Navigation composites carry no tab: they are
     * surface chrome rendered by the editor session, not part of a tab's control list.
     */
    public static List<ControlSpec> tabControls(ControlSpec.Element element, int tab, UiContext context) {
        int tabs = ControlSpec.tabCount(element);
        if (tabs == 0) throw new IllegalArgumentException(element + " has no option tabs");
        if (tab < 0 || tab >= tabs) {
            throw new IllegalArgumentException("Tab " + tab + " is outside " + element);
        }
        List<ControlSpec> controls = new ArrayList<>();
        for (ControlSpec control : optionControls(element, context)) {
            if (control.tab != null && control.tab == tab) controls.add(control);
        }
        return Collections.unmodifiableList(controls);
    }

    private static List<ControlSpec> createEditor() {
        List<ControlSpec> rows = new ArrayList<>();
        toggle(rows, Settings.ADAPTIVE_LANDSCAPE_LAYOUT, ARTWORK, null,
                "settings_layout_editor_layout", Visibility.ALWAYS, NONE);
        // TODO shape gap (controller:2866): one string setting changes from chips to a derived
        // toggle in two-column mode. The single-kind shape cannot express both presentations.
        composite(rows, Settings.TRACK_INFO_POSITION, null, ARTWORK, null, null,
                "track_info_position", Visibility.ALWAYS, REBUILD,
                "settings_layout_editor_artwork_hint", "Off", "Top", "Bottom", "Header");
        slider(rows, Settings.TRACK_INFO_ART_RADIUS, ARTWORK, null, "settings_unit_dp", ControlSpec.Effect.ARTWORK);
        choice(rows, Settings.PANEL_MEDIA_CONTROLS, ARTWORK, null, null, Visibility.ALWAYS, NONE,
                "Off", "Single tap", "Double tap");
        // This grip writes the mode and custom dp together. It is not an options-panel slider.
        // TODO shape gap (controller:1260): the shape has no gesture attachment; NEVER keeps
        // this ownership entry out of the options panel until the grip renderer consumes it.
        composite(rows, Settings.TRACK_INFO_ART_SIZE, Settings.TRACK_INFO_ART_SIZE_CUSTOM_DP,
                ARTWORK, null, null, "artwork_resize_grip", Visibility.NEVER, ControlSpec.Effect.ARTWORK, null);

        toggle(rows, Settings.TRACK_INFO_SHOW_TITLE, TRACK_TEXT, null,
                "settings_layout_editor_fields", Visibility.ALWAYS, REBUILD);
        toggle(rows, Settings.TRACK_INFO_SHOW_ARTIST, TRACK_TEXT, null,
                "settings_layout_editor_fields", Visibility.ALWAYS, REBUILD);
        toggle(rows, Settings.TRACK_INFO_SHOW_ALBUM, TRACK_TEXT, null,
                "settings_layout_editor_fields", Visibility.ALWAYS, REBUILD);
        choice(rows, Settings.TRACK_INFO_TEXT_ALIGN, TRACK_TEXT, null, null, STACKED, NONE,
                "Top", "Center", "Bottom");
        toggle(rows, Settings.TRACK_INFO_TEXT_SIZE_ADAPTIVE, TRACK_TEXT, null,
                label(Settings.TRACK_INFO_TEXT_SIZE), Visibility.ALWAYS, REBUILD);
        modeSlider(rows, Settings.TRACK_INFO_TEXT_SIZE, Settings.TRACK_INFO_TEXT_SIZE_CUSTOM,
                TRACK_TEXT, null, "Custom", MANUAL_TRACK_SIZE, NONE);
        choice(rows, Settings.TRACK_INFO_BACKGROUND, TRACK_TEXT, null, null, STACKED, NONE,
                "Gradient", "Solid", "None", "Blur");
        choice(rows, Settings.TRACK_INFO_TEXT_OVERFLOW, TRACK_TEXT, null, null, Visibility.ALWAYS, NONE,
                "Clip", "Wrap", "Scroll");

        // Navigation is shared by all tabs; each setting entry below declares its own tab.
        composite(rows, null, null, LYRICS, null, null, "lyrics_tabs", Visibility.ALWAYS, REBUILD, null);
        modeSlider(rows, Settings.LYRICS_TEXT_SIZE, Settings.LYRICS_TEXT_SIZE_CUSTOM,
                LYRICS, 0, "custom", Visibility.ALWAYS, NONE);
        toggle(rows, Settings.LYRICS_ADAPTIVE_TEXT_SIZE, LYRICS, 0,
                label(Settings.LYRICS_TEXT_SIZE), Visibility.ALWAYS, NONE);
        toggle(rows, Settings.ADAPTIVE_SECTIONING, LYRICS, 0,
                label(Settings.LYRICS_TEXT_SIZE), Visibility.ALWAYS, NONE);
        // Custom opens the chooser and commits mode + path only after a valid selection.
        composite(rows, Settings.LYRICS_FONT, null, LYRICS, 0, null, "font_chips",
                Visibility.ALWAYS, REBUILD, null, "spotify", "apple", "custom");
        // The dropdown writes path + mode; its owning setting is the path, not a second font row.
        composite(rows, Settings.LYRICS_FONT_CUSTOM_PATH, null, LYRICS, 0, label(Settings.LYRICS_FONT),
                "custom_font_chooser", CUSTOM_FONT, REBUILD, "settings_layout_editor_font_hint");
        choice(rows, Settings.LYRICS_WEIGHT, LYRICS, 0, null, Visibility.ALWAYS, NONE,
                "Regular", "Medium", "Bold");
        modeSlider(rows, Settings.LINE_SPACING, Settings.LINE_SPACING_CUSTOM,
                LYRICS, 0, "custom", Visibility.ALWAYS, NONE);
        choice(rows, Settings.ANIMATION_STYLE, LYRICS, 1, null, Visibility.ALWAYS, REBUILD,
                "Gradient wash", "Spotlight", "Apple Music");
        toggle(rows, Settings.LOAD_LIFT_ANIMATION, LYRICS, 1, label(Settings.ANIMATION_STYLE),
                Visibility.ALWAYS, NONE);
        toggle(rows, Settings.LINE_SLIDE_ANIMATION, LYRICS, 1, label(Settings.ANIMATION_STYLE), APPLE, NONE);
        toggle(rows, Settings.APPLE_LIFT, LYRICS, 1, label(Settings.ANIMATION_STYLE), APPLE, NONE);
        toggle(rows, Settings.APPLE_FADE_PASSED_LINES, LYRICS, 1, label(Settings.ANIMATION_STYLE), APPLE, NONE);
        slider(rows, Settings.APPLE_CASCADE_SPEED, LYRICS, 1, "settings_unit_percent", NONE,
                label(Settings.ANIMATION_STYLE), APPLE, null, label(Settings.APPLE_CASCADE_SPEED), null);
        slider(rows, Settings.APPLE_SPRING_STRENGTH, LYRICS, 1, "settings_unit_percent", NONE,
                label(Settings.ANIMATION_STYLE), APPLE, null, label(Settings.APPLE_SPRING_STRENGTH), null);
        choice(rows, Settings.WORD_BOUNCE, LYRICS, 1, null, OTHER_ANIMATION, REBUILD,
                "Off", "Word/syllable synced only", "All synced rows");
        choice(rows, Settings.WORD_BOUNCE_STYLE, LYRICS, 1, null,
                context -> OTHER_ANIMATION.test(context)
                        && context.settings.get(Settings.WORD_BOUNCE, WordBounceMode.class)
                                != WordBounceMode.OFF, REBUILD,
                "Phrase zoom", "Word zoom", "Phrase lift", "Word lift", "Apple lift");
        choice(rows, Settings.LINE_SYNC_FILL, LYRICS, 1, null, OTHER_ANIMATION, REBUILD,
                "Top to bottom", "Left to right (block)", "Left to right (sentence)");
        choice(rows, Settings.INTERLUDE_ICON, LYRICS, 2, null, Visibility.ALWAYS, NONE, "dots", "note");
        toggle(rows, Settings.ENABLE_GLOW_BLUR, LYRICS, 2, null, Visibility.ALWAYS, NONE);
        // One blur strength: 0 is off, and the percent above it strengthens the blur.
        composite(rows, Settings.LYRICS_BLUR_STRENGTH, null, LYRICS, 2, null,
                "distant_line_blur", Visibility.ALWAYS, NONE, "settings_layout_editor_blur_intensity_hint");

        choiceWithHint(rows, Settings.LYRICS_FOCUS_POSITION, FOCUS, null, ControlSpec.Effect.FOCUS,
                "settings_layout_editor_focus_hint", "Auto", "Top", "Center", "Bottom");
        // This handle also writes the existing mode to Custom; the mode owns the chips above.
        // TODO shape gap (controller:1772): gesture attachment is absent from ControlSpec.
        composite(rows, Settings.LYRICS_FOCUS_POSITION_CUSTOM_PERCENT, null, FOCUS, null, null,
                "focus_drag_handle", Visibility.NEVER, ControlSpec.Effect.FOCUS, null);

        // The style change recreates the ambient views, so the panel rebuilds and the editor's
        // capture outlines re-align once the ambient layer has settled.
        choice(rows, Settings.BACKGROUND_STYLE, BACKGROUND, null, null, Visibility.ALWAYS,
                ControlSpec.Effect.BACKGROUND, "Gradient", "Static texture", "Animated texture");
        Visibility effectAvailable = context -> context.settings
                .get(Settings.BACKGROUND_STYLE, BackgroundStyle.class).usesTexture();
        composite(rows, Settings.BACKGROUND_EFFECT, null, BACKGROUND, null, null,
                "background_effect_picker", effectAvailable, ControlSpec.Effect.BACKGROUND,
                "settings_background_effect_hint");
        composite(rows, Settings.BACKGROUND_RANDOM_EFFECTS, null, BACKGROUND, null, null,
                "background_random_effects", context -> effectAvailable.test(context)
                        && context.settings.get(Settings.BACKGROUND_EFFECT, BackgroundEffect.class) == BackgroundEffect.RANDOM,
                ControlSpec.Effect.BACKGROUND, "settings_background_random_hint");
        // One darkening value: 0 is off, 1-100 darkens the background by that percent.
        slider(rows, Settings.LYRICS_DARKENING, BACKGROUND, null, "settings_unit_percent", NONE);

        toggle(rows, Settings.SHOW_FULLSCREEN_BACK_BUTTON, TOP_BAR, null,
                "settings_layout_editor_visibility", Visibility.ALWAYS, ControlSpec.Effect.CHROME);
        choice(rows, Settings.LIKED_SONGS_BUTTON, TOP_BAR, null, null, Visibility.ALWAYS,
                ControlSpec.Effect.CHROME, "Off", "Heart", "Star");
        choice(rows, Settings.CHROME_CLUSTER_POSITION, TOP_BAR, null, null, Visibility.ALWAYS,
                ControlSpec.Effect.CHROME, "Left", "Right");
        choice(rows, Settings.CHROME_CLUSTER_LAYOUT, TOP_BAR, null, null, Visibility.ALWAYS,
                ControlSpec.Effect.CHROME, "Auto", "Vertical", "Horizontal");
        // The timeout is a stored name, not a number: the codec names every stop, so the row
        // carries no unit of its own. The final stop is the codec's "Always".
        rows.add(spec(Settings.FULLSCREEN_CONTROLS, TOP_BAR, null, null, SLIDER, Collections.emptyList(),
                null, Visibility.ALWAYS, ControlSpec.Effect.APPLY_PREFERENCES, null, null,
                new SliderSpec(1, 30, 1, null, 31,
                        "settings_layout_editor_always", 31, FULLSCREEN_CONTROLS_CODEC), null, null, null));

        // Skip and Follow are one floating-control system, so either outline opens the same
        // combined options. Every row names its own setting inside the element's card.
        choiceWithLabel(rows, Settings.SKIP_CHIP_POSITION, CHIPS, null,
                "settings_layout_editor_element_skip", label(Settings.SKIP_CHIP_POSITION),
                ControlSpec.Effect.CHIPS, "Left", "Center", "Right");
        choiceWithLabel(rows, Settings.SKIP_CHIP_STYLE, CHIPS, null,
                "settings_layout_editor_element_skip", label(Settings.SKIP_CHIP_STYLE),
                ControlSpec.Effect.CHIPS, "Auto", "Label", "Icon");
        choiceWithLabel(rows, Settings.FOLLOW_CHIP_POSITION, CHIPS, null,
                "settings_layout_editor_element_follow", label(Settings.FOLLOW_CHIP_POSITION),
                ControlSpec.Effect.CHIPS, "Left", "Center", "Right");
        choiceWithLabel(rows, Settings.FOLLOW_CHIP_STYLE, CHIPS, null,
                "settings_layout_editor_element_follow", label(Settings.FOLLOW_CHIP_STYLE),
                ControlSpec.Effect.CHIPS, "Auto", "Label", "Icon");
        choiceWithLabel(rows, Settings.FOLLOW_CHIP_ICON, CHIPS, null,
                "settings_layout_editor_element_follow", label(Settings.FOLLOW_CHIP_ICON),
                ControlSpec.Effect.CHIPS, "Adaptive arrow", "Static arrow", "Waveform");

        // Navigation is shared by the Text and Animation tabs.
        composite(rows, null, null, CARD, null, null, "card_tabs", Visibility.ALWAYS, REBUILD, null);
        modeSlider(rows, Settings.LIVE_CARD_TEXT_SIZE, Settings.LIVE_CARD_TEXT_SIZE_CUSTOM,
                CARD, 0, "custom", Visibility.ALWAYS, ControlSpec.Effect.CARD);
        choice(rows, Settings.LIVE_CARD_WEIGHT, CARD, 0, null, Visibility.ALWAYS, REBUILD,
                "Regular", "Medium", "Bold");
        choice(rows, Settings.LIVE_CARD_SECONDARY_MODE, CARD, 0, null, Visibility.ALWAYS, REBUILD,
                "Main only", "Transliteration", "Translation", "Both");
        choice(rows, Settings.LIVE_CARD_OVERFLOW, CARD, 0, null, Visibility.ALWAYS, REBUILD,
                "Wrap", "Scroll with lyric", "Clip");
        choice(rows, Settings.LIVE_CARD_SCROLL_SCOPE, CARD, 0, null,
                context -> context.settings.get(Settings.LIVE_CARD_OVERFLOW, LiveCardOverflow.class)
                        == LiveCardOverflow.SCROLL_WITH_LYRIC,
                REBUILD, "Grouped", "Individual lines");
        choice(rows, Settings.LIVE_CARD_ANIMATION, CARD, 1, null, Visibility.ALWAYS, REBUILD,
                "Minimal", "Karaoke fill", "Spotlight word");
        choice(rows, Settings.LIVE_CARD_GLOW, CARD, 1, null, Visibility.ALWAYS, REBUILD,
                "Off", "Word only");
        choice(rows, Settings.LIVE_CARD_LINE_SYNC_FILL, CARD, 1, null, Visibility.ALWAYS, REBUILD,
                "Top to bottom", "Left to right (block)", "Left to right (sentence)");
        choice(rows, Settings.LIVE_CARD_TRANSITION, CARD, 1, null, Visibility.ALWAYS, REBUILD,
                "Fade up", "Crossfade", "None");
        return Collections.unmodifiableList(rows);
    }

    // --- Panel inventory ---

    /**
     * The settings panel's rows, in panel order.
     *
     * <p>Transcribed from the panel's own section order, row order, row kinds and visibility rules.
     * A setting the layout editor owns has no panel row: the editor is its only surface. A row the
     * panel shows as one hand-built control names that control instead of a kind.
     */
    private static List<ControlSpec> createPanel() {
        List<ControlSpec> rows = new ArrayList<>();
        // Behavior
        panelChoice(rows, Settings.UI_LANGUAGE, Visibility.ALWAYS);
        panelChoice(rows, Settings.TAP_SEEK_MODE, Visibility.ALWAYS);
        panelToggle(rows, Settings.STAY_IN_LYRICS, Visibility.ALWAYS);
        panelToggle(rows, Settings.AUTO_RESUME_FOLLOW, Visibility.ALWAYS);
        // TODO shape gap (registry:345): the auto-resume delay row has never rendered. The
        // panel's applied state never carried the auto-resume switch, so the guard that shows
        // this row never matched, and no surface renders the setting today.
        panelStepper(rows, Settings.AUTO_RESUME_FOLLOW_DELAY_SECONDS, Visibility.NEVER);
        panelChoice(rows, Settings.AUTO_SKIP_INTRO_OUTRO, Visibility.ALWAYS);
        panelToggle(rows, Settings.MINI_PLAYER_LYRICS_ICON, Visibility.ALWAYS);
        panelChoice(rows, Settings.STATUS_BAR_HIDDEN_MODE, Visibility.ALWAYS);
        panelToggle(rows, Settings.LONG_PRESS_SHARE, Visibility.ALWAYS);
        panelStepper(rows, Settings.SYNC_OFFSET_MS, Visibility.ALWAYS);
        panelToggle(rows, Settings.HYPERGLOW_ENABLED, Visibility.ALWAYS);
        // Lyrics sources. The merged source row owns the mode, the override and the order: only
        // the mode entry renders, the other two are the row's label and its stored order.
        panelComposite(rows, Settings.LYRICS_SOURCE_MODE, "source_row", Visibility.ALWAYS);
        panelComposite(rows, Settings.LYRICS_SOURCE_OVERRIDE, "source_row", Visibility.NEVER);
        panelComposite(rows, Settings.SPICY_ORG_CLIENT_KEY, "spicy_key", Visibility.ALWAYS);
        panelComposite(rows, Settings.LYRICS_SOURCE_ORDER, "source_row", Visibility.NEVER);
        panelToggle(rows, Settings.KARAOKE_ORIGINAL_LYRICS, Visibility.ALWAYS);
        panelChoice(rows, Settings.CACHE_SIZE, Visibility.ALWAYS);
        // Layout editor. The looks are edited on the screen itself; tap behaviour and transition
        // feel stay in the ordinary settings rows.
        panelChoice(rows, Settings.LIVE_CARD_TAP_MODE, Visibility.ALWAYS);
        panelChoice(rows, Settings.LIVE_CARD_TAP_TARGET, Visibility.ALWAYS);
        panelChoice(rows, Settings.TRANSITION_FEEL, Visibility.ALWAYS);
        // Apple Music, shown only while the animation style is Apple Music.
        panelToggle(rows, Settings.APPLE_COMPACT_TEXT, APPLE_STYLE);
        // Reading and transliteration
        // The language pack download is an action, not a setting: the row stores nothing and
        // shows only while the pack is missing.
        panelAction(rows, Settings.TRANSLITERATION, "download_language_models",
                LANGUAGE_PACK_MISSING);
        panelToggle(rows, Settings.TRANSLITERATION_ENABLED, Visibility.ALWAYS);
        panelToggle(rows, Settings.ALIGNED_PER_WORD_ROMAJI, TRANSLITERATION_ON);
        panelChoice(rows, Settings.JAPANESE_READING_MODE, JAPANESE_READING);
        // TODO shape gap (registry:377): the furigana design rows have never rendered. The
        // panel's applied state never carried the Japanese reading mode, so the guard that
        // shows these rows never matched, and the layout editor does not show them either.
        panelStepper(rows, Settings.FURIGANA_BRIGHTNESS, Visibility.NEVER);
        panelStepper(rows, Settings.FURIGANA_POSITION_PERCENT, Visibility.NEVER);
        panelChoice(rows, Settings.CHINESE_MODE, TRANSLITERATION_ON);
        // The Chinese character form ships in the APK, so it stays visible on its own.
        panelChoice(rows, Settings.CHINESE_CHARACTER_FORM, Visibility.ALWAYS);
        panelChoice(rows, Settings.KOREAN_ROMANIZATION, TRANSLITERATION_ON);
        panelToggle(rows, Settings.CHINESE_TONES, TRANSLITERATION_ON);
        panelChoice(rows, Settings.CYRILLIC_MODE, TRANSLITERATION_ON);
        panelToggle(rows, Settings.CYRILLIC_KEEP_SIGNS, TRANSLITERATION_ON);
        // Translation
        panelToggle(rows, Settings.TRANSLATION_ENABLED, Visibility.ALWAYS);
        panelChoice(rows, Settings.TRANSLATION_TARGET, TRANSLATION_ON);
        panelChoice(rows, Settings.TRANSLATION_BRIGHTNESS, TRANSLATION_ON);
        // AI
        panelToggle(rows, Settings.AI_ENABLED, Visibility.ALWAYS);
        panelChoice(rows, Settings.AI_PROVIDER, AI_ON);
        panelChoice(rows, Settings.AI_DEEPSEEK_REASONING, AI_DEEPSEEK);
        panelChoice(rows, Settings.AI_TRANSLATION_MODE, AI_ON);
        panelChoice(rows, Settings.AI_TRANSLATION_PIPELINE, AI_ON);
        panelChoice(rows, Settings.AI_PRONUNCIATION_MODE, AI_ON);
        panelChoice(rows, Settings.AI_PRONUNCIATION_SOURCE, AI_ON);
        panelChoice(rows, Settings.AI_BUTTON_BEHAVIOR, AI_ON);
        // Android Auto
        panelToggle(rows, Settings.AUTO_ENABLED, Visibility.ALWAYS);
        panelChoice(rows, Settings.AUTO_SECONDARY_TEXT, ANDROID_AUTO_ON);
        // Picture-in-picture
        panelToggle(rows, Settings.PIP_ENABLED, Visibility.ALWAYS);
        panelChoice(rows, Settings.PIP_SHAPE, PIP_ON);
        panelToggle(rows, Settings.PIP_CONTROLS, PIP_ON);
        panelChoice(rows, Settings.PIP_FOCUS, PIP_ON);
        panelToggle(rows, Settings.PIP_LEAVE_SPOTIFY, PIP_ON);
        panelToggle(rows, Settings.PIP_ALBUM_ART, PIP_ON);
        return Collections.unmodifiableList(rows);
    }

    /** A panel toggle row. */
    private static void panelToggle(List<ControlSpec> rows, Settings.Setting<Boolean> setting,
            Visibility visibility) {
        rows.add(panelSpec(setting, ControlSpec.Kind.TOGGLE, visibility, null));
    }

    /** A panel selector row; it offers the setting's own allowed values. */
    private static void panelChoice(List<ControlSpec> rows, Settings.Setting<String> setting,
            Visibility visibility) {
        rows.add(panelSpec(setting, ControlSpec.Kind.CHOICE, visibility, null));
    }

    /** A panel stepper row; its range is the setting's own. */
    private static void panelStepper(List<ControlSpec> rows, Settings.IntegerSetting setting,
            Visibility visibility) {
        rows.add(panelSpec(setting, ControlSpec.Kind.STEPPER, visibility, null));
    }

    /** A panel row that one hand-built control renders. */
    private static void panelComposite(List<ControlSpec> rows, Settings.Setting<?> setting,
            String compositeId, Visibility visibility) {
        rows.add(panelSpec(setting, ControlSpec.Kind.COMPOSITE, visibility, compositeId));
    }

    /** A panel row that stores nothing: an action the user triggers. */
    private static void panelAction(List<ControlSpec> rows, Settings.Section section,
            String compositeId, Visibility visibility) {
        rows.add(new ControlSpec(null, section, ControlSpec.Surface.PANEL, null, null,
                ControlSpec.Kind.COMPOSITE, Collections.emptyList(), null, visibility,
                ControlSpec.Effect.NONE, compositeId, null, null, null, null, null, null));
    }

    private static ControlSpec panelSpec(Settings.Setting<?> setting, ControlSpec.Kind kind,
            Visibility visibility, String compositeId) {
        SliderSpec slider = kind == ControlSpec.Kind.STEPPER
                ? SliderSpec.from((Settings.IntegerSetting) setting, null) : null;
        return new ControlSpec(setting, ControlSpec.Surface.PANEL, null, null, kind,
                Collections.emptyList(), null, visibility, ControlSpec.Effect.NONE,
                compositeId, null, slider, null, null, null, null);
    }

    private static String label(Settings.Setting<?> setting) { return "settings_label_" + setting.key; }
    private static ControlSpec spec(Settings.Setting<?> setting, ControlSpec.Element element, Integer tab,
            String group, ControlSpec.Kind kind, List<String> choices, Settings.Setting<?> custom,
            Visibility visibility, ControlSpec.Effect effect, String composite, String hint, SliderSpec slider,
            String modeValue, String label, ControlSpec.HintPlacement hintPlacement) {
        return new ControlSpec(setting, ControlSpec.Surface.EDITOR, element, group, kind, choices,
                custom, visibility, effect, composite, hint, slider, tab, modeValue, label, hintPlacement);
    }

    private static void toggle(List<ControlSpec> rows, Settings.Setting<Boolean> setting,
            ControlSpec.Element element, Integer tab, String group, Visibility visibility, ControlSpec.Effect effect) {
        rows.add(spec(setting, element, tab, group, TOGGLE, Collections.emptyList(), null,
                visibility, effect, null, null, null, null, null, null));
    }

    private static void choice(List<ControlSpec> rows, Settings.Setting<String> setting,
            ControlSpec.Element element, Integer tab, String group, Visibility visibility,
            ControlSpec.Effect effect, String... values) {
        rows.add(spec(setting, element, tab, group, CHOICE, Arrays.asList(values), null,
                visibility, effect, null, null, null, null, null, null));
    }

    /** A choice that shares its element's card with other rows and names itself above them. */
    private static void choiceWithLabel(List<ControlSpec> rows, Settings.Setting<String> setting,
            ControlSpec.Element element, Integer tab, String group, String label,
            ControlSpec.Effect effect, String... values) {
        rows.add(spec(setting, element, tab, group, CHOICE, Arrays.asList(values), null,
                Visibility.ALWAYS, effect, null, null, null, null, label, null));
    }

    private static void choiceWithHint(List<ControlSpec> rows, Settings.Setting<String> setting,
            ControlSpec.Element element, Integer tab, ControlSpec.Effect effect, String hint, String... values) {
        rows.add(spec(setting, element, tab, null, CHOICE, Arrays.asList(values), null,
                Visibility.ALWAYS, effect, null, hint, null, null, null, null));
    }

    private static void slider(List<ControlSpec> rows, Settings.IntegerSetting setting,
            ControlSpec.Element element, Integer tab, String unit, ControlSpec.Effect effect) {
        slider(rows, setting, element, tab, unit, effect, null, Visibility.ALWAYS, null, null, null);
    }

    private static void slider(List<ControlSpec> rows, Settings.IntegerSetting setting,
            ControlSpec.Element element, Integer tab, String unit, ControlSpec.Effect effect, String group,
            Visibility visibility, String hint, String label, ControlSpec.HintPlacement hintPlacement) {
        rows.add(spec(setting, element, tab, group, SLIDER, Collections.emptyList(), null,
                visibility, effect, null, hint, SliderSpec.from(setting, unit), null, label, hintPlacement));
    }

    private static void modeSlider(List<ControlSpec> rows, Settings.Setting<String> setting,
            Settings.IntegerSetting custom, ControlSpec.Element element, Integer tab, String modeValue, Visibility visibility,
            ControlSpec.Effect effect) {
        rows.add(spec(setting, element, tab, null, MODE_SLIDER, Collections.emptyList(), custom,
                visibility, effect, null, null, SliderSpec.from(custom, "settings_unit_percent"), modeValue, null, null));
    }

    private static void composite(List<ControlSpec> rows, Settings.Setting<?> setting,
            Settings.Setting<?> custom, ControlSpec.Element element, Integer tab, String group, String id,
            Visibility visibility, ControlSpec.Effect effect, String hint, String... values) {
        rows.add(spec(setting, element, tab, group, COMPOSITE, Arrays.asList(values), custom,
                visibility, effect, id, hint, null, null, null, null));
    }
}
