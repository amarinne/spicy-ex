package com.eza.spicyex.settings;

/**
 * Locale-resolved string lookup for pure panel logic.
 *
 * <p>Production delegates to {@code SettingsUiStrings} (module XML with English fallback);
 * JVM tests inject a map. Keeps the locale contract intact while letting visibility and
 * formatting logic run without Android resources.
 */
public interface PanelStrings {
    String get(String name, String fallback);

    String format(String name, String fallback, Object... args);

    /** Test double: exact map hits, {@link String#format} over the fallback otherwise. */
    final class MapStrings implements PanelStrings {
        private final java.util.Map<String, String> strings;

        public MapStrings(java.util.Map<String, String> strings) {
            this.strings = strings == null
                    ? java.util.Collections.emptyMap()
                    : new java.util.HashMap<>(strings);
        }

        @Override
        public String get(String name, String fallback) {
            String value = strings.get(name);
            return value != null ? value : (fallback == null ? "" : fallback);
        }

        @Override
        public String format(String name, String fallback, Object... args) {
            String pattern = strings.get(name);
            if (pattern == null) pattern = fallback == null ? "" : fallback;
            try {
                return String.format(java.util.Locale.ROOT, pattern, args);
            } catch (RuntimeException badPattern) {
                return pattern;
            }
        }
    }
}
