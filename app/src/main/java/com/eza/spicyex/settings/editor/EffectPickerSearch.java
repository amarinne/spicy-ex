package com.eza.spicyex.settings.editor;

import java.util.Locale;

/** Pure matching rule for the searchable background-effect pickers. */
final class EffectPickerSearch {
    private EffectPickerSearch() { }

    static boolean matches(String query, String... candidates) {
        if (query == null || query.trim().isEmpty()) return true;
        String haystack = normalize(String.join(" ", candidates == null
                ? new String[0] : candidates));
        for (String token : normalize(query).split("\\s+")) {
            if (!token.isEmpty() && !haystack.contains(token)) return false;
        }
        return true;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace('-', ' ').replace('_', ' ').trim();
    }
}
