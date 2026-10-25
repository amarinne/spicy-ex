package com.eza.spicyex.hooks;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import com.eza.spicyex.BuildConfig;
import com.eza.spicyex.References;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.providers.LyricsResponseCache;
import com.eza.spicyex.ui.VsyncFrameScheduler;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One debug-only scripted track-change window through Spotify's own transport.
 *
 * <p>Why this exists: the frame histogram alone measures cadence while the surface is already
 * mounted, so it cannot see the main-thread stall a track change pays while the new document is
 * prepared. This run changes tracks on a fixed cadence and reports, per phase, both the frame
 * deltas and the {@link PrepareDocumentTiming} and {@link MountDocumentTiming} clocks.
 *
 * <p>Cold and warm are the same tracks in opposite order. The cold phase first deletes the raw
 * provider response cache, so the forward pass fetches instead of replaying; the warm phase walks
 * back over the same tracks, so the second pass is served from the cache the first pass filled.
 *
 * <p>This changes playback and the response cache and restores neither. It reads no lyrics, track
 * identifiers, or provider payloads into its reply. It rejects a run that lost the visible surface,
 * could not change a track, or produced too few samples instead of reporting a plausible-looking
 * number. A refresh-rate change is reported, not rejected: the threshold follows the phase's
 * current display period.
 */
final class AgentLyricsHitchRun {
    static final int MIN_CHANGES = 10;
    static final int MAX_CHANGES = 20;
    static final long DWELL_MS = 5_000L;
    private static final long TRACK_CHANGE_TIMEOUT_NANOS = 3_000_000_000L;
    private static final long DOCUMENT_TIMEOUT_NANOS = 3_000_000_000L;
    private static final long FRAME_SLACK_NANOS = 30_000_000_000L;
    private static final long SETTLE_BEFORE_COLD_MS = 1_000L;
    private static final long POLL_MS = 250L;

    private static volatile boolean busy;

    static boolean isBusy() { return busy; }

    static boolean validRequest(String[] args) {
        if (args.length != 2 || !args[1].matches("[A-Za-z0-9_-]{1,64}")) return false;
        try {
            int changes = Integer.parseInt(args[0]);
            return changes >= MIN_CHANGES && changes <= MAX_CHANGES;
        } catch (NumberFormatException bad) {
            return false;
        }
    }

    /**
     * Why the fullscreen surface is no longer visible, or "" when it is. Shared by {@link #start}
     * and the run monitor, so a run cannot start with a state the monitor would reject.
     *
     * <p>Deliberately excludes {@link Activity#hasWindowFocus()}: the owner's Fold 3 reports
     * {@code mCurrentFocus=null} and {@code mFocusedApp=null} during normal interaction, so focus
     * would reject every run on a surface that is genuinely visible. Resume state (the platform
     * fallback {@code !isFinishing()}, because public SDK {@code Activity} has no
     * {@code isResumed()}), attachment, window visibility, and the same activity instance carry it.
     */
    static String surfaceProblem(Activity activity, NativeSpicyShellView shell,
                                 String startActivityName, int startWindowVisibility) {
        if (activity == null) return "surface lost: no current activity";
        if (shell == null) return "surface lost: no lyrics shell";
        android.view.Window window = activity.getWindow();
        if (window == null) return "surface lost: no current window";
        boolean destroyed = activity.isDestroyed();
        boolean finishing = activity.isFinishing();
        boolean shown = shell.isShown();
        boolean attached = shell.isAttachedToWindow();
        int visibility = window.getDecorView().getWindowVisibility();
        boolean sameActivity = References.currentActivity() == activity;
        if (ready(destroyed, finishing, !finishing, shown, attached, visibility, sameActivity)) {
            return "";
        }
        if (destroyed) return "surface lost: activity destroyed";
        if (finishing) return "surface lost: activity finishing";
        if (!shown) return "surface lost: shell no longer shown";
        if (!attached) return "surface lost: shell detached from its window";
        if (visibility != View.VISIBLE) {
            return "surface lost: decor window visibility " + visibilityName(visibility)
                    + " (was " + visibilityName(startWindowVisibility) + ")";
        }
        if (!sameActivity) {
            return "surface lost: current activity changed " + startActivityName
                    + " -> " + className(References.currentActivity());
        }
        return "surface lost: visibility predicate rejected the current state";
    }

