package com.eza.spicyex.diagnostics;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiagnosticEventBufferTest {
    @Test
    public void sanitizesContextAndNeverSerializesThrowableMessage() {
        RuntimeException error = new RuntimeException("spotify:track:secret Bearer token");
        DiagnosticEventBuffer.Event event = DiagnosticEventBuffer.event(
                10L,
                "lyrics\nfetch",
                "provider result",
                error,
                DiagnosticEventBuffer.context(
                        "provider", "Spicy Lyrics",
                        "language", "ja-JP",
                        "trackUri", "spotify:track:secret"));
        DiagnosticEventBuffer.Result result = DiagnosticEventBuffer.append("", event);

        assertTrue(result.jsonl.contains("java.lang.RuntimeException"));
        assertTrue(result.jsonl.contains("Spicy_Lyrics"));
        assertFalse(result.jsonl.contains("spotify:track"));
        assertFalse(result.jsonl.contains("Bearer"));
        assertFalse(result.jsonl.contains("trackUri"));
    }

    @Test
    public void boundsCountAndUtf8BytesWhileKeepingNewestEvents() {
        String jsonl = "";
        boolean truncated = false;
        for (int i = 0; i < 400; i++) {
            DiagnosticEventBuffer.Result result = DiagnosticEventBuffer.append(jsonl,
                    DiagnosticEventBuffer.event(i, "renderer", "event_" + i, null,
                            DiagnosticEventBuffer.context("status", "值" + i)));
            jsonl = result.jsonl;
            truncated |= result.truncated;
        }
        assertTrue(truncated);
        assertTrue(jsonl.split("\\n").length <= DiagnosticEventBuffer.MAX_EVENTS);
        assertTrue(jsonl.getBytes(StandardCharsets.UTF_8).length <= DiagnosticEventBuffer.MAX_BYTES);
        assertFalse(jsonl.contains("event_0"));
        assertTrue(jsonl.contains("event_399"));
    }

    @Test
    public void capturePolicyCoversRestartTimeoutAndDeduplication() {
        assertFalse(DiagnosticCapturePolicy.expired(100L, 200L, 300L, 400L, 1_000L));
        assertTrue(DiagnosticCapturePolicy.expired(100L, 200L, 300L, 1_200L, 1_000L));
        assertTrue(DiagnosticCapturePolicy.expired(100L, 200L, 99L, 300L, 1_000L));
        assertTrue(DiagnosticCapturePolicy.interruptedAfterRestore(true, false));
        assertFalse(DiagnosticCapturePolicy.interruptedAfterRestore(true, true));
        assertFalse(DiagnosticCapturePolicy.shouldRecord(1_000L, 2_000L, 5_000L));
        assertTrue(DiagnosticCapturePolicy.shouldRecord(1_000L, 6_000L, 5_000L));
    }
}
