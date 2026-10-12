package com.eza.spicyex.lyrics;

import org.junit.Test;

import static org.junit.Assert.*;

public class ResponseCreditOutroTest {
    @Test public void compactCreditStartsOnlyAfterFinalVocalHold() {
        ResponseCreditOutro.Window window = new ResponseCreditOutro.Window();
        assertFalse(window.update("song", true, true, true, 1000, 1699, true, false, 10));
        assertTrue(window.update("song", true, true, true, 1000, 1700, true, false, 10));
        assertTrue(window.update("song", true, true, true, 1000, 1700, true, true, 20));
        assertFalse(window.update("song", true, true, true, 1000, 1700, true, true, 10_021));
    }

    @Test public void seekingBackClearsOutroAndAllowsAnotherEpisode() {
        ResponseCreditOutro.Window window = new ResponseCreditOutro.Window();
        assertTrue(window.update("song", true, true, true, 1000, 1700, true, false, 10));
        assertTrue(window.update("song", true, true, true, 1000, 1700, true, true, 20));
        assertFalse(window.update("song", true, true, true, 1000, 1200, true, true, 30));
        assertTrue(window.update("song", true, true, true, 1000, 1700, true, false, 40));
    }

    @Test public void staticLyricsNeverEnterCreditOutro() {
        ResponseCreditOutro.Window window = new ResponseCreditOutro.Window();
        assertFalse(window.update("song", true, true, false, 1000, 1700, true, false, 10));
    }
}
