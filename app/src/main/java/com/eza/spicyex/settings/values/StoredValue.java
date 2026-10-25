package com.eza.spicyex.settings.values;

import java.util.Collections;
import java.util.List;

/**
 * A setting value that the preference stores as a label.
 *
 * <p>The label is what the preference holds and what the user reads; the constant is what the
 * code branches on. The labels live with the value, so the schema and the code read one list
 * instead of two.
 */
public interface StoredValue {
    /** The label the preference holds for this value. */
    String storedLabel();

    /** True for the value used when the stored label is unknown or absent. */
    boolean isDefault();

    /**
     * True when the preference can hold this value. A projected value - one the runtime derives
     * for a surface, not one the user picks - is never stored, so it is no part of a setting's
     * allowed values.
     */
    default boolean isStored() {
        return true;
    }

    /**
     * Older labels that mean this value. They must keep working: a stored label from an older
     * release still reads as the value it always meant.
     */
    default List<String> legacyLabels() {
        return Collections.emptyList();
    }
}
