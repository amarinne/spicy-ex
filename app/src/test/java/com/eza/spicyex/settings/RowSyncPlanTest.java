package com.eza.spicyex.settings;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class RowSyncPlanTest {
    @Test
    public void stableSetPlansNoRemovals() {
        RowSyncPlan plan = RowSyncPlan.of(
                Arrays.asList("a", "b", "c"), Arrays.asList("a", "b", "c"));
        assertEquals(Collections.emptyList(), plan.removals);
        assertEquals(Arrays.asList("a", "b", "c"), plan.order);
    }

    @Test
    public void gatedRowsAreRemovals() {
        RowSyncPlan plan = RowSyncPlan.of(
                Arrays.asList("a", "c"), Arrays.asList("a", "b", "c"));
        assertEquals(Collections.singletonList("b"), plan.removals);
        assertEquals(Arrays.asList("a", "c"), plan.order);
    }

    @Test
    public void newlyVisibleRowsJoinInSchemaOrder() {
        RowSyncPlan plan = RowSyncPlan.of(
                Arrays.asList("b", "a", "d"), Arrays.asList("a", "b"));
        assertEquals(Collections.emptyList(), plan.removals);
        assertEquals(Arrays.asList("b", "a", "d"), plan.order);
    }

    @Test
    public void emptyCardBuildsEverything() {
        RowSyncPlan plan = RowSyncPlan.of(
                Arrays.asList("a", "b"), Collections.emptyList());
        assertEquals(Collections.emptyList(), plan.removals);
        assertEquals(Arrays.asList("a", "b"), plan.order);
    }

    @Test
    public void nullInputsPlanEmpty() {
        RowSyncPlan plan = RowSyncPlan.of(null, null);
        assertEquals(Collections.emptyList(), plan.removals);
        assertEquals(Collections.emptyList(), plan.order);
    }
}
