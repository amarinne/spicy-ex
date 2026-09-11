package com.eza.spicyex.hooks;

import android.content.Context;
import android.content.SharedPreferences;

import com.eza.spicyex.References;

import com.eza.spicyex.xposed.XpLog;
import com.eza.spicyex.xposed.XpReflect;

/**
 * Process-wide owner of the captured Spotify access-token lifecycle (packet M2).
 *
 * <p>Every capture path (OkHttp Authorization headers and Spotify auth response objects) routes
 * through {@link #store}; the authoritative state is the single {@link SpotifyTokenState} owned
 * here, not the legacy {@code References.accessToken} raw string, which is only a compatibility
 * mirror for read-only consumers and is refreshed from this store. Capture never consults the
 * {@code SEND_TOKEN} setting: capturing stays local, and the later request code keeps deciding
 * whether the token may be sent.
 *
 * <p><b>Persistence.</b> The token lives under the historical {@code native_spotify_access_token}
 * key of the {@code SpotifyPlus} preferences; the captured timestamp, observed expiry, and the
 * generation-compatible metadata live in dedicated sibling keys. Persistence is throttled for
 * duplicate-token refreshes because header capture fires per request. A tombstoned epoch is never
 * persisted, and invalidation clears the persisted entry so a rejected token cannot be resurrected
 * by a process restart.
 *
 * <p><b>Restore freshness rule (deliberate, conservative).</b> Restored data must pass:
 * <ul>
 *   <li>known observed expiry — acceptable only strictly before
 *       {@code expiresAt - SpotifyTokenState.EXPIRY_SAFETY_MARGIN_MILLIS};</li>
 *   <li>unknown expiry — acceptable only when {@code now - capturedAt <= MAX_RESTORE_AGE_MILLIS}.</li>
 * </ul>
 * {@link #MAX_RESTORE_AGE_MILLIS} is 30 minutes: half of Spotify's nominal 3600&nbsp;s access-token
 * lifetime, so an unknown-expiry token is only restored while it should still have meaningful
 * validity left even with clock skew. A capture time in the future is rejected as unverifiable.
 *
 * <p><b>Legacy migration.</b> The pre-M2 preference stored only the raw token with no timestamp, so
 * its age can never be verified. Such an entry is never restored as usable; it is cleared once
 * (without logging or leaking its value) and repopulated with full metadata by the next valid
 * capture, which Spotify's cold-start auth refresh provides within seconds.
 *
 * <p><b>Privacy.</b> No log line, key, or failure message ever contains the token text or its
 * length; only operation, source class, and non-secret generation/freshness state are logged.
 * Failure logs carry the exception class name only.
 *
 * <p><b>Thread-safety:</b> static methods synchronize on this class; the model adds its own
 * per-instance monitor.
 */
final class SpotifyTokenStore {
    private static final String PREFS_NAME = "SpotifyPlus";
    private static final String KEY_TOKEN = "native_spotify_access_token";
    private static final String KEY_CAPTURED_AT = "native_spotify_token_captured_at";
    private static final String KEY_EXPIRES_AT = "native_spotify_token_expires_at";
    private static final String KEY_GENERATION = "native_spotify_token_generation";

    /** Conservative restore ceiling for unknown-expiry captures; see class javadoc. */
    static final long MAX_RESTORE_AGE_MILLIS = 30L * 60_000L;

    /**
     * Duplicate-token refreshes update the captured timestamp in the model but persist at most this
     * often, because OkHttp header capture fires on every authorized request.
     */
    static final long PERSIST_REFRESH_MIN_INTERVAL_MILLIS = 5L * 60_000L;

    private static final SpotifyTokenState STATE = new SpotifyTokenState();

    private static boolean hasPersistedMetadata;
    private static long lastPersistedCapturedAtMillis;
    private static long lastPersistedExpiresAtMillis;

    private SpotifyTokenStore() {
    }

