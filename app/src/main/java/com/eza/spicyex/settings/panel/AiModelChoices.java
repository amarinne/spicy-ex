package com.eza.spicyex.settings.panel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure list rules for the AI model picker. */
final class AiModelChoices {
    private AiModelChoices() { }

    /** The current model first, then the discovered ids in their own order, without blanks or repeats. */
    static List<String> ordered(List<String> discovered, String current) {
        Set<String> ids = new LinkedHashSet<>();
        if (current != null && !current.trim().isEmpty()) ids.add(current);
        if (discovered != null) {
            for (String id : discovered) {
                if (id != null && !id.trim().isEmpty()) ids.add(id);
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * The typed id the picker offers to use as is, or {@code ""} when the query is blank or names a
     * listed model exactly. A proxy's model list can omit a model it still serves.
     */
    static String typedChoice(String query, Collection<String> listed) {
        String typed = query == null ? "" : query.trim();
        if (typed.isEmpty() || typed.contains(" ")) return "";
        return listed != null && listed.contains(typed) ? "" : typed;
    }
}
