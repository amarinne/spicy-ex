package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NeteaseEapiTest {
    @Test
    public void signedParamsAreDeterministicUppercaseHex() {
        String first = NeteaseEapi.params("/api/song/lyric/v1", "{\"id\":\"1\"}");
        String second = NeteaseEapi.params("/api/song/lyric/v1", "{\"id\":\"1\"}");

        assertNotNull(first);
        assertEquals(first, second);
        assertTrue(first.matches("[0-9A-F]+"));
    }

    @Test
    public void differentPayloadsSignDifferently() {
        assertTrue(!NeteaseEapi.params("/api/song/lyric/v1", "{\"id\":\"1\"}")
                .equals(NeteaseEapi.params("/api/song/lyric/v1", "{\"id\":\"2\"}")));
    }

    @Test
    public void cookieAndHeaderCarryNoAccount() {
        assertTrue(NeteaseEapi.cookieHeader().endsWith("MUSIC_U="));
        assertTrue(NeteaseEapi.headerJson().contains("\"MUSIC_U\":\"\""));
    }
}
