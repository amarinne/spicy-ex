package com.eza.spicyex.settings.editor;

import java.util.Collection;

/**
 * One request to pick a choice the layout editor rendered.
 *
 * <p>Pure Java: the request's shape and the reasons it cannot be carried out are decided here,
 * so the route and its tests agree on what "not a choice" means. The route itself resolves the
 * choice in the panel and lets that control's own owner do the work, so the write and the
 * rebuild that follows it are the production ones.
 *
 * <p>A request names a choice the way the panel stores it: the setting's key and the stored
 * value, with its case intact. A value that is not one of the setting's choices, or a setting
 * the current layout does not offer, is refused without a write.
 */
public final class EditorChoice {

    private final String settingKey;
    private final String value;

    private EditorChoice(String settingKey, String value) {
        this.settingKey = settingKey;
        this.value = value;
    }

    /**
     * The request for one choice.
     *
     * @param settingKey the setting the choice belongs to.
     * @param value      the stored value the choice stands for.
     * @return the request, or null when it does not name both a setting and a value.
     */
    public static EditorChoice of(String settingKey, String value) {
        if (settingKey == null || settingKey.isEmpty()) return null;
        if (value == null || value.isEmpty()) return null;
        return new EditorChoice(settingKey, value);
    }

    /** The setting the choice belongs to. */
    public String settingKey() {
        return settingKey;
    }

    /** The stored value the choice stands for. */
    public String value() {
        return value;
    }

    /**
     * Why the request cannot be carried out, or null when it can.
     *
     * @param editorOpen     the editor is open.
     * @param panelVisible   the options panel is on screen.
     * @param renderedValues the stored values of the setting's rendered choices.
     * @return the reason the request is refused, or null when the choice can be picked.
     */
    public String refusal(boolean editorOpen, boolean panelVisible,
            Collection<String> renderedValues) {
        if (!editorOpen) return "editor is not open";
        if (!panelVisible) return "the options panel is closed";
        if (renderedValues == null || renderedValues.isEmpty()) {
            return "no rendered choice for '" + settingKey + "'";
        }
        if (!renderedValues.contains(value)) {
            return "'" + value + "' is not a choice of '" + settingKey + "'";
        }
        return null;
    }

    @Override
    public String toString() {
        return settingKey + "=" + value;
    }
}
