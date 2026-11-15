package com.eza.spicyex.auto;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Opt-in, network-free measurement of the real Auto adapter, not a performance pass gate. */
public final class AutoPerformanceInstrumentation {
    public static void run(Instrumentation host) {
        Bundle result = new Bundle();
        Activity activity = null;
        try {
            Intent intent = new Intent().setClassName(host.getTargetContext(),
                    "com.eza.spicyex.auto.AutoRenderingTestActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = host.startActivitySync(intent);
            Activity window = activity;
            CountDownLatch done = new CountDownLatch(1);
            host.runOnMainSync(() -> {
                window.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                android.content.res.Configuration configuration = new android.content.res.Configuration(
                        host.getTargetContext().getResources().getConfiguration());
                configuration.densityDpi = 160; configuration.fontScale = 1f;
                AutoLyricsView view = new AutoLyricsView(host.getTargetContext().createConfigurationContext(configuration));
                view.setTag("player-full");
                FrameLayout root = new FrameLayout(host.getTargetContext());
                root.addView(view, new FrameLayout.LayoutParams(668, 178));
                window.setContentView(root);
                new Run(view, result, done).start();
            });
            if (!done.await(25, TimeUnit.SECONDS)) throw new AssertionError("measurement timeout");
            result.putString("stream", result.toString() + "\n");
            host.finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error + "\n"); host.finish(0, result);
        } finally {
            if (activity != null) { Activity window = activity; host.runOnMainSync(window::finish); }
        }
    }

    private static final class Run implements Choreographer.FrameCallback, Runnable {
        final AutoLyricsView view;
        final Bundle result, packet = new Bundle();
        final CountDownLatch done;
        final Handler main = new Handler(Looper.getMainLooper());
        final Field previous;
        final long[] intervals = new long[2000];
        long start, lastFrame, warmFrames, updateNanos, updateMax;
        float firstGradient;
        float refreshHz;
        int count, updates;
        boolean stopped;

