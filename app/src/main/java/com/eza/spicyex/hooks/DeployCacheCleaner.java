package com.eza.spicyex.hooks;

import android.content.Context;
import android.content.SharedPreferences;

import com.eza.spicyex.BuildStamp;
import com.eza.spicyex.Settings;
import com.eza.spicyex.beautifullyrics.entities.LyricsResponseCache;
import com.eza.spicyex.beautifullyrics.entities.LyricsTranslator;
import com.eza.spicyex.lyrics.LyricCaches;

import de.robv.android.xposed.XposedBridge;

final class DeployCacheCleaner {
    private static final String PREFS_DEPLOY_STATE = "SpotifyPlusNativeDeployState";

    private DeployCacheCleaner() {
    }

    static synchronized void ensureCleared(Context context) {
        if (context == null) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_DEPLOY_STATE, Context.MODE_PRIVATE);
            // We run inside Spotify's process, so getPackageInfo("com.eza.spicyex") throws
            // NameNotFoundException and the cache was never cleared on deploy. Use our baked-in build
            // stamp instead - it changes every build, so each deploy clears stale cached responses.
            String currentVersion = BuildStamp.FULL;
            String lastVersion = prefs.getString(Settings.LAST_CACHE_CLEAR_VERSION.key, "");
            if (currentVersion.equals(lastVersion)) return;
            LyricsResponseCache.clear(context);
            LyricsTranslator.clearCache(context);
            LyricCaches.clearGoogle(context);
            LyricCaches.clearProcessed(context);
            prefs.edit().putString(Settings.LAST_CACHE_CLEAR_VERSION.key, currentVersion).apply();
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " deploy cache clear version=" + currentVersion);
        } catch (Throwable t) {
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " deploy cache clear failed: " + t);
        }
    }
}
