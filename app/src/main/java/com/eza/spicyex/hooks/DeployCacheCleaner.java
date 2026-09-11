package com.eza.spicyex.hooks;

import android.content.Context;
import android.content.SharedPreferences;

import com.eza.spicyex.BuildStamp;
import com.eza.spicyex.Settings;

import com.eza.spicyex.xposed.XpLog;

final class DeployCacheCleaner {
    private static final String PREFS_DEPLOY_STATE = "SpotifyPlusNativeDeployState";

    private DeployCacheCleaner() {
    }

    static synchronized void ensureCleared(Context context) {
        if (context == null) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_DEPLOY_STATE, Context.MODE_PRIVATE);
            String currentVersion = BuildStamp.NETWORK_CACHE_EPOCH;
            String lastVersion = prefs.getString(Settings.LAST_CACHE_CLEAR_VERSION.key, "");
            if (currentVersion.equals(lastVersion)) return;
            // Keep every durable lyric and processed artifact on device. Cache identities include
            // their processing/schema contracts, so incompatible records are ignored on restore;
            // deleting the stores here discards reusable, already-paid processing for no benefit.
            prefs.edit().putString(Settings.LAST_CACHE_CLEAR_VERSION.key, currentVersion).apply();
            XpLog.log(NativeSpicyLyricsHook.TAG + " deploy cache retained epoch=" + currentVersion
                    + " build=" + BuildStamp.FULL);
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " deploy cache clear failed: " + t);
        }
    }
}
