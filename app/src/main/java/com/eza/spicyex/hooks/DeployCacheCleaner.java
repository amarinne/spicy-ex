package com.eza.spicyex.hooks;

import android.content.Context;
import android.content.SharedPreferences;

import com.eza.spicyex.BuildStamp;
import com.eza.spicyex.Settings;
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
            // Network/processing caches should only be invalidated when their semantics change.
            // UI-only builds keep this epoch stable so they do not burn Google requests on reopen.
            // A build epoch may invalidate derived implementation artifacts, never the canonical
            // source they are derived from. CanonicalSourceCache and the raw provider payload
            // cache are therefore deliberately absent below: clearing them would re-fetch every
            // track's lyrics on a deploy that only changed how they are processed.
            // AIPaidArtifactCache is absent for a stronger reason: a deploy must never destroy
            // output the owner paid for. Only an explicit owner action may clear it.
            String currentVersion = BuildStamp.NETWORK_CACHE_EPOCH;
            String lastVersion = prefs.getString(Settings.LAST_CACHE_CLEAR_VERSION.key, "");
            if (currentVersion.equals(lastVersion)) return;
            LyricCaches.clearGoogle(context);
            LyricCaches.clearProcessed(context);
            prefs.edit().putString(Settings.LAST_CACHE_CLEAR_VERSION.key, currentVersion).apply();
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " deploy cache clear epoch=" + currentVersion
                    + " build=" + BuildStamp.FULL);
        } catch (Throwable t) {
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " deploy cache clear failed: " + t);
        }
    }
}
