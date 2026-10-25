package com.eza.spicyex.hooks;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import com.eza.spicyex.BuildConfig;
import com.eza.spicyex.lyrics.session.LyricPipelineMetrics;
import com.eza.spicyex.ui.FrameDeltaHistogram;
import com.eza.spicyex.ui.VsyncFrameScheduler;
import org.json.JSONObject;
import java.util.Map;
import java.util.function.Consumer;

/** One debug-only, offline demo run through the existing editor and shell owners. */
final class AgentDemoFrameRun {
    private static volatile boolean busy;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Activity activity;
    private final NativeSpicyShellView shell;
    private final int seconds;
    private final String id;
    private final Consumer<String> done;
    private final float refreshHz;
    private final Map<String, Long> pipelineBefore;
    private VsyncFrameScheduler scheduler;
    private boolean finished;
    private final Runnable monitor = this::monitor;

    static boolean isBusy() { return busy; }

    static boolean validRequest(String[] args) {
        if (args.length != 2 || !args[1].matches("[A-Za-z0-9_-]{1,64}")) return false;
        try { int seconds = Integer.parseInt(args[0]); return seconds >= 5 && seconds <= 60; }
        catch (NumberFormatException bad) { return false; }
    }

    static void start(Activity activity, NativeSpicyShellView shell, int seconds, String id,
                      Consumer<String> done) throws Exception {
        if (!BuildConfig.DEBUG) throw new IllegalStateException("debug build required");
        if (busy) throw new IllegalStateException("demo measurement already running");
        JSONObject layout = new JSONObject(shell.agentLayoutReport());
        if (layout.optBoolean("editor_open")) throw new IllegalStateException("close the editor first");
        if (!activity.hasWindowFocus() || !shell.isShown()) throw new IllegalStateException("lyrics must be visible and focused");
        AgentDemoFrameRun run = new AgentDemoFrameRun(activity, shell, seconds, id, done);
        if (!Float.isFinite(run.refreshHz) || run.refreshHz <= 0) throw new IllegalStateException("invalid refresh rate");
        if (!shell.agentOpenEditor(false)) throw new IllegalStateException("cannot open editor");
        busy = true;
        // Let the editor's deferred auto-preview finish before resetting the demo clock.
        shell.postOnAnimation(() -> shell.postOnAnimation(() -> run.step(run::prepare)));
        run.main.postDelayed(() -> run.finish("workload timeout"), (seconds + 10L) * 1000L);
    }

    private AgentDemoFrameRun(Activity activity, NativeSpicyShellView shell, int seconds, String id,
                              Consumer<String> done) {
        this.activity = activity;
        this.shell = shell;
        this.seconds = seconds;
        this.id = id;
        this.done = done;
        refreshHz = activity.getWindowManager().getDefaultDisplay().getRefreshRate();
        pipelineBefore = LyricPipelineMetrics.snapshot();
    }

    private void prepare() {
        if (!shell.agentEditorAction("demo", "live")
                || !shell.agentEditorAction("demo", "synthetic")
                || !shell.agentEditorAction("options", "hide")) {
            finish("cannot prepare synthetic demo");
            return;
        }
        monitor();
        if (!finished) main.postDelayed(() -> step(this::measure), 3000L);
    }

    private void measure() {
        long from = System.nanoTime();
        scheduler = VsyncFrameScheduler.beginMeasurement(Math.round(1_000_000_000d / refreshHz),
                from, from + seconds * 1_000_000_000L);
        main.postDelayed(() -> finish(""), seconds * 1000L);
    }

    private void step(Runnable action) {
        if (finished) return;
        try { action.run(); }
        catch (Exception failure) { finish("workload action failed: " + failure.getClass().getSimpleName()); }
    }

    private void monitor() {
        step(() -> {
            if (activity.isDestroyed() || !activity.hasWindowFocus() || !shell.isShown()
                    || com.eza.spicyex.References.currentActivity() != activity
                    || Math.abs(activity.getWindowManager().getDefaultDisplay().getRefreshRate() - refreshHz) > .1f) {
                finish("visibility, activity, or refresh rate changed");
                return;
            }
            main.postDelayed(monitor, 500L);
        });
    }

    private void finish(String reason) {
        if (finished) return;
        finished = true;
        main.removeCallbacksAndMessages(null);
        String result;
        try {
            if (reason.isEmpty() && (activity.isDestroyed() || !activity.hasWindowFocus() || !shell.isShown()))
                reason = "lyrics lost visibility or focus";
            FrameDeltaHistogram histogram = scheduler == null ? null : scheduler.endMeasurement();
            JSONObject layout = new JSONObject(shell.agentLayoutReport());
            if (reason.isEmpty() && (!layout.optBoolean("demo_active") || !layout.optBoolean("editor_open")))
                reason = "demo or editor closed";
            Map<String, Object> frames = histogram == null ? null : histogram.snapshot();
            if (reason.isEmpty() && (frames == null || (long) frames.get("count") < seconds * 20L
                    || (long) frames.get("sample_span_ns") < seconds * 900_000_000L
                    || (long) frames.get("invalid_timestamps") != 0
                    || (long) frames.get("interruptions") != 0
                    || (long) frames.get("overflow_count") != 0)) reason = "incomplete or interrupted frame window";
            result = new JSONObject().put("id", id).put("valid", reason.isEmpty())
                    .put("invalid_reason", reason).put("seconds", seconds).put("warmup_seconds", 3)
                    .put("refresh_hz", refreshHz).put("version", BuildConfig.VERSION_NAME)
                    .put("frames", frames == null ? JSONObject.NULL : new JSONObject(frames))
                    .put("pipeline_before", new JSONObject(pipelineBefore))
                    .put("pipeline_after", new JSONObject(LyricPipelineMetrics.snapshot())).toString();
        } catch (Exception failure) {
            result = "error demo measurement export failed";
        } finally {
            if (scheduler != null) scheduler.endMeasurement();
            try { shell.agentCloseEditor(); }
            catch (Exception failure) { result = "error demo editor cleanup failed"; }
            finally { busy = false; }
        }
        done.accept(result);
    }
}
