package com.eza.spicyex.settings.values;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the stored label of a value type. */
public final class StoredValues {
    private StoredValues() {
    }

    /** The value a stored label means; the default when the label is unknown or absent. */
    public static <E extends Enum<E> & StoredValue> E fromStored(Class<E> type, String stored) {
        for (E value : type.getEnumConstants()) {
            if (!value.isStored()) continue;
            if (value.storedLabel().equals(stored)) return value;
            if (value.legacyLabels().contains(stored)) return value;
        }
        return defaultValue(type);
    }

    /** The value used when the stored label is unknown or absent. */
    public static <E extends Enum<E> & StoredValue> E defaultValue(Class<E> type) {
        for (E value : type.getEnumConstants()) {
            if (value.isDefault()) return value;
        }
        throw new IllegalStateException(type.getSimpleName() + " declares no default value");
    }

    /** The label of the default value. */
    public static <E extends Enum<E> & StoredValue> String defaultLabel(Class<E> type) {
        return defaultValue(type).storedLabel();
    }

    /** Every stored label, in declaration order. */
    public static <E extends Enum<E> & StoredValue> List<String> labels(Class<E> type) {
        List<String> labels = new ArrayList<>();
        for (E value : type.getEnumConstants()) {
            if (value.isStored()) labels.add(value.storedLabel());
        }
        return Collections.unmodifiableList(labels);
    }

    /**
     * Every retired label, mapped to the label its value stores today.
     *
     * <p>A store read coerces the raw preference against the setting's current labels, so an old
     * label would otherwise fall back to the default before {@link #fromStored} could read it. The
     * schema passes this map to the setting so the read normalizes instead of losing the choice.
     * The first value that declares a label owns it, matching {@link #fromStored}.
     */
    public static <E extends Enum<E> & StoredValue> Map<String, String> aliases(
            Class<E> type) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (E value : type.getEnumConstants()) {
            if (!value.isStored()) continue;
            for (String legacy : value.legacyLabels()) {
                if (!legacy.equals(value.storedLabel()) && !aliases.containsKey(legacy)) {
                    aliases.put(legacy, value.storedLabel());
                }
            }
        }
        return Collections.unmodifiableMap(aliases);
    }
}