        Run(AutoLyricsView view, Bundle result, CountDownLatch done) {
            this.view = view; this.result = result; this.done = done;
            try { previous = AutoLyricsView.class.getDeclaredField("previousFrame"); previous.setAccessible(true); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            String text = "Smooth lyrics keep every word in time";
            packet.putString("line", text); packet.putString("presentation", "lyric");
            packet.putString("auto_secondary_text", "Main only"); packet.putInt("generation", 1);
            packet.putBoolean("playing", true); packet.putDouble("playbackRate", 1d);
            packet.putLong("lineEndMs", 60000);
            ArrayList<Bundle> words = new ArrayList<>();
            int offset = 0, i = 0;
            for (String textWord : text.split(" ")) {
                Bundle word = new Bundle(); word.putString("text", textWord);
                word.putInt("sourceStart", offset); word.putInt("sourceEnd", offset + textWord.length());
                word.putLong("startMs", i * 2500L); word.putLong("endMs", (i + 1) * 2500L);
                word.putBoolean("boundaryAfter", true); words.add(word);
                offset += textWord.length() + 1; i++;
            }
            packet.putParcelableArrayList("words", words);
        }
        void start() { start = SystemClock.elapsedRealtime(); run(); Choreographer.getInstance().postFrameCallback(this); }
        @Override public void run() {
            if (stopped) return;
            long now = SystemClock.elapsedRealtime();
            packet.putLong("positionMs", now - start); packet.putLong("sampledAt", now);
            long before = System.nanoTime(); view.update(packet); long duration = System.nanoTime() - before;
            if (now - start >= 3000) { updateNanos += duration; updateMax = Math.max(updateMax, duration); updates++; }
            main.postDelayed(this, 100);
        }
        @Override public void doFrame(long time) {
            try {
                long frame = previous.getLong(view), elapsed = SystemClock.elapsedRealtime() - start;
                float refresh = view.getDisplay().getRefreshRate();
                if (refreshHz == 0) refreshHz = refresh;
                else if (Math.abs(refreshHz - refresh) > .1f) result.putString("invalid", "refresh rate changed");
                if (elapsed < 3000) { warmFrames = view.metrics().getLong("frameCount"); lastFrame = 0; firstGradient = gradientSum(view); }
                else if (frame != 0 && frame != lastFrame) {
                    if (lastFrame != 0 && count < intervals.length) intervals[count++] = frame - lastFrame;
                    lastFrame = frame;
                }
                if (elapsed < 15000) { Choreographer.getInstance().postFrameCallback(this); return; }
                Bundle metrics = view.metrics();
                Arrays.sort(intervals, 0, count);
                // BuildStamp constants would be inlined from the harness's compile revision.
                android.content.pm.PackageInfo installed = view.getContext().getPackageManager()
                        .getPackageInfo(view.getContext().getPackageName(), 0);
                result.putString("build", installed.versionName);
                result.putFloat("refreshHz", refreshHz);
                result.putInt("samples", count); result.putLong("elapsedMs", elapsed);
                result.putDouble("cadenceP50Ms", percentile(.5)); result.putDouble("cadenceP90Ms", percentile(.9));
                result.putDouble("cadenceMaxMs", percentile(1));
                result.putDouble("callbackFps", count * 1000d / (elapsed - 3000));
                result.putDouble("updateMeanMs", updateNanos / 1e6 / Math.max(1, updates));
                result.putDouble("updateMaxMs", updateMax / 1e6); result.putInt("updates", updates);
                result.putLong("draws", metrics.getLong("frameCount") - warmFrames);
                result.putString("metrics", metrics.toString());
                float lastGradient = gradientSum(view);
                result.putFloat("gradientBefore", firstGradient); result.putFloat("gradientAfter", lastGradient);
                if (!view.isShown() || metrics.getInt("pages") != 1 || count < 100 || firstGradient == lastGradient)
                    result.putString("invalid", "hidden, paged, static, or too few callbacks");
            } catch (Throwable error) { result.putString("invalid", error.toString()); }
            stopped = true; main.removeCallbacks(this); view.stop();
            try { shaderProbe(); } catch (Throwable error) { result.putString("shaderError", error.toString()); }
            result.putBoolean("valid", !result.containsKey("invalid")); done.countDown();
        }
        /** Diagnostic only: software glyph draw plus moving shader, excluding fitting and IPC. */
        void shaderProbe() throws ReflectiveOperationException {
            com.eza.spicyex.lyrics.SpicyAnimatedTextView text =
                    com.eza.spicyex.lyrics.SpicyAnimatedTextView.unstyled(view.getContext());
            text.setText("Smooth"); text.setTextSize(48); text.setTextColor(android.graphics.Color.WHITE);
            int exact = android.view.View.MeasureSpec.EXACTLY;
            text.measure(android.view.View.MeasureSpec.makeMeasureSpec(240, exact),
                    android.view.View.MeasureSpec.makeMeasureSpec(72, exact)); text.layout(0, 0, 240, 72);
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(240, 72, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
            Field shader = text.getClass().getDeclaredField("cachedShader"); shader.setAccessible(true);
            Object previousShader = null;
            int replacements = 0;
            long cpu = 0, bytes = 0;
            try {
                for (int i = 0; i < 1700; i++) {
                    if (i == 200) {
                        cpu = android.os.Debug.threadCpuTimeNanos();
                        bytes = Long.parseLong(android.os.Debug.getRuntimeStat("art.gc.bytes-allocated"));
                    }
                    text.setGradientPosition(-39f + (i % 200) * .69f, 0f);
                    bitmap.eraseColor(0); text.draw(canvas);
                    Object current = shader.get(text);
                    if (i >= 200 && previousShader != current) replacements++;
                    previousShader = current;
                }
                result.putDouble("shaderDrawCpuMeanMs", (android.os.Debug.threadCpuTimeNanos() - cpu) / 1e6 / 1500);
                result.putLong("shaderDrawAllocatedBytes", Long.parseLong(android.os.Debug.getRuntimeStat("art.gc.bytes-allocated")) - bytes);
                result.putInt("shaderReplacements", replacements); result.putInt("shaderDraws", 1500);
            } finally { bitmap.recycle(); }
        }
        double percentile(double fraction) { return count == 0 ? 0 : intervals[Math.max(0, (int) Math.ceil(count * fraction) - 1)] / 1e6; }
        float gradientSum(android.view.View leaf) throws ReflectiveOperationException {
            if (leaf instanceof com.eza.spicyex.lyrics.SpicyAnimatedTextView) {
                Field field = com.eza.spicyex.lyrics.SpicyAnimatedTextView.class.getDeclaredField("gradientPosition");
                field.setAccessible(true); return field.getFloat(leaf);
            }
            float total = 0;
            if (leaf instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) leaf).getChildCount(); i++)
                total += gradientSum(((android.view.ViewGroup) leaf).getChildAt(i));
            return total;
        }
    }
}
