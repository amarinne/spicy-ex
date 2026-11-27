package com.eza.spicyex.settings.panel;

import com.eza.spicyex.settings.values.ChineseMode;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChineseReadingSelectionTest {
    @Test public void restoresAllStoredModes() {
        for (ChineseMode mode : ChineseMode.values()) {
            ChineseReadingSelection selection = new ChineseReadingSelection(mode.storedLabel(), true);
            assertEquals(mode, selection.selected());
            assertFalse(selection.isDirty());
        }
    }

    @Test public void languageChangesKeepStyleAndCycleReplacesTheReading() {
        ChineseReadingSelection selection = new ChineseReadingSelection("auto-eza", true);
        selection.selectMode(ChineseMode.PINYIN);
        assertEquals(ChineseMode.PINYIN_EZA, selection.selected());
        selection.selectMode(ChineseMode.JYUTPING);
        assertEquals(ChineseMode.JYUTPING_EZA, selection.selected());
        selection.selectMode(ChineseMode.OFF);
        assertFalse(selection.hasStyle());
        assertEquals(ChineseMode.OFF, selection.selected());
        selection.selectMode(ChineseMode.CYCLE);
        assertFalse(selection.hasStyle());
        assertEquals(ChineseMode.CYCLE, selection.selected());
        selection.selectMode(ChineseMode.AUTO);
        assertFalse(selection.isDirty());
        selection.selectEza(false);
        assertTrue(selection.isDirty());
        assertEquals(ChineseMode.AUTO, selection.selected());
    }

    @Test public void missingPackOnlyDisablesFixedMandarinEza() {
        ChineseReadingSelection selection = new ChineseReadingSelection("pinyin-eza", false);
        assertFalse(selection.offersEza());
        assertEquals(ChineseMode.PINYIN, selection.selected());
        assertFalse(selection.isDirty());

        selection.selectMode(ChineseMode.JYUTPING);
        assertTrue(selection.offersEza());
        selection.selectEza(true);
        assertEquals(ChineseMode.JYUTPING_EZA, selection.selected());
        selection.selectMode(ChineseMode.AUTO);
        assertEquals(ChineseMode.AUTO_EZA, selection.selected());
        selection.selectMode(ChineseMode.PINYIN);
        assertEquals(ChineseMode.PINYIN, selection.selected());
    }
}
