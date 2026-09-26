package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.eza.spicyex.SpotifyTrack;

import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** A native hook capture reaches subscribers immediately; removal stops delivery. */
public class NativeLyricsSourceListenerTest {
    static final class FakeNativeLine {
        public String text = "hello";
        public long startTime = 1000L;
    }

    static final class FakeNativeMessage {
        public java.util.List<FakeNativeLine> lines = Collections.singletonList(new FakeNativeLine());
    }

    private static final SpotifyTrack TRACK = new SpotifyTrack(
            "Song", "Artist", "Album", "spotify:track:abc123", 0, "", 0, null, 180000, false);

    private static void capture(NativeLyricsSource source) {
        source.captureCandidate(TRACK, new FakeNativeMessage(),
                new Object[0], "test");
    }

    @Test
    public void captureNotifiesListenerWithUsableDocument() {
        NativeLyricsSource source = new NativeLyricsSource(null, null);
        AtomicReference<String> trackId = new AtomicReference<>();
        AtomicReference<LyricsDocument> document = new AtomicReference<>();
        source.addNativeListener((id, doc) -> {
            trackId.set(id);
            document.set(doc);
        });

        capture(source);

        assertEquals("abc123", trackId.get());
        assertNotNull(document.get());
        assertEquals(1, document.get().lines.size());
        assertEquals("hello", document.get().lines.get(0).text);
    }

    @Test
    public void removedListenerReceivesNothingFurther() {
        NativeLyricsSource source = new NativeLyricsSource(null, null);
        AtomicInteger calls = new AtomicInteger();
        NativeLyricsSource.NativeListener listener = (id, doc) -> calls.incrementAndGet();
        source.addNativeListener(listener);
        source.addNativeListener(listener);

        capture(source);
        source.removeNativeListener(listener);
        // A better-scoring capture still lands in the store but notifies nobody.
        source.captureCandidate(TRACK, new FakeNativeMessage(),
                new Object[0], "test");

        assertEquals(1, calls.get());
    }
}
