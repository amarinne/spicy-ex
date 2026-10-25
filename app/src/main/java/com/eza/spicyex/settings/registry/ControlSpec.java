package com.eza.spicyex.settings.registry;

import com.eza.spicyex.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable UI metadata. Persistence and Android widgets remain owned by their existing layers. */
public final class ControlSpec {
    public enum Surface { PANEL, EDITOR }
    public enum Element { ARTWORK, TRACK_TEXT, LYRICS, FOCUS, BACKGROUND, TOP_BAR, CHIPS, CARD }
    public enum Kind { TOGGLE, CHOICE, SLIDER, MODE_SLIDER, STEPPER, TEXT_FIELD, COMPOSITE }
    public enum Effect { NONE, ARTWORK, TRACK_TEXT, LYRIC_ROWS, FOCUS, BACKGROUND, CHROME, CHIPS, CARD, REBUILD, APPLY_PREFERENCES }
    /** Where a row's hint line sits inside its own card. */
    public enum HintPlacement { BELOW, ABOVE }

    /** Primary owned setting; null for the navigation composites and the action rows. */
    public final Settings.Setting<?> setting;
    /**
     * The row's section. A row backed by a setting takes that setting's section; a row that
     * stores nothing (an action the user triggers) declares its own.
     */
    public final Settings.Section section;
    public final Surface surface;
    public final Element element;
    public final String groupTitleKey;
    public final Kind kind;
    public final List<String> choices;
    /** Paired custom value, also used to record the second owned setting of a composite. */
    public final Settings.Setting<?> customValue;
    public final Visibility visibleWhen;
    public final Effect effect;
    public final String compositeId;
    public final String hintKey;
    public final SliderSpec slider;
    /** Tab index for Lyrics/Card setting rows; null for navigation and untabbed elements. */
    public final Integer tab;
    /** Exact stored mode selected when a MODE_SLIDER writes its paired custom value. */
    public final String modeValue;
    /** Own title of the row, rendered above it inside its group card; null when the card
     *  title already names the control. */
    public final String labelKey;
    /** Where the hint line sits in the row's card: below the control, or above it for the
     *  controls whose hint explains the control it introduces. */
    public final HintPlacement hintPlacement;

    public ControlSpec(Settings.Setting<?> setting, Surface surface, Element element,
            String groupTitleKey, Kind kind, List<String> choices, Settings.Setting<?> customValue,
            Visibility visibleWhen, Effect effect, String compositeId, String hintKey,
            SliderSpec slider, Integer tab, String modeValue, String labelKey,
            HintPlacement hintPlacement) {
        this(setting, null, surface, element, groupTitleKey, kind, choices, customValue,
                visibleWhen, effect, compositeId, hintKey, slider, tab, modeValue, labelKey,
                hintPlacement);
    }

    /**
     * A row that stores nothing declares the section it belongs to; a row backed by a setting
     * takes that setting's section, so the section of a setting is stated in one place only.
     */
    public ControlSpec(Settings.Setting<?> setting, Settings.Section section, Surface surface,
            Element element, String groupTitleKey, Kind kind, List<String> choices,
            Settings.Setting<?> customValue, Visibility visibleWhen, Effect effect,
            String compositeId, String hintKey, SliderSpec slider, Integer tab, String modeValue,
            String labelKey, HintPlacement hintPlacement) {
        this.surface = Objects.requireNonNull(surface);
        this.kind = Objects.requireNonNull(kind);
        this.effect = Objects.requireNonNull(effect);
        if ((surface == Surface.EDITOR) != (element != null)) {
            throw new IllegalArgumentException("only editor controls have an element");
        }
        if ((kind == Kind.COMPOSITE) != (compositeId != null)) {
            throw new IllegalArgumentException("only composites have a renderer id");
        }
        if (setting == null && kind != Kind.COMPOSITE) {
            throw new IllegalArgumentException("setting required");
        }
        if (setting == null && surface == Surface.PANEL && section == null) {
            throw new IllegalArgumentException("a panel row without a setting needs its section");
        }
        if (kind == Kind.MODE_SLIDER && !(customValue instanceof Settings.IntegerSetting)) {
            throw new IllegalArgumentException("mode slider requires an integer custom value");
        }
        if (kind == Kind.MODE_SLIDER) {
            if (modeValue == null || setting.allowedValues == null || !setting.allowedValues.contains(modeValue)) {
                throw new IllegalArgumentException("mode slider requires an allowed stored mode");
            }
        } else if (modeValue != null) {
            throw new IllegalArgumentException("only mode sliders have a stored mode");
        }
        boolean tabbedRow = setting != null && tabCount(element) > 0;
        if (tabbedRow ? tab == null || tab < 0 || tab >= tabCount(element) : tab != null) {
            throw new IllegalArgumentException("invalid tab membership");
        }
        if ((kind == Kind.SLIDER || kind == Kind.STEPPER || kind == Kind.MODE_SLIDER)
                && slider == null) throw new IllegalArgumentException("slider metadata required");
        if ((kind == Kind.SLIDER || kind == Kind.STEPPER)
                && setting instanceof Settings.StringSetting && slider.codec == null) {
            throw new IllegalArgumentException("string numeric control requires a codec");
        }
        this.setting = setting;
        this.section = setting != null ? setting.section : section;
        this.element = element;
        this.groupTitleKey = groupTitleKey;
        this.choices = Collections.unmodifiableList(new ArrayList<>(choices));
        if (!this.choices.isEmpty() && (setting == null || setting.allowedValues == null
                || !setting.allowedValues.containsAll(this.choices))) {
            throw new IllegalArgumentException("choices must be allowed values");
        }
        this.customValue = customValue;
        this.visibleWhen = visibleWhen == null ? Visibility.ALWAYS : visibleWhen;
        this.compositeId = compositeId;
        this.hintKey = hintKey;
        this.slider = slider;
        this.tab = tab;
        this.modeValue = modeValue;
        this.labelKey = labelKey;
        this.hintPlacement = hintPlacement == null ? HintPlacement.BELOW : hintPlacement;
    }

    /** The row's stable id: its setting's key, or its composite id for a row that stores nothing. */
    public String rowId() {
        return setting != null ? setting.key : compositeId;
    }

    /** Option tabs a setting-bearing element is split across; zero when it has no tabs. */
    public static int tabCount(Element element) {
        if (element == Element.LYRICS) return 3;
        if (element == Element.CARD) return 2;
        return 0;
    }
}
