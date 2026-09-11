package com.eza.spicyex.lyrics;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import okhttp3.Request;
import okio.Buffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class LyricsRepositorySpicyRequestTest {
    private static final String TRACK_ID = "4uLU6hMCjMI75M1A2tKUQC";
    private static final String UPSTREAM_VERSION = "6.3.15";
    private static final String UPSTREAM_BODY = "{\"queries\":[{\"operation\":\"lyrics\",\"variables\":{\"id\":\"4uLU6hMCjMI75M1A2tKUQC\",\"auth\":\"SpicyLyrics-WebAuth\"}}],\"client\":{\"version\":\"6.3.15\"}}";
    private static final String UPSTREAM_BODY_HEX = "7b2271756572696573223a5b7b226f7065726174696f6e223a226c7972696373222c227661726961626c6573223a7b226964223a2234754c5536684d436a4d4937354d314132744b555143222c2261757468223a2253706963794c79726963732d57656241757468227d7d5d2c22636c69656e74223a7b2276657273696f6e223a22362e332e3135227d7d";

    @Test
    public void lyricsQueryBodyMatchesUpstream6315JsonStringifyBytes() {
        byte[] body = SpicyLyricsRequestContract.buildLyricsQueryBytes(TRACK_ID);

        assertEquals(UPSTREAM_VERSION, SpicyLyricsRequestContract.UPSTREAM_VERSION);
        assertEquals(UPSTREAM_BODY, new String(body, StandardCharsets.UTF_8));
        assertEquals(UPSTREAM_BODY_HEX, hex(body));
        assertEquals(139, body.length);
    }

    @Test
    public void jsonStringEscapingMatchesUpstreamForUtf8AndLoneSurrogate() {
        String unusualId = "A\"\\\n<é😀" + new String(new char[]{0xd800});
        byte[] body = SpicyLyricsRequestContract.buildLyricsQueryBytes(unusualId);

        assertEquals("7b2271756572696573223a5b7b226f7065726174696f6e223a226c7972696373222c227661726961626c6573223a7b226964223a22415c225c5c5c6e3cc3a9f09f98805c7564383030222c2261757468223a2253706963794c79726963732d57656241757468227d7d5d2c22636c69656e74223a7b2276657273696f6e223a22362e332e3135227d7d", hex(body));
        assertEquals(137, body.length);
    }

    @Test
    public void lyricsRequestUsesExactUpstream6315PayloadAndHeaders() throws Exception {
        Request request = SpicyLyricsRequestContract.buildLyricsRequest(TRACK_ID, "token");

        assertEquals("application/json", request.header("Content-Type"));
        assertEquals("application/json", request.body().contentType().toString());
        assertEquals(UPSTREAM_VERSION, request.header("SpicyLyrics-Version"));
        assertEquals("2", request.header("X-mode"));
        assertEquals("Bearer token", request.header("SpicyLyrics-WebAuth"));
        assertEquals(UPSTREAM_BODY_HEX, hex(requestBodyBytes(request)));
        assertEquals(139L, request.body().contentLength());
    }

    @Test
    public void zeroTokenDoesNotCreateBearerHeader() throws Exception {
        Request request = SpicyLyricsRequestContract.buildLyricsRequest(TRACK_ID, "0");

        assertFalse(SpicyLyricsRequestContract.hasUsableToken(true, "0"));
        assertNull(request.header("SpicyLyrics-WebAuth"));
        assertFalse(request.toString().contains("Bearer 0"));
        assertFalse(new String(requestBodyBytes(request), StandardCharsets.UTF_8).contains("Bearer 0"));
    }

    private static byte[] requestBodyBytes(Request request) throws Exception {
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        return buffer.readByteArray();
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }
}
