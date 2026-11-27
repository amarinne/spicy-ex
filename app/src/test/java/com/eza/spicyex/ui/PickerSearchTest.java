package com.eza.spicyex.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public class PickerSearchTest {
    @Test public void emptyQueryShowsEverything() {
        assertTrue(PickerSearch.matches("", "God Rays", "god-rays"));
        assertTrue(PickerSearch.matches("   ", "God Rays", "god-rays"));
        assertTrue(PickerSearch.matches(null, "God Rays"));
    }

    @Test public void matchesLabelsStableIdsAndSeparators() {
        assertTrue(PickerSearch.matches("god", "God Rays", "god-rays"));
        assertTrue(PickerSearch.matches("rays", "God Rays", "god-rays"));
        assertTrue(PickerSearch.matches("god-rays", "God Rays", "god-rays"));
        assertTrue(PickerSearch.matches("GOD RAY", "God Rays", "god-rays"));
        assertTrue(PickerSearch.matches("mesh", "Mesh Gradient", "mesh-gradient"));
        assertTrue(PickerSearch.matches("static mesh", "Static Mesh Gradient",
                "static-mesh-gradient"));
    }

    @Test public void everyTokenMustMatchAndUnknownTextDoesNot() {
        assertFalse(PickerSearch.matches("god smoke", "God Rays", "god-rays"));
        assertFalse(PickerSearch.matches("compose", "God Rays", "god-rays"));
        assertTrue(PickerSearch.matches("god smoke", "God Rays", "god-rays",
                "Smoke Ring", "smoke-ring"));
    }
}
