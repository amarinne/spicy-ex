package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MiniPlayerButtonGeometryTest {
    @Test
    public void adHeightKeepsButtonOnBottomControlRow() {
        assertEquals(950f, LyricsActivityTakeoverHook.controlRowCenterY(900f, 100, 100), 0.01f);
        assertEquals(950f, LyricsActivityTakeoverHook.controlRowCenterY(700f, 300, 100), 0.01f);
    }
}