    static boolean surfaceReady(Activity activity, NativeSpicyShellView shell) {
        if (activity == null || shell == null || activity.getWindow() == null) return false;
        return surfaceProblem(activity, shell, className(activity),
                activity.getWindow().getDecorView().getWindowVisibility()).isEmpty();
    }

    /**
     * Pure form of the visibility predicate, so it has JVM coverage. {@code resumed} is the resume
     * signal; it is the platform fallback today because public SDK {@code Activity} exposes no
     * {@code isResumed()}.
     */
    static boolean ready(boolean destroyed, boolean finishing, boolean resumed, boolean shown,
                         boolean attached, int windowVisibility, boolean sameActivity) {
        return !destroyed && !finishing && resumed && shown && attached
                && windowVisibility == View.VISIBLE && sameActivity;
    }

    static String visibilityName(int visibility) {
        switch (visibility) {
            case View.VISIBLE: return "VISIBLE";
            case View.INVISIBLE: return "INVISIBLE";
            case View.GONE: return "GONE";
            default: return "visibility " + visibility;
        }
    }

    private static String className(Activity activity) {
        return activity == null ? "none" : activity.getClass().getName();
    }

    static void start(Activity activity, NativeSpicyShellView shell, LyricsHost host, Context context,
                      int changes, String id, Consumer<String> done) throws Exception {
        if (!BuildConfig.DEBUG) throw new IllegalStateException("debug build required");
        if (busy || AgentDemoFrameRun.isBusy()) {
            throw new IllegalStateException("a debug measurement is already running");
        }
        if (activity == null || shell == null) throw new IllegalStateException("no usable activity");
        AgentLyricsHitchRun run = new AgentLyricsHitchRun(activity, shell, host, context, changes, id, done);
        String surfaceProblem = run.visibilityProblem();
        if (!surfaceProblem.isEmpty()) throw new IllegalStateException(surfaceProblem);
        JSONObject layout = new JSONObject(shell.agentLayoutReport());
        if (layout.optBoolean("editor_open")) throw new IllegalStateException("close the editor first");
        if (shell.agentSettings("status")) throw new IllegalStateException("close the settings dialog first");
        if (!shell.hasLyricsDocument()) throw new IllegalStateException("wait for a lyrics document before measuring");
        if (!Float.isFinite(run.startRefreshHz) || run.startRefreshHz <= 0f) {
            throw new IllegalStateException("invalid refresh rate");
        }
        busy = true;
        try {
            run.begin();
        } catch (RuntimeException failure) {
            // A synchronous setup failure must not leave every later command rejected as busy.
            busy = false;
            throw failure;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Activity activity;
    private final NativeSpicyShellView shell;
    private final LyricsHost host;
    private final Context context;
    private final int changes;
    private final String id;
    private final Consumer<String> done;
    private final String startActivityName;
    private final int startWindowVisibility;
    private final boolean startWindowHasFocus;
    private final boolean startShellShown;
    private final boolean startShellAttached;
    private final float startRefreshHz;
    private final long startVsyncNanos;
    private final long dwellNanos = DWELL_MS * 1_000_000L;
    private float liveRefreshHz;

    private VsyncFrameScheduler scheduler;
    private boolean measuring;
    private boolean finished;
    private volatile boolean cacheCleared;

    private String phase = "";
    private int step;
    private int phaseChanges;
    private long phaseFromNanos;
    private long prepareBaseline;
    private long stepStartNanos;
    private long changeDeadlineNanos;
    private String previousUri;
    private boolean retried;
    private float phaseStartRefreshHz;
    private float phaseEndRefreshHz;
    private int phaseRefreshChanges;
    private final List<Float> phaseRefreshSeen = new ArrayList<>();
    private JSONObject cold;
    private JSONObject warm;

    private AgentLyricsHitchRun(Activity activity, NativeSpicyShellView shell, LyricsHost host,
                                Context context, int changes, String id, Consumer<String> done) {
        this.activity = activity;
        this.shell = shell;
        this.host = host;
        this.context = context;
        this.changes = changes;
        this.id = id;
        this.done = done;
        startActivityName = activity.getClass().getName();
        startWindowVisibility = activity.getWindow() == null ? View.GONE
                : activity.getWindow().getDecorView().getWindowVisibility();
        startWindowHasFocus = activity.hasWindowFocus();
        startShellShown = shell.isShown();
        startShellAttached = shell.isAttachedToWindow();
        startRefreshHz = currentRefreshHz();
        liveRefreshHz = startRefreshHz;
        startVsyncNanos = periodNanos(startRefreshHz);
    }

    private void begin() {
        monitor();
        main.postDelayed(() -> finish("workload timeout"),
                changes * 2L * DWELL_MS + 90_000L);
        // The raw response store is SQLite: clear it off the main thread, then start the cold
        // clock from a settled screen. A failed clear would measure the warm path under a cold
        // label, so it ends the run instead.
        NativeRuntime.LYRICS_IO.execute(() -> {
            try {
                LyricsResponseCache.clear(context);
                cacheCleared = true;
            } catch (Throwable failure) {
                main.post(() -> finish("cannot clear the lyrics response cache: "
                        + failure.getClass().getSimpleName()));
                return;
            }
            main.postDelayed(() -> startPhase("cold"), SETTLE_BEFORE_COLD_MS);
        });
    }

    private void startPhase(String name) {
        if (finished) return;
        String problem = visibilityProblem();
        if (!problem.isEmpty()) {
            finish(problem);
            return;
        }
        phase = name;
        step = 0;
        phaseChanges = 0;
        phaseFromNanos = System.nanoTime();
        PrepareDocumentTiming.get().reset();
        PrepareWorkerTiming.get().reset();
        MountDocumentTiming.get().reset();
        prepareBaseline = 0L;
        phaseStartRefreshHz = liveRefreshHz;
        phaseEndRefreshHz = liveRefreshHz;
        phaseRefreshChanges = 0;
        phaseRefreshSeen.clear();
        phaseRefreshSeen.add(liveRefreshHz);
        long span = changes * dwellNanos;
        try {
            scheduler = VsyncFrameScheduler.beginMeasurement(periodNanos(liveRefreshHz), phaseFromNanos,
                    phaseFromNanos + span * 3L + FRAME_SLACK_NANOS);
            measuring = true;
        } catch (RuntimeException failure) {
            finish("cannot start the frame measurement: " + failure.getMessage());
            return;
        }
        nextStep();
    }

    private void nextStep() {
        if (finished) return;
        String problem = visibilityProblem();
        if (!problem.isEmpty()) {
            finish(problem);
            return;
        }
        if (step >= changes) {
            endPhase();
            return;
        }
        previousUri = currentUri();
        if (previousUri.isEmpty()) {
            finish("no current track");
            return;
        }
        if (!sendChange()) {
            finish("the playback transport rejected the track change");
            return;
        }
        step++;
        retried = false;
        prepareBaseline = PrepareWorkerTiming.get().completedPosts();
        stepStartNanos = System.nanoTime();
        changeDeadlineNanos = stepStartNanos + TRACK_CHANGE_TIMEOUT_NANOS;
        main.postDelayed(this::checkTrackChange, POLL_MS);
    }

    private void checkTrackChange() {
        if (finished) return;
        String now = currentUri();
        if (!now.isEmpty() && !now.equals(previousUri)) {
            phaseChanges++;
            awaitDocument(System.nanoTime());
            return;
        }
        long nowNanos = System.nanoTime();
        if (nowNanos <= changeDeadlineNanos) {
            main.postDelayed(this::checkTrackChange, POLL_MS);
            return;
        }
        if (retried) {
            finish("no track change in step " + step + " of the " + phase + " phase");
            return;
        }
        // Some players treat previous as restart while the track has played, and a transport that
        // is not ready yet looks the same from here. One verified retry separates those cases from
        // "this device cannot change tracks"; a second miss invalidates the run.
        retried = true;
        stepStartNanos = nowNanos;
        prepareBaseline = PrepareWorkerTiming.get().completedPosts();
        if (!sendChange()) {
            finish("the playback transport rejected the track change");
            return;
        }
        changeDeadlineNanos = nowNanos + TRACK_CHANGE_TIMEOUT_NANOS;
        main.postDelayed(this::checkTrackChange, POLL_MS);
    }

    private void awaitDocument(long changedAtNanos) {
        if (finished) return;
        if (PrepareWorkerTiming.get().completedPosts() > prepareBaseline) {
            scheduleNextStep();
            return;
        }
        if (System.nanoTime() - changedAtNanos > DOCUMENT_TIMEOUT_NANOS) {
            finish("no lyrics document was prepared for the changed track in the " + phase + " phase");
            return;
        }
        main.postDelayed(() -> awaitDocument(changedAtNanos), POLL_MS);
    }

    private void scheduleNextStep() {
        if (finished) return;
        long remaining = stepStartNanos + dwellNanos - System.nanoTime();
        if (remaining <= 0L) {
            nextStep();
            return;
        }
        main.postDelayed(this::nextStep, remaining / 1_000_000L + 1L);
    }

    /** Closes the phase window and, when it is the last phase, the whole run. */
    private void endPhase() {
        long endedNanos = System.nanoTime();
        VsyncFrameScheduler phaseScheduler = scheduler;
        scheduler = null;
        measuring = false;
        Map<String, Object> frames = phaseScheduler == null
                ? null : phaseScheduler.endMeasurement().snapshot();
        Map<String, Object> prepare = PrepareDocumentTiming.get().snapshot();
        String problem = phaseProblem(frames, prepare, endedNanos);
        if (!problem.isEmpty()) {
            finish(problem);
            return;
        }
        JSONObject report = new JSONObject();
        try {
            report.put("version", BuildConfig.VERSION_NAME);
            report.put("refresh_hz", phaseStartRefreshHz);
            report.put("refresh_hz_end", phaseEndRefreshHz);
            report.put("refresh_changes", phaseRefreshChanges);
            report.put("refresh_hz_seen", new JSONArray(phaseRefreshSeen));
            report.put("track_changes", phaseChanges);
            report.put("duration_ms", Math.round((endedNanos - phaseFromNanos) / 1_000_000.0d));
            report.put("frames_total", frames.get("count"));
            report.put("p50_ns", frames.get("p50_ns"));
            report.put("p90_ns", frames.get("p90_ns"));
            report.put("max_ns", frames.get("max_ns"));
            report.put("over_1_5x_vsync", frames.get("over_1_5x_vsync"));
            report.put("prepare_calls", prepare.get("prepare_calls"));
            report.put("prepare_sampled", prepare.get("prepare_sampled"));
            report.put("prepare_median_ms", prepare.get("prepare_median_ms"));
            report.put("prepare_p90_ms", prepare.get("prepare_p90_ms"));
            report.put("prepare_max_ms", prepare.get("prepare_max_ms"));
            report.put("prepare_last_ms", prepare.get("prepare_last_ms"));
            for (Map.Entry<String, Object> timing : PrepareWorkerTiming.get().snapshot().entrySet()) {
                report.put(timing.getKey(), timing.getValue());
            }
            // The mount callback and the Java heap: what the track change pays on the main
            // thread, and what that work left behind.
            for (Map.Entry<String, Object> timing : MountDocumentTiming.get().snapshot().entrySet()) {
                report.put(timing.getKey(), timing.getValue());
            }
            for (Map.Entry<String, Object> heap : JavaHeap.snapshot().entrySet()) {
                report.put(heap.getKey(), heap.getValue());
            }
        } catch (Exception failure) {
            finish("cannot build the " + phase + " phase report");
            return;
        }
        if ("cold".equals(phase)) {
            cold = report;
            startPhase("warm");
        } else {
            warm = report;
            finish("");
        }
    }

    /**
     * The same posture as {@code demo-frames}: a phase with no demand, an interrupted window, or a
     * missed mount is not a number worth comparing. Histogram overflow is deliberately allowed
     * here: a delta past the bounded bucket range is a real hitch, not a broken window.
     */
    private String phaseProblem(Map<String, Object> frames, Map<String, Object> prepare, long endedNanos) {
        if (frames == null) return "the frame window was not open in the " + phase + " phase";
        long elapsedNanos = Math.max(0L, endedNanos - phaseFromNanos);
        long count = (Long) frames.get("count");
        if (count < Math.ceil(elapsedNanos / 1_000_000_000.0d * 20d)) {
            return "too few frame samples in the " + phase + " phase";
        }
        if ((Long) frames.get("sample_span_ns") < (long) (elapsedNanos * 0.9d)) {
            return "the frame window is shorter than the " + phase + " phase";
        }
        if ((Long) frames.get("invalid_timestamps") != 0L) {
            return "invalid frame timestamps in the " + phase + " phase";
        }
        if ((Long) frames.get("interruptions") != 0L) {
            return "the frame window was interrupted in the " + phase + " phase";
        }
        long calls = (Long) prepare.get("prepare_calls");
        if (calls == 0L) return "no document preparation samples in the " + phase + " phase";
        if (calls < phaseChanges) {
            return "fewer document preparations than track changes in the " + phase + " phase";
        }
        return "";
    }

    private void monitor() {
        if (finished) return;
        String problem = visibilityProblem();
        if (!problem.isEmpty()) {
            finish(problem);
            return;
        }
        followRefresh();
        main.postDelayed(this::monitor, 500L);
    }

    /**
     * An adaptive display can switch rates mid-phase. Do not invalidate: move the histogram's
     * threshold to the new period for later deltas and record the change. At most one 500 ms
     * monitor tick of frames after a switch still uses the previous period.
     */
    private void followRefresh() {
        float hz = currentRefreshHz();
        if (!Float.isFinite(hz) || hz <= 0f) return;
        if (Math.abs(hz - liveRefreshHz) <= 0.1f) return;
        liveRefreshHz = hz;
        if (!measuring || scheduler == null) return;
        scheduler.updateMeasurementPeriod(periodNanos(hz));
        phaseEndRefreshHz = hz;
        phaseRefreshChanges++;
        for (Float seen : phaseRefreshSeen) {
            if (Math.abs(seen - hz) <= 0.1f) return;
        }
        phaseRefreshSeen.add(hz);
    }

    private String visibilityProblem() {
        return surfaceProblem(activity, shell, startActivityName, startWindowVisibility);
    }

    private float currentRefreshHz() {
        return activity.getWindowManager().getDefaultDisplay().getRefreshRate();
    }

    private static long periodNanos(float hz) {
        return Math.round(1_000_000_000d / hz);
    }

    private String currentUri() {
        SpotifyTrack current = host.getCurrentTrackSafely();
        return current == null || current.uri == null ? "" : current.uri;
    }

    private boolean sendChange() {
        if ("cold".equals(phase)) return host.skipToNextTrack();
        // Some players restart the current track when previous arrives after the first seconds.
        // Rewind first, so the warm pass returns over the same tracks instead of re-measuring the
        // one already on screen.
        if (host.canSeek()) host.seekSpotifyTo(0L);
        return host.skipToPreviousTrack();
    }

    private void finish(String reason) {
        if (finished) return;
        finished = true;
        main.removeCallbacksAndMessages(null);
        if (measuring) {
            measuring = false;
            try {
                scheduler.endMeasurement();
            } catch (Throwable ignored) {
                // The export below still states what it has; a dead window is not a crash.
            }
        }
        String result;
        try {
            if (reason.isEmpty()) reason = visibilityProblem();
            JSONObject out = new JSONObject();
            out.put("id", id);
            out.put("valid", reason.isEmpty());
            out.put("invalid_reason", reason);
            out.put("version", BuildConfig.VERSION_NAME);
            out.put("refresh_hz", startRefreshHz);
            out.put("refresh_hz_end", liveRefreshHz);
            out.put("vsync_ns", startVsyncNanos);
            out.put("track_changes", changes);
            out.put("dwell_ms", DWELL_MS);
            out.put("cache_cleared", cacheCleared);
            out.put("side_effects", "current track moved; the lyrics response cache was cleared before the cold phase");
            JSONObject start = new JSONObject();
            start.put("activity", startActivityName);
            start.put("decor_window_visibility", visibilityName(startWindowVisibility));
            start.put("shell_shown", startShellShown);
            start.put("shell_attached", startShellAttached);
            start.put("window_has_focus", startWindowHasFocus);
            start.put("refresh_hz", startRefreshHz);
            out.put("start", start);
            out.put("cold", cold == null ? JSONObject.NULL : cold);
            out.put("warm", warm == null ? JSONObject.NULL : warm);
            result = out.toString();
        } catch (Exception failure) {
            result = "error lyrics-hitch export failed";
        } finally {
            busy = false;
        }
        done.accept(result);
    }
}
