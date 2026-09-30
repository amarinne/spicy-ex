package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import okhttp3.OkHttpClient;
import org.junit.Test;

/**
 * F3: a stale fetch error must not remove its replacement registered under the same key.
 * Ordering: A runs, invalidate drops A, B registers under the same key, A's delayed error
 * lands, then B succeeds. B's callbacks must still receive B exactly once.
 */
public class LyricsFetchCoordinatorStaleErrorTest {
    private static final class RecordingCallback
            implements NativeSpicyLyricsHook.LyricsResultCallback {
        final List<LyricsDocument> successes = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override public void onSuccess(LyricsDocument document) {
            successes.add(document);
        }

        @Override public void onError(String error) {
            errors.add(error);
        }
    }

    private LyricsFetchCoordinator coordinator() {
        return new LyricsFetchCoordinator(new OkHttpClient(), () -> null, 0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inFlight(LyricsFetchCoordinator coordinator) throws Exception {
        Field field = LyricsFetchCoordinator.class.getDeclaredField("inFlight");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(coordinator);
    }

    private Object operation(String key) throws Exception {
        Class<?> type = Class.forName(
                "com.eza.spicyex.hooks.LyricsFetchCoordinator$InFlightFetch");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(key, false);
    }

    @SuppressWarnings("unchecked")
    private List<NativeSpicyLyricsHook.LyricsResultCallback> callbacks(Object operation)
            throws Exception {
        Field field = operation.getClass().getDeclaredField("callbacks");
        field.setAccessible(true);
        return (List<NativeSpicyLyricsHook.LyricsResultCallback>) field.get(operation);
    }

    private void deliver(LyricsFetchCoordinator coordinator, Object operation, String method,
            Object argument) throws Exception {
        Class<?> argumentType = "deliverError".equals(method) ? String.class : LyricsDocument.class;
        Method deliver = LyricsFetchCoordinator.class.getDeclaredMethod(method,
                Class.forName("com.eza.spicyex.hooks.LyricsFetchCoordinator$InFlightFetch"),
                argumentType);
        deliver.setAccessible(true);
        deliver.invoke(coordinator, operation, argument);
    }

    @Test
    public void staleErrorKeepsReplacementAndReplacementSuccessDeliversOnce() throws Exception {
        LyricsFetchCoordinator coordinator = coordinator();
        Map<String, Object> inFlight = inFlight(coordinator);
        String key = "abc|karaoke-verbatim";

        Object stale = operation(key);
        RecordingCallback staleCallback = new RecordingCallback();
        callbacks(stale).add(staleCallback);
        inFlight.put(key, stale);

        // invalidate + replacement request under the same key.
        inFlight.remove(key);
        Object replacement = operation(key);
        RecordingCallback replacementCallback = new RecordingCallback();
        callbacks(replacement).add(replacementCallback);
        inFlight.put(key, replacement);

        // The stale error must not touch the replacement.
        deliver(coordinator, stale, "deliverError", "stale boom");
        assertSame(replacement, inFlight.get(key));
        assertTrue(staleCallback.errors.isEmpty());
        assertTrue(replacementCallback.errors.isEmpty());
        assertEquals(1, callbacks(replacement).size());

        // The replacement success still delivers exactly once, then retires the entry.
        LyricsDocument document = new LyricsDocument();
        document.lines.add(new LyricsLine());
        deliver(coordinator, replacement, "deliverSuccess", document);
        assertEquals(1, replacementCallback.successes.size());
        assertTrue(replacementCallback.errors.isEmpty());
        assertTrue(staleCallback.successes.isEmpty());
        assertTrue(inFlight.isEmpty());
    }
}
