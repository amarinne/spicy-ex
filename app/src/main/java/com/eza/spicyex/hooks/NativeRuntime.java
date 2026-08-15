package com.eza.spicyex.hooks;

import com.eza.spicyex.lyrics.SpicyProcessing;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

final class NativeRuntime {
    // W5 / D4: set our OWN client timeouts so a hung/slow Spicy upstream fails over promptly to
    // the native -> LRCLIB cascade instead of waiting indefinitely. This HTTP client is shared by
    // every lyrics call (Spicy /query, Spotify color-lyrics, LRCLIB, Google translate) - all are
    // small JSON, so a 15s read budget is ample and won't truncate any legitimate response.
    private static final int HTTP_CONNECT_TIMEOUT_SECONDS = 10;
    private static final int HTTP_READ_TIMEOUT_SECONDS = 15;
    private static final int HTTP_WRITE_TIMEOUT_SECONDS = 10;

    static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(HTTP_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(HTTP_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(HTTP_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();
    static final ScheduledExecutorService LYRICS_IO = Executors.newScheduledThreadPool(2);
    // Sound and Meaning get distinct bounded jobs. Neither lane may park the other's thread on
    // network I/O, and cancelling one never starves the other.
    /** Deterministic on-device reading work. Single-threaded: the romanizers are not reentrant. */
    static final ExecutorService SOUND_PROCESSOR = Executors.newSingleThreadExecutor();
    /** Reading fallback requests. Bounded fan-out; the lane executor never awaits these. */
    static final ExecutorService SOUND_WORKERS = Executors.newFixedThreadPool(2);
    /** Machine translation batches. Separate from every Sound thread. */
    static final ExecutorService MEANING_WORKERS = Executors.newFixedThreadPool(2);
    static final int GOOGLE_PROCESSING_VERSION = SpicyProcessing.PROCESSING_VERSION + 2;
    static final int LYRIC_FULL_RENDER_THRESHOLD = 72;
    static final int LYRIC_WINDOW_BEFORE_ACTIVE = 18;
    static final int LYRIC_WINDOW_AFTER_ACTIVE = 24;
    static final int LYRIC_WINDOW_EDGE_BUFFER = 7;
    static final int LYRIC_ESTIMATED_ROW_HEIGHT_DP = 74;
    static final long SCROLL_SETTLE_REMEASURE_DELAY_MS = 140;

    private NativeRuntime() {
    }
}
