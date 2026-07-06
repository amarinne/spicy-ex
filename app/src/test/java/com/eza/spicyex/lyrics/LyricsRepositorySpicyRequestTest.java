package com.eza.spicyex.lyrics;

import org.junit.Test;

import okhttp3.Request;
import okio.Buffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class LyricsRepositorySpicyRequestTest {
    @Test
    public void lyricsQueryBodyMatchesDesktopJsonStringifyShape() {
        String body = LyricsRepository.buildSpicyLyricsQueryBody("4uLU6hMCjMI75M1A2tKUQC", "6.1.1");

        assertEquals("{\"queries\":[{\"operation\":\"lyrics\",\"variables\":{\"id\":\"4uLU6hMCjMI75M1A2tKUQC\",\"auth\":\"SpicyLyrics-WebAuth\"}}],\"client\":{\"version\":\"6.1.1\"}}", body);
        assertFalse(body.contains("\n"));
        assertFalse(body.contains(" "));
    }

    @Test
    public void lyricsRequestUsesExactJsonContentType() throws Exception {
        Request request = LyricsRepository.buildSpicyLyricsRequest("4uLU6hMCjMI75M1A2tKUQC", "6.1.1", "token");

        assertEquals("application/json", request.header("Content-Type"));
        assertEquals("application/json", request.body().contentType().toString());
        assertEquals(LyricsRepository.buildSpicyLyricsQueryBody("4uLU6hMCjMI75M1A2tKUQC", "6.1.1"), requestBody(request));
    }

    @Test
    public void zeroTokenDoesNotCreateBearerHeader() throws Exception {
        Request request = LyricsRepository.buildSpicyLyricsRequest("4uLU6hMCjMI75M1A2tKUQC", "6.1.1", "0");

        assertFalse(LyricsRepository.hasUsableToken(true, "0"));
        assertNull(request.header("SpicyLyrics-WebAuth"));
        assertFalse(request.toString().contains("Bearer 0"));
        assertFalse(requestBody(request).contains("Bearer 0"));
    }

    private static String requestBody(Request request) throws Exception {
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        return buffer.readUtf8();
    }
}