    /**
     * Single capture path for every token source. A different usable token advances the generation;
     * a duplicate token follows the M1 policy inside {@link SpotifyTokenState#capture} (metadata
     * refresh while healthy, no-op while tombstoned).
     *
     * @param expiresAtMillis observed expiry, or any value &lt;= 0 when unknown
     */
    static synchronized void store(String tokenText, long capturedAtMillis, long expiresAtMillis, String source) {
        if (isBlank(tokenText)) return;
        boolean advanced = STATE.capture(tokenText, capturedAtMillis, expiresAtMillis);
        if (STATE.isInvalidated() || !STATE.hasToken()) {
            // Tombstoned epoch: never persist, never log a capture, never resurrect the token.
            syncMirror();
            return;
        }
        boolean expiresAtChanged = expiresAtMillis > 0 && expiresAtMillis != lastPersistedExpiresAtMillis;
        boolean refreshDue = hasPersistedMetadata
                && capturedAtMillis - lastPersistedCapturedAtMillis >= PERSIST_REFRESH_MIN_INTERVAL_MILLIS;
        if (advanced || !hasPersistedMetadata || expiresAtChanged || refreshDue) {
            persistCurrent(tokenText);
        }
        syncMirror();
        if (advanced) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " captured Spotify access token"
                    + " source=" + source
                    + " tokenPreview=" + tokenPreview(tokenText)
                    + " tokenLength=" + tokenText.length()
                    + " generation=" + STATE.generation()
                    + " hasObservedExpiry=" + (STATE.expiresAtMillis() > 0));
        }
    }

    /**
     * Restores persisted token state at process start. Only data that passes
     * {@link #isFreshForRestore} is admitted; everything else (including legacy raw entries without
     * a captured timestamp) is cleared without leaking its value and awaits the next live capture.
     */
    static synchronized void restore(Context context) {
        if (context == null || STATE.hasToken()) return;
        try {
            SharedPreferences prefs = prefs(context);
            if (prefs == null) return;
            String token = prefs.getString(KEY_TOKEN, "");
            if (isBlank(token) || "0".equals(token)) return;
            long capturedAt = prefs.getLong(KEY_CAPTURED_AT, 0L);
            long expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L);
            int generation = prefs.getInt(KEY_GENERATION, 0);
            long now = System.currentTimeMillis();
            if (capturedAt <= 0) {
                // Legacy raw-token entry: no captured timestamp, freshness unverifiable. Clear it
                // (value and length are never logged) so only metadata-complete captures survive.
                clearPersisted(prefs);
                XpLog.log(NativeSpicyLyricsHook.TAG
                        + " legacy token preference migrated: freshness unverifiable, cleared for repopulation");
                return;
            }
            if (!isFreshForRestore(now, capturedAt, expiresAt)) {
                clearPersisted(prefs);
                XpLog.log(NativeSpicyLyricsHook.TAG
                        + " persisted token rejected by freshness rule"
                        + " ageMillis=" + Math.max(0L, now - capturedAt)
                        + " hasObservedExpiry=" + (expiresAt > 0));
                return;
            }
            STATE.restore(token.trim(), capturedAt, expiresAt, generation);
            persistValues(prefs, token.trim(), capturedAt, expiresAt, STATE.generation());
            syncMirror();
            XpLog.log(NativeSpicyLyricsHook.TAG
                    + " restored persisted Spotify access token"
                    + " tokenPreview=" + tokenPreview(token)
                    + " tokenLength=" + token.length()
                    + " generation=" + STATE.generation()
                    + " hasObservedExpiry=" + (STATE.expiresAtMillis() > 0));
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG
                    + " token restore failed type=" + t.getClass().getName());
        }
    }


    /** Package-private M3 seam: current usable authorization snapshot, or {@code null} when none. */
    static SpotifyTokenState.Authorized authorization(long nowMillis) {
        return STATE.authorization(nowMillis);
    }

    /** Package-private M3 seam: current non-secret token generation. */
    static int generation() {
        return STATE.generation();
    }

    /**
     * Package-private M3 seam: tombstones exactly {@code expectedGeneration}; a stale generation can
     * never invalidate a newer token. The persisted entry is cleared on success so a rejected token
     * cannot be resurrected after a process restart.
     */
    static synchronized boolean invalidate(int expectedGeneration) {
        boolean invalidated = STATE.invalidate(expectedGeneration);
        if (invalidated) {
            Context context = appContext();
            if (context != null) {
                try {
                    clearPersisted(prefs(context));
                } catch (Throwable t) {
                    XpLog.log(NativeSpicyLyricsHook.TAG
                            + " token persist failed op=invalidate type=" + t.getClass().getName());
                }
            }
            syncMirror();
        }
        return invalidated;
    }

    /**
     * Pure restore-freshness decision, shared by the restore path and reusable by later packets:
     * known expiry must clear the shared safety margin, unknown expiry must sit inside
     * {@link #MAX_RESTORE_AGE_MILLIS}, and the capture time must not lie in the future.
     */
    static boolean isFreshForRestore(long nowMillis, long capturedAtMillis, long expiresAtMillis) {
        if (capturedAtMillis <= 0 || nowMillis < capturedAtMillis) return false;
        if (expiresAtMillis > 0) {
            return nowMillis < expiresAtMillis - SpotifyTokenState.EXPIRY_SAFETY_MARGIN_MILLIS;
        }
        return nowMillis - capturedAtMillis <= MAX_RESTORE_AGE_MILLIS;
    }

    private static void persistCurrent(String tokenText) {
        Context context = appContext();
        if (context == null) return; // in-memory state stays authoritative; retried on next capture
        try {
            persistValues(prefs(context), tokenText,
                    STATE.capturedAtMillis(), STATE.expiresAtMillis(), STATE.generation());
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG
                    + " token persist failed op=capture type=" + t.getClass().getName());
        }
    }

    private static void persistValues(SharedPreferences prefs, String token,
                                      long capturedAt, long expiresAt, int generation) {
        prefs.edit()
                .putString(KEY_TOKEN, token)
                .putLong(KEY_CAPTURED_AT, capturedAt)
                .putLong(KEY_EXPIRES_AT, Math.max(0L, expiresAt))
                .putInt(KEY_GENERATION, generation)
                .apply();
        hasPersistedMetadata = true;
        lastPersistedCapturedAtMillis = capturedAt;
        lastPersistedExpiresAtMillis = Math.max(0L, expiresAt);
    }

    private static void clearPersisted(SharedPreferences prefs) {
        prefs.edit()
                .remove(KEY_TOKEN)
                .remove(KEY_CAPTURED_AT)
                .remove(KEY_EXPIRES_AT)
                .remove(KEY_GENERATION)
                .apply();
        hasPersistedMetadata = false;
        lastPersistedCapturedAtMillis = 0L;
        lastPersistedExpiresAtMillis = 0L;
    }

    /** Refreshes the legacy {@code References.accessToken} mirror from the authoritative state. */
    private static void syncMirror() {
        SpotifyTokenState.Authorized authorized = STATE.authorization(System.currentTimeMillis());
        References.accessToken = authorized == null ? "" : authorized.token();
    }

    private static SharedPreferences prefs(Context context) {
        Context app = context.getApplicationContext();
        return (app != null ? app : context)
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static Context appContext() {
        android.app.Activity activity = References.currentActivity();
        if (activity != null) return activity.getApplicationContext();
        try {
            Object app = XpReflect.callStaticMethod(
                    XpReflect.findClass("android.app.ActivityThread", null),
                    "currentApplication");
            if (app instanceof Context) return ((Context) app).getApplicationContext();
        } catch (Throwable ignored) {
        }
        return null;
    }

    static String tokenPreview(String token) {
        if (token == null) return "<absent>";
        if (token.length() <= 10) return "<redacted>";
        return token.substring(0, 5) + "…" + token.substring(token.length() - 5);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
