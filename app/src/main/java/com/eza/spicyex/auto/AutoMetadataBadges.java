package com.eza.spicyex.auto;

import java.util.ArrayList;
import java.util.List;

/** Filters a transient render list without changing the native metadata state. */
final class AutoMetadataBadges {
    static List<?> withoutExplicit(List<?> badges, int explicitId) {
        if (explicitId == 0 || !badges.contains(explicitId)) return badges;
        List<Object> filtered = new ArrayList<>(badges);
        filtered.removeIf(badge -> Integer.valueOf(explicitId).equals(badge));
        return filtered;
    }
}
