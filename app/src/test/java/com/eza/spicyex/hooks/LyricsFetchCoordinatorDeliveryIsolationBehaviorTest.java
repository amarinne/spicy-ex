package com.eza.spicyex.hooks;

import static org.junit.Assert.*;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.catalog.CatalogPolicy;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import com.eza.spicyex.lyrics.providers.SpicyOrgAccessState;
import com.eza.spicyex.lyrics.providers.SpicyOrgKeyStore;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import com.eza.spicyex.xposed.XpLog;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Drives the production delivery methods with the existing in-flight reflection fixture. */
public class LyricsFetchCoordinatorDeliveryIsolationBehaviorTest {
    private static final String PRIVATE_FAILURE = "private-token-or-lyrics-in-callback-error";
    private static final String KEY = "abc|tokenGen=none";
    private final LyricsFetchCoordinator coordinator =
            new LyricsFetchCoordinator(new OkHttpClient(), () -> null, 0);
    private final FakeAndroidContext context = new FakeAndroidContext();
    private final List<String> logs = new ArrayList<>();
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
    private Map<String, Object> inFlight;
    private Map<String, Long> warnTimes;
    private Map<String, Long> oldWarnTimes;
    private Object oldLogger;
    private Object oldDiagnosticContext;

    private static class RecordingCallback implements NativeSpicyLyricsHook.LyricsResultCallback {
        final List<LyricsDocument> successes = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        @Override public void onSuccess(LyricsDocument document) { successes.add(document); }
        @Override public void onError(String error) { errors.add(error); }
    }

    private static final class ThrowingCallback extends RecordingCallback {
        private final Runnable reenter;
        ThrowingCallback() { this(() -> {}); }
        ThrowingCallback(Runnable reenter) { this.reenter = reenter; }
        @Override public void onSuccess(LyricsDocument document) {
            super.onSuccess(document);
            document.lines.get(0).text = "subscriber mutation";
            reenter.run();
            throw new IllegalStateException(PRIVATE_FAILURE);
        }
        @Override public void onError(String error) {
            super.onError(error);
            reenter.run();
            throw new AssertionError(PRIVATE_FAILURE);
        }
    }

