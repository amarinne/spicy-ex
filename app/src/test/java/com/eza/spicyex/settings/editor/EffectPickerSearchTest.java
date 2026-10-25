package com.eza.spicyex.settings.editor;

import org.junit.Test;
import static org.junit.Assert.*;

public class EffectPickerSearchTest {
    @Test public void emptyQueryShowsEverything() {
        assertTrue(EffectPickerSearch.matches("", "God Rays", "god-rays"));
        assertTrue(EffectPickerSearch.matches("   ", "God Rays", "god-rays"));
        assertTrue(EffectPickerSearch.matches(null, "God Rays"));
    }

    @Test public void matchesLabelsStableIdsAndSeparators() {
        assertTrue(EffectPickerSearch.matches("god", "God Rays", "god-rays"));
        assertTrue(EffectPickerSearch.matches("rays", "God Rays", "god-rays"));
        assertTrue(EffectPickerSearch.matches("god-rays", "God Rays", "god-rays"));
        assertTrue(EffectPickerSearch.matches("GOD RAY", "God Rays", "god-rays"));
        assertTrue(EffectPickerSearch.matches("mesh", "Mesh Gradient", "mesh-gradient"));
        assertTrue(EffectPickerSearch.matches("static mesh", "Static Mesh Gradient",
                "static-mesh-gradient"));
    }

    @Test public void everyTokenMustMatchAndUnknownTextDoesNot() {
        assertFalse(EffectPickerSearch.matches("god smoke", "God Rays", "god-rays"));
        assertFalse(EffectPickerSearch.matches("compose", "God Rays", "god-rays"));
        assertTrue(EffectPickerSearch.matches("god smoke", "God Rays", "god-rays",
                "Smoke Ring", "smoke-ring"));
    }
}
