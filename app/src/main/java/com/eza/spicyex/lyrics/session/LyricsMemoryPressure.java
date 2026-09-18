package com.eza.spicyex.lyrics.session;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;

import com.eza.spicyex.lyrics.LatinLanguageGate;
import com.eza.spicyex.lyrics.SpicyJapaneseChineseProcessor;

import com.eza.spicyex.xposed.XpLog;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-level memory-pressure fan-out owned by the module runtime.
 *
 * <p>language detector model, UniDic resources, and session detection rows are the module's only large
 * in-memory residents. On {@code TRIM_MEMORY_RUNNING_LOW} or stronger this releases all three and
 * leaves every durable cache on disk untouched, so the next track reloads lazily rather than
 * reprocessing.
 */
public final class LyricsMemoryPressure {
    private static final String TAG = "[SpotifyPlusMemoryPressure]";

    public interface Reclaimer {
        void onTrimMemory(int level);
    }

    private static final List<Reclaimer> RECLAIMERS = new CopyOnWriteArrayList<>();
    private static volatile boolean installed;

    private LyricsMemoryPressure() {
    }

    /** Idempotent; call from the hooked application's {@code attach}. */
    public static void install(Context context) {
        if (context == null || installed) return;
        try {
            Context app = context.getApplicationContext();
            if (app == null) return;
            app.registerComponentCallbacks(new ComponentCallbacks2() {
                @Override
                public void onTrimMemory(int level) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) dispatch(level);
                }

                @Override
                public void onLowMemory() {
                    dispatch(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL);
                }

                @Override
                public void onConfigurationChanged(Configuration newConfig) {
                }
            });
            installed = true;
        } catch (Throwable t) {
            XpLog.log(TAG + " install failed: " + t);
        }
    }

    public static void addReclaimer(Reclaimer reclaimer) {
        if (reclaimer != null) RECLAIMERS.add(reclaimer);
    }

    public static void removeReclaimer(Reclaimer reclaimer) {
        RECLAIMERS.remove(reclaimer);
    }

    /** Exposed for tests: runs the same release path without an Android callback. */
    public static void dispatch(int level) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return;
        XpLog.log(TAG + " trim level=" + level);
        try {
            LatinLanguageGate.trimMemory();
        } catch (Throwable ignored) {
        }
        try {
            SpicyJapaneseChineseProcessor.trimMemory();
        } catch (Throwable ignored) {
        }
        try {
            com.eza.spicyex.lyrics.ProviderTextDetectionStore.trimMemory();
        } catch (Throwable ignored) {
        }
        for (Reclaimer reclaimer : RECLAIMERS) {
            try {
                reclaimer.onTrimMemory(level);
            } catch (Throwable ignored) {
            }
        }
    }
}
