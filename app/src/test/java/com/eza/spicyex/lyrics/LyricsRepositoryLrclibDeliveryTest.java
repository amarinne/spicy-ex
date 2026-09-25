package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;

import com.eza.spicyex.SpotifyTrack;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/** One LRCLIB request reaches the consumer exactly once, on success and on failure. */
public class LyricsRepositoryLrclibDeliveryTest {

    private static final SpotifyTrack TRACK = new SpotifyTrack(
            "Song", "Artist", "Album", "spotify:track:test", 0, "", 0, null, 180000, false);

    private static JsonArray merged() {
        JsonObject candidate = new JsonObject();
        candidate.addProperty("trackName", "Song");
        candidate.addProperty("syncedLyrics", "[00:01.00] hello\n[00:02.00] world\n");
        candidate.addProperty("plainLyrics", "hello\nworld");
        JsonArray merged = new JsonArray();
        merged.add(candidate);
        return merged;
    }

    private static final class RecordingCallback implements LyricsRepository.ResultCallback {
        final AtomicInteger successes = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
        boolean throwOnSuccess;
        boolean throwOnError;

        @Override
        public void onSuccess(LyricsDocument document) {
            successes.incrementAndGet();
            if (throwOnSuccess) throw new IllegalStateException("consumer rejected the document");
        }

        @Override
        public void onError(String error) {
            errors.incrementAndGet();
            if (throwOnError) throw new IllegalStateException("error consumer rejected the report");
        }
    }

    private static void deliver(RecordingCallback callback, JsonArray merged) {
        new LyricsRepository(null, new LyricsParser(null), null, null)
                .deliverMergedLrclib(null, TRACK, 1, callback, "chain exhausted",
                        new LyricsProviderChain(1, null), false, merged);
    }

    @Test
    public void usableMergedResponseFiresSuccessExactlyOnce() {
        RecordingCallback callback = new RecordingCallback();
        deliver(callback, merged());

        assertEquals(1, callback.successes.get());
        assertEquals(0, callback.errors.get());
    }

    @Test
    public void aConsumerThatThrowsAfterSuccessDoesNotGetAnErrorOnTop() {
        RecordingCallback callback = new RecordingCallback();
        callback.throwOnSuccess = true;
        deliver(callback, merged());

        assertEquals(1, callback.successes.get());
        assertEquals(0, callback.errors.get());
    }

    @Test
    public void anUnusableMergedResponseReportsTheErrorOnce() {
        RecordingCallback callback = new RecordingCallback();
        deliver(callback, new JsonArray());

        assertEquals(0, callback.successes.get());
        assertEquals(1, callback.errors.get());
    }

    @Test
    public void aThrowingErrorConsumerStillReportsExactlyOnce() {
        RecordingCallback callback = new RecordingCallback();
        callback.throwOnError = true;
        deliver(callback, new JsonArray());

        assertEquals(0, callback.successes.get());
        assertEquals(1, callback.errors.get());
    }
}
