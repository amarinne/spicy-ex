package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LyricsFetchErrorsTest {
    @Test
    public void durableNoLyricsMatchesOnlyConfirmedNoResultErrors() {
        assertTrue(LyricsFetchErrors.isDurableNoLyrics("Spicy lyrics empty; LRCLIB empty"));
        assertTrue(LyricsFetchErrors.isDurableNoLyrics("native miss; no LRCLIB result"));
        assertTrue(LyricsFetchErrors.isDurableNoLyrics("native miss; LRCLIB HTTP 404"));
        assertTrue(LyricsFetchErrors.isDurableNoLyrics("Lyrics unavailable (cached no-result)"));
    }

    @Test
    public void transientFailuresStayRetryable() {
        assertFalse(LyricsFetchErrors.isDurableNoLyrics("Spicy network failed: timeout; LRCLIB failed: timeout"));
        assertFalse(LyricsFetchErrors.isDurableNoLyrics("native miss; LRCLIB HTTP 500"));
        assertFalse(LyricsFetchErrors.isDurableNoLyrics("LRCLIB parse failed: malformed json"));
        assertFalse(LyricsFetchErrors.isDurableNoLyrics(""));
        assertFalse(LyricsFetchErrors.isDurableNoLyrics(null));
    }
}