    @Before @SuppressWarnings("unchecked") public void setUp() throws Exception {
        inFlight = (Map<String, Object>) field(LyricsFetchCoordinator.class, "inFlight").get(coordinator);
        oldLogger = field(XpLog.class, "api").get(null);
        XpLog.attach((XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
                    if ("log".equals(method.getName())) logs.add((String) args[2]);
                    return null;
                }));
        oldDiagnosticContext = field(Diagnostics.class, "applicationContext").get(null);
        field(Diagnostics.class, "applicationContext").set(null, null);
        warnTimes = (Map<String, Long>) field(Diagnostics.class, "LAST_LOG_AT_MS").get(null);
        oldWarnTimes = new HashMap<>(warnTimes);
        warnTimes.clear();
    }

    @After public void tearDown() throws Exception {
        timer.shutdownNow();
        assertTrue("fixture timer did not stop", timer.awaitTermination(5L, TimeUnit.SECONDS));
        warnTimes.clear();
        warnTimes.putAll(oldWarnTimes);
        field(Diagnostics.class, "applicationContext").set(null, oldDiagnosticContext);
        XpLog.attach((XposedInterface) oldLogger);
    }

    @Test public void throwingSuccessStillDeliversIndependentCopiesExactlyOnce() throws Exception {
        ThrowingCallback first = new ThrowingCallback();
        RecordingCallback second = new RecordingCallback();
        RecordingCallback third = new RecordingCallback();
        Object operation = register(KEY, false, first, second, third);
        LyricsDocument document = document("spicy_api");

        success(operation, document);
        success(operation, document);

        assertEquals(1, first.successes.size());
        assertSingleSuccess(second, "spicy_api");
        assertSingleSuccess(third, "spicy_api");
        assertNotSame(document, second.successes.get(0));
        assertNotSame(second.successes.get(0), third.successes.get(0));
        assertNotSame(second.successes.get(0).lines.get(0), third.successes.get(0).lines.get(0));
        assertEquals("original lyrics", document.lines.get(0).text);
        assertTrue(inFlight.isEmpty());
        assertTrue(callbacks(operation).isEmpty());
        assertSafeDiagnostic("IllegalStateException");
    }

    @Test public void throwingErrorStillDeliversToLaterSubscribersExactlyOnce() throws Exception {
        ThrowingCallback first = new ThrowingCallback();
        RecordingCallback second = new RecordingCallback();
        Object operation = register(KEY, false, first, second);

        error(operation, "provider unavailable");
        error(operation, "duplicate error");

        assertEquals(1, first.errors.size());
        assertEquals(java.util.Collections.singletonList("provider unavailable"), second.errors);
        assertTrue(second.successes.isEmpty());
        assertTrue(inFlight.isEmpty());
        assertTrue(callbacks(operation).isEmpty());
        assertSafeDiagnostic("AssertionError");
    }

    @Test public void throwingPreviewSubscriberDoesNotBlockNetworkUpgradeOrFloodLogs() throws Exception {
        assertUpgrade("spicy_api_cache");
    }

    @Test public void throwingNativeBaselineSubscriberDoesNotBlockNetworkUpgrade() throws Exception {
        assertUpgrade("spotify_native");
    }

    private void assertUpgrade(String initialSource) throws Exception {
        ThrowingCallback first = new ThrowingCallback();
        RecordingCallback second = new RecordingCallback();
        Object operation = register(KEY, true, first, second);
        // Keep the real upgrade-retirement path without starting a shared runtime worker.
        java.util.concurrent.ScheduledFuture<?> expiry = timer.schedule(() -> {}, 1L, TimeUnit.DAYS);
        field(operation.getClass(), "expiry").set(operation, expiry);

        success(operation, document(initialSource));
        assertSame(operation, inFlight.get(KEY));
        LyricsDocument latest = (LyricsDocument) field(operation.getClass(), "latest").get(operation);
        assertEquals("original lyrics", latest.lines.get(0).text);
        assertFalse(expiry.isCancelled());
        success(operation, document("spicy_api"));

        assertEquals(2, first.successes.size());
        assertEquals(2, second.successes.size());
        assertEquals(initialSource, second.successes.get(0).fetchSource);
        assertEquals("spicy_api", second.successes.get(1).fetchSource);
        for (LyricsDocument received : second.successes) assertEquals("original lyrics", received.lines.get(0).text);
        assertTrue(second.errors.isEmpty());
        assertTrue(inFlight.isEmpty());
        assertTrue(expiry.isCancelled());
        assertSafeDiagnostic("IllegalStateException");
    }

    @Test public void reentrantSuccessReplacementSurvivesThrowAndStaleError() throws Exception {
        assertReentrantReplacement(true);
    }

    @Test public void reentrantErrorReplacementSurvivesThrowAndStaleError() throws Exception {
        assertReentrantReplacement(false);
    }

    private void assertReentrantReplacement(boolean succeeds) throws Exception {
        RecordingCallback replacementCallback = new RecordingCallback();
        Object replacement = operation(KEY, false);
        callbacks(replacement).add(replacementCallback);
        ThrowingCallback first = new ThrowingCallback(() -> {
            coordinator.invalidate(track());
            inFlight.put(KEY, replacement);
        });
        RecordingCallback second = new RecordingCallback();
        Object old = register(KEY, false, first, second);

        if (succeeds) success(old, document("spicy_api"));
        else error(old, "old provider failure");
        assertSame(replacement, inFlight.get(KEY));
        assertEquals(1, succeeds ? second.successes.size() : second.errors.size());
        error(old, "late stale failure");
        assertSame(replacement, inFlight.get(KEY));
        assertTrue(replacementCallback.errors.isEmpty());
        success(replacement, document("replacement"));
        assertSingleSuccess(replacementCallback, "replacement");
        assertTrue(inFlight.isEmpty());
    }

    @Test public void catalogReplayThrowDoesNotBreakLaterUpgradeDelivery() throws Exception {
        boolean karaoke = CatalogPolicy.read(context).karaokeOriginalLyrics;
        String key = LyricsFetchCoordinator.pickerKey("abc", SourceId.QQ, karaoke);
        assertReplay(key, () -> coordinator.fetchCatalogSource(context, track(), 0,
                SourceId.QQ, replayThrower));
    }

    @Test public void automaticReplayThrowDoesNotBreakLaterUpgradeDelivery() throws Exception {
        context.file(SpotifyPlusConfig.PREFS_NAME).edit().putBoolean(Settings.SEND_TOKEN.key, false).apply();
        String key = KEY + "|scope=none|spicyOrgEpoch=" + SpicyOrgKeyStore.epoch(context)
                + "|orgAccessRevision=" + SpicyOrgAccessState.revision(context);
        assertReplay(key, () -> coordinator.fetchLyrics(context, track(), 0, null, replayThrower));
    }

    private ThrowingCallback replayThrower;

    private void assertReplay(String key, Runnable join) throws Exception {
        RecordingCallback original = new RecordingCallback();
        Object operation = register(key, false, original);
        field(operation.getClass(), "latest").set(operation, document("spicy_api_cache"));
        replayThrower = new ThrowingCallback();
        join.run();
        assertEquals(1, replayThrower.successes.size());
        assertTrue(original.successes.isEmpty());
        assertSame(operation, inFlight.get(key));
        RecordingCallback later = new RecordingCallback();
        callbacks(operation).add(later);
        success(operation, document("spicy_api"));
        assertSingleSuccess(original, "spicy_api");
        assertSingleSuccess(later, "spicy_api");
        assertEquals(2, replayThrower.successes.size());
        assertTrue(inFlight.isEmpty());
        assertSafeDiagnostic("IllegalStateException");
    }

    private void assertSafeDiagnostic(String exceptionType) {
        assertEquals("one rate-limited diagnostic", 1, logs.size());
        assertTrue(logs.get(0).contains(exceptionType));
        assertFalse(logs.get(0).contains(PRIVATE_FAILURE));
        assertFalse(logs.get(0).contains("original lyrics"));
        assertFalse(logs.get(0).contains(KEY));
        assertFalse(logs.get(0).contains("\n"));
    }

    private void assertSingleSuccess(RecordingCallback callback, String source) {
        assertEquals(1, callback.successes.size());
        assertEquals(source, callback.successes.get(0).fetchSource);
        assertEquals("original lyrics", callback.successes.get(0).lines.get(0).text);
        assertTrue(callback.errors.isEmpty());
    }

    private static LyricsDocument document(String source) {
        LyricsDocument document = new LyricsDocument();
        document.fetchSource = source;
        LyricsLine line = new LyricsLine();
        line.text = "original lyrics";
        document.lines.add(line);
        return document;
    }

    private static SpotifyTrack track() {
        return new SpotifyTrack("Title", "Artist", "Album", "spotify:track:abc", 0L,
                null, 0L, null, 0L, false);
    }

    private Object register(String key, boolean upgrade, RecordingCallback... subscribers) throws Exception {
        Object operation = operation(key, upgrade);
        for (RecordingCallback callback : subscribers) callbacks(operation).add(callback);
        inFlight.put(key, operation);
        return operation;
    }

    private Object operation(String key, boolean upgrade) throws Exception {
        Class<?> type = Class.forName("com.eza.spicyex.hooks.LyricsFetchCoordinator$InFlightFetch");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(key, upgrade);
    }

    @SuppressWarnings("unchecked") private List<NativeSpicyLyricsHook.LyricsResultCallback> callbacks(Object operation)
            throws Exception {
        return (List<NativeSpicyLyricsHook.LyricsResultCallback>) field(operation.getClass(), "callbacks").get(operation);
    }

    private void success(Object operation, LyricsDocument document) throws Exception {
        deliver(operation, "deliverSuccess", LyricsDocument.class, document);
    }

    private void error(Object operation, String error) throws Exception {
        deliver(operation, "deliverError", String.class, error);
    }

    private void deliver(Object operation, String name, Class<?> argumentType, Object argument) throws Exception {
        Method method = LyricsFetchCoordinator.class.getDeclaredMethod(name, operation.getClass(), argumentType);
        method.setAccessible(true);
        method.invoke(coordinator, operation, argument);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
