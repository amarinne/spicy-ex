package com.eza.spicyex.auto;

import java.util.ArrayList;
import java.util.List;

/** Removes only the discovered suggestion singleton from a transient layout list. */
final class AutoDashboardCards {
    static List<?> withoutSuggestion(List<?> cards, Object suggestion) {
        if (suggestion == null) return cards;
        boolean found = false;
        for (Object card : cards) if (card == suggestion) { found = true; break; }
        if (!found) return cards;
        List<Object> filtered = new ArrayList<>(cards.size());
        for (Object card : cards) if (card != suggestion) filtered.add(card);
        return filtered;
    }
}
