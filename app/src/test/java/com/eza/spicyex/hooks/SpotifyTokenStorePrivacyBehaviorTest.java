package com.eza.spicyex.hooks;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import com.eza.spicyex.References;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import com.eza.spicyex.xposed.XpLog;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Exercises emitted logs at the capture and persisted-restore boundaries. */
public class SpotifyTokenStorePrivacyBehaviorTest {
    private static final String SECRET = "FIRST-private-access-token-fixture-LASTX";
    private final List<String> logs = new ArrayList<>();
    private final Map<Field, Object> savedState = new LinkedHashMap<>();
    private final Map<Field, Object> savedStore = new LinkedHashMap<>();
    private final FakeAndroidContext context = new FakeAndroidContext();
    private Object state;
    private Object oldLogger;
    private Activity oldActivity;
    private String oldMirror;
    private Activity activity;

    @Before public void setUp() throws Exception {
        oldLogger = field(XpLog.class, "api").get(null);
        XpLog.attach((XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> {
                    if ("log".equals(method.getName())) logs.add((String) args[2]);
                    return null;
                }));
        oldActivity = References.currentActivity();
        oldMirror = References.accessToken;
        activity = new Activity() {
            @Override public Context getApplicationContext() { return context; }
        };
        References.setCurrentActivity(activity);
        state = field(SpotifyTokenStore.class, "STATE").get(null);
        for (String name : new String[]{"token", "capturedAtMillis", "expiresAtMillis",
                "generation", "invalidated"}) {
            Field field = field(SpotifyTokenState.class, name);
            savedState.put(field, field.get(state));
        }
        for (String name : new String[]{"hasPersistedMetadata", "lastPersistedCapturedAtMillis",
                "lastPersistedExpiresAtMillis"}) {
            Field field = field(SpotifyTokenStore.class, name);
            savedStore.put(field, field.get(null));
        }
        clearMemory();
    }

    @After public void tearDown() throws Exception {
        for (Map.Entry<Field, Object> entry : savedState.entrySet()) entry.getKey().set(state, entry.getValue());
        for (Map.Entry<Field, Object> entry : savedStore.entrySet()) entry.getKey().set(null, entry.getValue());
        References.setCurrentActivity(oldActivity);
        References.accessToken = oldMirror;
        XpLog.attach((XposedInterface) oldLogger);
    }

    @Test public void captureLogsOnlyLifecycleMetadataAndPersistsAuthorization() {
        long now = System.currentTimeMillis();
        SpotifyTokenStore.store(SECRET, now, now + 3_600_000L, "fixture-header");

        assertEquals(SECRET, SpotifyTokenStore.authorization(now).token());
        assertEquals(SECRET, prefs().getString("native_spotify_access_token", ""));
        assertEquals(now, prefs().getLong("native_spotify_token_captured_at", 0L));
        assertPrivateLog("captured Spotify access token source=fixture-header generation=1 hasObservedExpiry=true");
    }

    @Test public void shortCaptureAlsoOmitsTokenLength() {
        SpotifyTokenStore.store("tiny", System.currentTimeMillis(), 0L, "fixture-header");
        assertPrivateLog("captured Spotify access token source=fixture-header generation=1 hasObservedExpiry=false");
        assertFalse(logs.toString().contains("tiny"));
    }

    @Test public void restoreLogsOnlyLifecycleMetadataAndKeepsPersistedGeneration() {
        long now = System.currentTimeMillis();
        seed(SECRET, now - 1000L, now + 3_600_000L, 7);
        SpotifyTokenStore.restore(context);

        assertEquals(SECRET, SpotifyTokenStore.authorization(now).token());
        assertEquals(7, SpotifyTokenStore.generation());
        assertEquals(SECRET, References.accessToken);
        assertEquals(now - 1000L, prefs().getLong("native_spotify_token_captured_at", 0L));
        assertPrivateLog("restored persisted Spotify access token generation=7 hasObservedExpiry=true");
    }

