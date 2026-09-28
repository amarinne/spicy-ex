package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LyricsScrollEndLimitTest {
    @Test
    public void stopsWhenFooterTextIsVisible() {
        // The footer has another 180 px of padding below its text. That padding is not scrollable
        // extra distance once the source label is visible above the viewport's bottom margin.
        assertEquals(424, NativeSpicyShellViewImpl.scrollEndLimit(300, 1000, 600, 0, 24));
        assertEquals(500, NativeSpicyShellViewImpl.scrollEndLimit(500, 1000, 600, 0, 24));
    }
}
