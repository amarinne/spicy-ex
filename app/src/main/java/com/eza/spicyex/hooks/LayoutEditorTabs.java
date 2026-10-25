package com.eza.spicyex.hooks;

import java.util.Locale;

/**
 * The layout editor's two tab strips: the lyrics panel's Style/Animation/Effects and the
 * now-playing card's Text/Animation.
 *
 * <p>The strip the user taps and the agent's {@code editor tab} command read the same tables here,
 * so a probe reaches exactly the tabs a user can reach, and a name that belongs to the other
 * strip is refused instead of quietly switching nothing. A tab's index is the registry's tab index
 * for that element, so the strip order and the rows under it cannot drift apart.
 */
final class LayoutEditorTabs {

    /** One tab of a strip: the name an agent uses and the label the user reads. */
    static final class Tab {
        /** The agent-facing name, lower case and the same in every translation. */
        final String name;
        /** The localization key of the visible label. */
        final String labelKey;
        /** The English label, used when the key has no translation yet. */
        final String label;

        Tab(String name, String labelKey, String label) {
            this.name = name;
            this.labelKey = labelKey;
            this.label = label;
        }
    }

    /** The lyrics panel's strip, in strip order: Style, Animation, Effects. */
    static final Tab[] LYRICS = {
            new Tab("style", "tab_style", "Style"),
            new Tab("animation", "tab_animation", "Animation"),
            new Tab("effects", "tab_effects", "Effects"),
    };

    /** The now-playing card's strip, in strip order: Text, Animation. */
    static final Tab[] CARD = {
            new Tab("text", "tab_text", "Text"),
            new Tab("animation", "tab_animation", "Animation"),
    };

    private LayoutEditorTabs() {
    }

    /** The strip of one editor mode. */
    static Tab[] strip(boolean cardMode) {
        return cardMode ? CARD : LYRICS;
    }

    /**
     * The index of a named tab in the given mode's strip, or -1 when the name is not one of them:
     * the card strip has no Effects tab and the lyrics strip has no Text tab.
     */
    static int index(boolean cardMode, String name) {
        if (name == null) return -1;
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        Tab[] strip = strip(cardMode);
        for (int i = 0; i < strip.length; i++) {
            if (strip[i].name.equals(wanted)) return i;
        }
        return -1;
    }

    /** The name of the tab at an index of the given mode's strip, or an empty string when the
     *  index is not one of them. */
    static String name(boolean cardMode, int index) {
        Tab[] strip = strip(cardMode);
        return index >= 0 && index < strip.length ? strip[index].name : "";
    }

    /** The tab names one mode accepts, for a refusal message: "text, animation". */
    static String accepted(boolean cardMode) {
        Tab[] strip = strip(cardMode);
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < strip.length; i++) {
            if (i > 0) names.append(", ");
            names.append(strip[i].name);
        }
        return names.toString();
    }

    /** Why a tab name was refused: which mode was open, and the tabs that mode does have. */
    static String refusal(boolean cardMode, String name) {
        return "editor tab '" + name + "' is not a tab in " + (cardMode ? "card" : "lyrics")
                + " mode; expected " + accepted(cardMode);
    }
}
