package com.eza.spicyex.settings.editor;

import com.eza.spicyex.settings.registry.ControlSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The layout editor's list mode: the options panel shows every element, one header per element,
 * in one fixed order, instead of only the selected element's options.
 *
 * <p>Plain Java with no Android in it, so the order, the tap-to-section mapping, the scroll
 * retention and the card-mode refusal are unit-tested directly. The panel mode lives only in a
 * {@link State} that each editor session creates fresh, so the editor always opens in per-element
 * mode and a mode switch never writes a setting.
 */
public final class EditorListPlan {

    /** What the options panel shows. */
    public enum Mode {
        /** The selected element's options only. */
        ELEMENT("element"),
        /** Every element's options, one section each. */
        LIST("list");

        /** The agent-facing name, as {@code editor mode} takes it and {@code layout} reports it. */
        public final String wire;

        Mode(String wire) {
            this.wire = wire;
        }

        /** The mode with the given agent name, or null when the name is not one of them. */
        public static Mode parse(String name) {
            if (name == null) return null;
            String wanted = name.trim().toLowerCase(Locale.ROOT);
            for (Mode mode : values()) {
                if (mode.wire.equals(wanted)) return mode;
            }
            return null;
        }
    }

    /** One list section: one element under one header. */
    public static final class Section {
        /** The agent-facing section id, reported in {@code list_sections} and {@code list_scroll}. */
        public final String id;
        /** The registry element whose rows the section renders. */
        public final ControlSpec.Element element;
        /** The header's key under {@code settings_layout_editor_}, the per-element panel title. */
        public final String labelKey;
        /** The English header, used when the key has no translation. */
        public final String label;

        Section(String id, ControlSpec.Element element, String labelKey, String label) {
            this.id = id;
            this.element = element;
            this.labelKey = labelKey;
            this.label = label;
        }
    }

    /** Every section, in list order. The card is not here: the card editor keeps its own list. */
    public static final List<Section> SECTIONS = Collections.unmodifiableList(sections());

    private static List<Section> sections() {
        List<Section> out = new ArrayList<>();
        out.add(new Section("artwork", ControlSpec.Element.ARTWORK, "element_artwork", "Artwork"));
        out.add(new Section("track_text", ControlSpec.Element.TRACK_TEXT, "element_track_text", "Track text"));
        out.add(new Section("lyrics", ControlSpec.Element.LYRICS, "element_lyrics", "Lyrics"));
        out.add(new Section("focus", ControlSpec.Element.FOCUS, "element_focus", "Focus"));
        out.add(new Section("background", ControlSpec.Element.BACKGROUND, "element_background", "Background"));
        out.add(new Section("top_bar", ControlSpec.Element.TOP_BAR, "element_top_bar", "Top bar"));
        out.add(new Section("skip_follow", ControlSpec.Element.CHIPS, "element_chips", "Skip & Follow"));
        return out;
    }

    private EditorListPlan() {
    }

    /** The section ids in list order. */
    public static List<String> sectionIds() {
        List<String> ids = new ArrayList<>(SECTIONS.size());
        for (Section section : SECTIONS) ids.add(section.id);
        return ids;
    }

    /** The index of the section that renders an element, or -1 when the list has none (the card). */
    public static int indexOf(ControlSpec.Element element) {
        for (int i = 0; i < SECTIONS.size(); i++) {
            if (SECTIONS.get(i).element == element) return i;
        }
        return -1;
    }

    /**
     * The section that owns the top of the list viewport.
     *
     * @param sectionTops each header's top in list coordinates, in list order.
     * @param scrollY     the list's scroll position.
     * @return the index of the last section whose header starts at or above the viewport top, or
     *         -1 when there are no sections.
     */
    public static int firstVisible(int[] sectionTops, int scrollY) {
        if (sectionTops == null || sectionTops.length == 0) return -1;
        int index = 0;
        for (int i = 1; i < sectionTops.length; i++) {
            if (sectionTops[i] <= scrollY) index = i;
        }
        return index;
    }

    /**
     * Why a mode request was refused, or null when the open editor accepts it.
     *
     * @param cardMode  the now playing card editor is open; it keeps its own list.
     * @param requested the agent-facing mode name.
     */
    public static String refusal(boolean cardMode, String requested) {
        Mode mode = Mode.parse(requested);
        if (mode == Mode.ELEMENT || (mode == Mode.LIST && !cardMode)) return null;
        return "editor mode '" + requested + "' is not a mode in " + (cardMode ? "card" : "lyrics")
                + " mode; expected " + (cardMode ? "element" : "element, list");
    }

    /**
     * The mode the toolbar's list toggle shows.
     *
     * <p>While the panel is hidden the toggle always opens the full list, whatever mode the
     * hidden panel was left in. While it is open, the toggle swaps the two modes.
     *
     * @param current      the panel's mode.
     * @param panelVisible the options panel is on screen.
     */
    public static Mode toggled(Mode current, boolean panelVisible) {
        if (!panelVisible) return Mode.LIST;
        return current == Mode.LIST ? Mode.ELEMENT : Mode.LIST;
    }

    /**
     * Why the toolbar's list toggle cannot run, or null when it can.
     *
     * @param editorOpen the layout editor is open.
     * @param cardMode   the now playing card editor is open; it has no list toggle.
     */
    public static String toggleRefusal(boolean editorOpen, boolean cardMode) {
        if (!editorOpen) return "editor is not open";
        if (cardMode) return "the card editor has no list toggle";
        return null;
    }

    /**
     * One editor session's panel mode and list scroll position. A session creates its own, so the
     * editor always opens in {@link Mode#ELEMENT}; nothing here reads or writes a setting.
     */
    public static final class State {
        private Mode mode = Mode.ELEMENT;
        private int listScrollY;

        public Mode mode() {
            return mode;
        }

        public boolean isList() {
            return mode == Mode.LIST;
        }

        /**
         * Switches the panel mode.
         *
         * @return false when the mode does not change: it is already open, or the card editor
         *         refuses the list.
         */
        public boolean set(Mode wanted, boolean cardMode) {
            if (wanted == null || wanted == mode) return false;
            if (wanted == Mode.LIST && cardMode) return false;
            mode = wanted;
            listScrollY = 0;
            return true;
        }

        /** Records the list's scroll position before the list is rebuilt. */
        public void rememberScroll(int scrollY) {
            listScrollY = Math.max(0, scrollY);
        }

        /** The list's scroll position to restore after a rebuild. */
        public int scrollY() {
            return listScrollY;
        }

        /**
         * The scroll position that brings an element's section to the top of the list, which also
         * becomes the remembered position. An element without a section leaves the position alone.
         */
        public int scrollTo(ControlSpec.Element element, int[] sectionTops) {
            int index = indexOf(element);
            if (index >= 0 && sectionTops != null && index < sectionTops.length) {
                rememberScroll(sectionTops[index]);
            }
            return listScrollY;
        }
    }
}
