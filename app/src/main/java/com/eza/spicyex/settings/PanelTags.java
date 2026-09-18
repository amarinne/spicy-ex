package com.eza.spicyex.settings;

import com.eza.spicyex.Settings;

/**
 * Stable view tags used by the panel's keyed rebuild.
 *
 * <p>One place defines the identity strings so the coordinator (which finds rows), the row
 * factory (which sets them), and the section editor (which reorders them) cannot drift apart.
 * Row identity is the persisted preference key: renaming it would break rebinding.
 */
public final class PanelTags {
    public static final String HEADER_PREFIX = "hdr:";
    public static final String CARD_PREFIX = "card:";
    public static final String ROW_PREFIX = "row:";
    /** Scoped lookups inside one row: the summary and value TextViews. */
    public static final String ROW_SUMMARY = "row:summary";
    public static final String ROW_VALUE = "row:value";
    /** AI dynamic block inside the AI card; ordinary AI settings rows use a row tag. */
    public static final String AI_DYNAMIC = "card:ai:dynamic";

    private PanelTags() {
    }

    public static String header(Settings.Section section) {
        return HEADER_PREFIX + section.id;
    }

    public static String card(Settings.Section section) {
        return CARD_PREFIX + section.id;
    }

    public static String row(Settings.Setting<?> setting) {
        return ROW_PREFIX + setting.key;
    }

    public static String row(String settingKey) {
        return ROW_PREFIX + settingKey;
    }

    /** The setting key encoded by a row tag, or null when the tag is not a row tag. */
    public static String keyOf(Object tag) {
        if (!(tag instanceof String)) return null;
        String value = (String) tag;
        return value.startsWith(ROW_PREFIX) ? value.substring(ROW_PREFIX.length()) : null;
    }
}