    @Test public void duplicateRefreshAndInvalidationKeepTheirPersistenceRules() throws Exception {
        long now = System.currentTimeMillis();
        SpotifyTokenStore.store(SECRET, now, 0L, "fixture-header");
        int generation = SpotifyTokenStore.generation();
        SpotifyTokenStore.store(SECRET, now + 1L, 0L, "fixture-header");
        assertEquals(generation, SpotifyTokenStore.generation());
        assertEquals(now, prefs().getLong("native_spotify_token_captured_at", 0L));
        long refresh = now + SpotifyTokenStore.PERSIST_REFRESH_MIN_INTERVAL_MILLIS;
        SpotifyTokenStore.store(SECRET, refresh, 0L, "fixture-header");
        assertEquals(refresh, prefs().getLong("native_spotify_token_captured_at", 0L));
        assertFalse(SpotifyTokenStore.invalidate(generation - 1));
        assertTrue(SpotifyTokenStore.invalidate(generation));
        assertFalse(prefs().contains("native_spotify_access_token"));
        SpotifyTokenStore.store(SECRET, refresh + 1L, 0L, "fixture-header");
        assertNull(SpotifyTokenStore.authorization(refresh + 1L));
        assertFalse(prefs().contains("native_spotify_access_token"));
        assertPrivateLog("captured Spotify access token source=fixture-header generation=1 hasObservedExpiry=false");
        clearMemory();
        SpotifyTokenStore.restore(context);
        assertNull(SpotifyTokenStore.authorization(System.currentTimeMillis()));
    }

    @Test public void staleAndLegacyRestoreClearSecretsWithoutRestoringThem() throws Exception {
        long now = System.currentTimeMillis();
        seed(SECRET, now - SpotifyTokenStore.MAX_RESTORE_AGE_MILLIS - 1000L, 0L, 3);
        SpotifyTokenStore.restore(context);
        assertNull(SpotifyTokenStore.authorization(now));
        assertFalse(prefs().contains("native_spotify_access_token"));
        assertTrue(logs.get(0).contains("persisted token rejected by freshness rule"));
        clearMemory();
        seed(SECRET, 0L, 0L, 3);
        SpotifyTokenStore.restore(context);
        assertNull(SpotifyTokenStore.authorization(now));
        assertFalse(prefs().contains("native_spotify_access_token"));
        assertEquals(2, logs.size());
        assertTrue(logs.get(1).contains("legacy token preference migrated"));
        assertNoSecretMetadata();
    }

    private void assertPrivateLog(String expected) {
        assertNoSecretMetadata();
        assertEquals(1, logs.size());
        assertEquals(NativeSpicyLyricsHook.TAG + " " + expected, logs.get(0));
    }

    private void assertNoSecretMetadata() {
        String emitted = logs.toString();
        assertFalse("full token leaked", emitted.contains(SECRET));
        assertFalse("token prefix leaked", emitted.contains("FIRST"));
        assertFalse("token suffix leaked", emitted.contains("LASTX"));
        assertFalse("token length leaked", emitted.contains("tokenLength"));
        assertFalse("token preview leaked", emitted.contains("tokenPreview"));
    }

    private SharedPreferences prefs() { return context.file(SpotifyPlusConfig.PREFS_NAME); }

    private void seed(String token, long capturedAt, long expiresAt, int generation) {
        prefs().edit().putString("native_spotify_access_token", token)
                .putLong("native_spotify_token_captured_at", capturedAt)
                .putLong("native_spotify_token_expires_at", expiresAt)
                .putInt("native_spotify_token_generation", generation).apply();
    }

    private void clearMemory() throws Exception {
        field(SpotifyTokenState.class, "token").set(state, "");
        field(SpotifyTokenState.class, "capturedAtMillis").setLong(state, 0L);
        field(SpotifyTokenState.class, "expiresAtMillis").setLong(state, 0L);
        field(SpotifyTokenState.class, "generation").setInt(state, 0);
        field(SpotifyTokenState.class, "invalidated").setBoolean(state, false);
        field(SpotifyTokenStore.class, "hasPersistedMetadata").setBoolean(null, false);
        field(SpotifyTokenStore.class, "lastPersistedCapturedAtMillis").setLong(null, 0L);
        field(SpotifyTokenStore.class, "lastPersistedExpiresAtMillis").setLong(null, 0L);
        References.accessToken = "";
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
