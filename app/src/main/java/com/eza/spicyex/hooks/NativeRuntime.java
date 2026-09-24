package com.eza.spicyex.hooks;

import com.eza.spicyex.lyrics.SpicyProcessing;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.RejectedExecutionHandler;

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
            // Happy Eyeballs: race IPv4/IPv6 instead of trying routes in order, so a
            // blackholed route costs the fallback delay rather than a full timeout.
            .fastFallback(true)
            .connectTimeout(HTTP_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(HTTP_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(HTTP_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();
    static final java.util.concurrent.ScheduledThreadPoolExecutor LYRICS_IO = new java.util.concurrent.ScheduledThreadPoolExecutor(2);
    static {
        LYRICS_IO.setRemoveOnCancelPolicy(true);
        LYRICS_IO.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        LYRICS_IO.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }
    // Sound and Meaning get distinct bounded jobs. Neither lane may park the other's thread on
    // network I/O, and cancelling one never starves the other.
    /** Deterministic on-device reading work. Single-threaded: the romanizers are not reentrant. */
    static final ExecutorService SOUND_PROCESSOR = Executors.newSingleThreadExecutor();
    /** Reading fallback requests. Bounded fan-out; the lane executor never awaits these. */
    static final ExecutorService SOUND_WORKERS = boundedWorkers("spicy-sound");
    /** Machine translation batches. Separate from every Sound thread. */
    static final ExecutorService MEANING_WORKERS = boundedWorkers("spicy-meaning");
    /**
     * AI generation, on its own thread and nobody else's.
     *
     * <p>A model call runs for tens of seconds against a 60s deadline. On a lane's own pool that
     * would hold a worker the deterministic and machine passes need, so skipping tracks during a
     * generation would stall readings that owe nothing to the network. Single-threaded because one
     * paid run at a time per layer is already the contract, and two would just be two bills.
     */
    static final ExecutorService AI_WORKERS = boundedWorkers("spicy-ai");

    /**
     * A track change can invalidate hundreds of per-line jobs while their network calls are still
     * draining. An unbounded executor queue retains each job's document snapshot until it runs.
     * Keep a small handoff buffer; when it fills, run the task on the submitting lane so callers
     * apply backpressure instead of retaining an arbitrary number of lyric lines.
     */
    private static ExecutorService boundedWorkers(String name) {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
        RejectedExecutionHandler backpressure = new ThreadPoolExecutor.CallerRunsPolicy();
        return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32), factory, backpressure);
    }
    static final int GOOGLE_PROCESSING_VERSION = SpicyProcessing.PROCESSING_VERSION + 2;
    // Rows mounted around the active line. The screen shows ~8-10; everything past the window is a
    // virtual spacer. Mounting every row of any song up to 72 lines (the old threshold) built all
    // of them - one text view per character in CJK lyrics, thousands in all - on the main thread
    // when the screen opened (~1.5 s) and kept them, with their blur layers, for the whole song.
    static final int LYRIC_FULL_RENDER_THRESHOLD = 24;
    static final int LYRIC_WINDOW_BEFORE_ACTIVE = 8;
    static final int LYRIC_WINDOW_AFTER_ACTIVE = 12;
    static final int LYRIC_WINDOW_EDGE_BUFFER = 4;
    static final int LYRIC_ESTIMATED_ROW_HEIGHT_DP = 74;
    static final long SCROLL_SETTLE_REMEASURE_DELAY_MS = 140;

    private NativeRuntime() {
    }
}
