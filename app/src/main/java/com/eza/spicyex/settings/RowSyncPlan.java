package com.eza.spicyex.settings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure planner for keyed row rebinding.
 *
 * <p>Given the visible row keys in schema order and the keys currently mounted in a card,
 * answers which mounted rows are stale and what the final order is. The Android layer
 * executes the plan: remove stale rows, reuse the rest by stable ID (patching ordinary
 * rows, replacing composites), and build only what is missing.
 */
public final class RowSyncPlan {
    /** Mounted keys that are no longer visible, in mount order. */
    public final List<String> removals;
    /** Visible keys in schema order; the card's final child order. */
    public final List<String> order;

    private RowSyncPlan(List<String> removals, List<String> order) {
        this.removals = removals;
        this.order = order;
    }

    public static RowSyncPlan of(List<String> visibleKeys, List<String> currentKeys) {
        Set<String> visible = new LinkedHashSet<>(
                visibleKeys == null ? new ArrayList<String>() : visibleKeys);
        List<String> removals = new ArrayList<>();
        if (currentKeys != null) {
            for (String key : currentKeys) {
                if (key != null && !visible.contains(key)) removals.add(key);
            }
        }
        return new RowSyncPlan(removals, new ArrayList<>(visible));
    }
}
