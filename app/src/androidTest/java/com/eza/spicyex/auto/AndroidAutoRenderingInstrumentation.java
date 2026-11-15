package com.eza.spicyex.auto;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.eza.spicyex.lyrics.SpicyAnimatedTextView;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Exercises Auto fitting, mounting, and drawn sentence fill through the production adapter. */
public final class AndroidAutoRenderingInstrumentation {
    private final Instrumentation host;
    private android.app.Activity activity;
    private int completed;
    private static long fixtureFrameNanos;
    private AndroidAutoRenderingInstrumentation(Instrumentation host) { this.host = host; }
    public static void run(Instrumentation host) { new AndroidAutoRenderingInstrumentation(host).run(); }

    private void run() {
        List<String> failures = new ArrayList<>();
        android.content.Intent intent = new android.content.Intent();
        intent.setClassName(host.getTargetContext(), "com.eza.spicyex.auto.AutoRenderingTestActivity");
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = host.startActivitySync(intent);
        host.runOnMainSync(() -> activity.getWindow().addFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        host.waitForIdleSync();
        for (String surface : new String[]{"player", "player-full", "weather"}) {
            check(failures, surface + ".nested_rows", () -> checkRows(surface));
            check(failures, surface + ".fit_nested_rows", () -> checkFit(surface));
            check(failures, surface + ".one_row", () -> checkSmall(surface, 1));
            check(failures, surface + ".two_rows", () -> checkSmall(surface, 2));
            check(failures, surface + ".secondary", () -> checkSecondary(surface));
            check(failures, surface + ".credit_size", () -> checkCreditSize(surface));
            check(failures, surface + ".word_fill", () -> checkTimedFill(surface, true));
            check(failures, surface + ".syllable_fill", () -> checkTimedFill(surface, false));
        }
        check(failures, "duet.two_sections", this::checkDuet);
        check(failures, "duet.off_and_weather", this::checkDuetSurfaces);
        check(failures, "duet.survivor_size", () -> AutoDuetTransitionChecks.replacement(host.getTargetContext()));
        check(failures, "duet.reserved_fade", () -> AutoDuetTransitionChecks.deferred(host.getTargetContext()));
        host.runOnMainSync(() -> activity.finish());
        Bundle result = new Bundle();
        result.putInt("expectedCases", 28); result.putInt("completedCases", completed);
        result.putString("stream", failures.isEmpty() ? "PASS: 28 Android Auto rendering cases\n"
                : "FAIL: " + String.join("\n", failures) + "\n");
        host.finish(failures.isEmpty() ? -1 : 0, result);
    }

    private void check(List<String> failures, String id, Runnable task) {
        host.runOnMainSync(() -> {
            try { task.run(); completed++; }
            catch (Throwable error) { failures.add(id + ": " + error); }
        });
        host.waitForIdleSync();
    }

    private void checkRows(String surface) {
        checkRows(surface, false);
    }

    private void checkRows(String surface, boolean timed) {
        AutoLyricsView view = newView(surface);
        if (timed) activity.setContentView(view);
        List<Bitmap> frames = new ArrayList<>();
        try {
            int words = wordsForRows(view, 3);
            Bitmap unsung = frame(view, 0, words, 1, timed); frames.add(unsung);
            List<int[]> rows = rows(view);
            if (rows.size() != 3) throw new AssertionError("expected 3 drawn rows: " + rows.size());
            if (view.metrics().getInt("pages") != 1) throw new AssertionError("fixture must keep one page");
            Bitmap sung = frame(view, words * 200L - (timed ? 1 : 0), words, 2, timed); frames.add(sung);
            float total = 0; for (int[] row : rows) total += row[2];
            float preceding = 0;
            float[] previous = new float[rows.size()];
            long earlyPosition = 0; Bitmap early = null;
            for (int frontier = 0; frontier < rows.size(); frontier++) {
                long position = Math.round((preceding + rows.get(frontier)[2] * .5f) / total * words * 200L);
                Bitmap current = frame(view, position, words, 3 + frontier, timed); frames.add(current);
                float[] progress = normalized(unsung, current, sung, rows);
                for (int row = 0; row < progress.length; row++) {
                    if (row < frontier && progress[row] < .90f
                            || row == frontier && (progress[row] < .15f || progress[row] > .85f)
                            || row > frontier && progress[row] > .05f) {
                        throw new AssertionError("frontier=" + frontier + " pixels=" + java.util.Arrays.toString(progress));
                    }
                    if (progress[row] + .05f < previous[row]) throw new AssertionError("forward playback dimmed row");
                }
                previous = progress;
                if (frontier == 0) { early = current; earlyPosition = position; }
                preceding += rows.get(frontier)[2];
            }
            Bitmap back = frame(view, earlyPosition, words, 9, timed); frames.add(back);
            if (!early.sameAs(back)) throw new AssertionError("backward seek did not restore pixels");
            if (timed) saveFrames(surface + "-timed-rows", view, frames);
        } catch (Throwable error) {
            saveFrames(surface + (timed ? "-timed-rows" : ""), view, frames);
            throw error;
        } finally { for (Bitmap bitmap : frames) bitmap.recycle(); view.stop(); }
    }

    private void checkFit(String surface) {
        AutoLyricsView view = newView(surface);
        try {
            for (long position : new long[]{0, 4800, 9500}) {
                Bitmap bitmap = frame(view, position, 48, (int) position + 20);
                try {
                    List<int[]> rows = rows(view);
                    if (rows.isEmpty() || rows.size() > 3) throw new AssertionError("fit admitted " + rows.size() + " visual rows");
                    for (int[] row : rows) if (row[0] < view.getPaddingTop()
                            || row[1] > view.getHeight() - view.getPaddingBottom()) {
                        throw new AssertionError("glyph rows exceed the surface");
                    }
                } finally { bitmap.recycle(); }
            }
        } finally { view.stop(); }
    }

    private void checkSmall(String surface, int expected) {
        AutoLyricsView view = newView(surface);
        try {
            int words = wordsForRows(view, expected);
            Bitmap bitmap = frame(view, 0, words, 30);
            try { if (rows(view).size() != expected) throw new AssertionError("expected " + expected
                    + " rows, got " + rows(view).size()); }
            finally { bitmap.recycle(); }
        } finally { view.stop(); }
    }

    /** A duet mounts both overlapping rows, caps each secondary to one line, and stays in bounds. */
    private void checkDuet() {
        AutoLyricsView view = newView("player-full");
        try {
            view.update(duetPacket(true)); layout(view, view.getWidth(), view.getHeight());
            Bundle metrics = view.metrics();
            if (!metrics.getBoolean("duet")) throw new AssertionError("duet did not mount");
            if (metrics.getInt("duetSections") != 2) throw new AssertionError("expected two sections");
            if (metrics.getInt("secondaryLineCap") != 1)
                throw new AssertionError("duet must cap secondary rows at one line");
            List<int[]> rows = rows(view);
            if (rows.size() < 2) throw new AssertionError("expected two drawn sections: " + rows.size());
            for (int[] row : rows) if (row[0] < view.getPaddingTop()
                    || row[1] > view.getHeight() - view.getPaddingBottom())
                throw new AssertionError("duet rows exceed the surface");
            List<TextView> secondary = new ArrayList<>(); collectSecondary(view, secondary);
            if (secondary.size() != 4)
                throw new AssertionError("expected reading and translation per section: " + secondary.size());
            for (TextView text : secondary) if (text.getMaxLines() != 1)
                throw new AssertionError("duet secondary rows must keep one line");
        } finally { view.stop(); }
    }

    private void checkDuetSurfaces() {
        AutoLyricsView solo = newView("player-full");
        try {
            solo.update(duetPacket(false)); layout(solo, solo.getWidth(), solo.getHeight());
            if (solo.metrics().getBoolean("duet") || solo.metrics().getInt("duetSections") != 1)
                throw new AssertionError("duet mounted while the toggle was off");
        } finally { solo.stop(); }
        AutoLyricsView weather = newView("weather");
        try {
            weather.update(duetPacket(true)); layout(weather, weather.getWidth(), weather.getHeight());
            if (weather.metrics().getBoolean("duet") || weather.metrics().getInt("duetSections") != 1)
                throw new AssertionError("weather admitted a second section");
        } finally { weather.stop(); }
    }

    private static Bundle duetPacket(boolean duet) {
        Bundle packet = sample(800, 6, 80);
        packet.putBoolean("auto_duet", duet);
        packet.putString("auto_secondary_text", "Both");
        packet.putString("romanized", "ai ai"); packet.putString("secondary", "love love");
        Bundle companion = sample(800, 5, 80);
        companion.putInt("lineIndex", 1);
        companion.putLong("lineStartMs", 400); companion.putLong("lineEndMs", 1800);
        companion.putString("romanized", "ai ai"); companion.putString("secondary", "love love");
        packet.putBundle("duet", companion);
        return packet;
    }

    /** Select geometry with production font metrics, while keeping the pixel oracle independent. */
    private static int wordsForRows(AutoLyricsView view, int expected) {
        for (int words = 3; words <= 48; words++) {
            Bitmap bitmap = frame(view, 0, words, 100 + words);
            try {
                if (rows(view).size() == expected && view.metrics().getInt("pages") == 1) return words;
            } finally { bitmap.recycle(); }
        }
        throw new AssertionError("cannot construct " + expected + " visual rows on this surface");
    }

    private void checkSecondary(String surface) {
        AutoLyricsView view = newView(surface);
        try {
            Bundle packet = sample(400, 18, 50);
            packet.putString("auto_secondary_text", "Both");
            packet.putString("romanized", "ai ai ai"); packet.putString("secondary", "love love love");
            view.update(packet); layout(view, view.getWidth(), view.getHeight());
            List<TextView> secondary = new ArrayList<>(); collectSecondary(view, secondary);
            if (secondary.size() != ("player-full".equals(surface) ? 2 : 0))
                throw new AssertionError("secondary rows violated surface policy");
            for (TextView text : secondary) {
                Bitmap bitmap = Bitmap.createBitmap(text.getWidth(), text.getHeight(), Bitmap.Config.ARGB_8888);
                try {
                    text.draw(new Canvas(bitmap));
                    int brightest = 0;
                    for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++)
                        brightest = Math.max(brightest, Color.alpha(bitmap.getPixel(x, y)));
                    if (brightest < 240) throw new AssertionError("secondary is not fully bright");
                } finally { bitmap.recycle(); }
            }
        } finally { view.stop(); }
    }

    private static void collectSecondary(View view, List<TextView> result) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(" ")) result.add((TextView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectSecondary(group.getChildAt(i), result);
        }
    }

    private void checkCreditSize(String surface) {
        AutoLyricsView view = newView(surface);
        try {
            Bundle packet = sample(400, 4, 60);
            packet.putString("auto_secondary_text", "Both");
            packet.putString("romanized", "ai ai"); packet.putString("secondary", "love love");
            view.update(packet); layout(view, view.getWidth(), view.getHeight());
            List<TextView> secondary = new ArrayList<>(); collectSecondary(view, secondary);
            float expectedSp = secondary.isEmpty() ? 24f : secondary.get(0).getTextSize()
                    / view.getResources().getDisplayMetrics().scaledDensity;
            packet.putBoolean("creditOutroVisible", true);
            packet.putString("creditWriters", "Test Writer");
            view.update(packet); layout(view, view.getWidth(), view.getHeight());
            List<TextView> credits = new ArrayList<>(); collectSecondary(view, credits);
            if (credits.size() != 1) throw new AssertionError("credit must replace the lyric rows");
            TextView credit = credits.get(0);
            float actualSp = credit.getTextSize() / view.getResources().getDisplayMetrics().scaledDensity;
            if (Math.abs(actualSp - expectedSp) > .01f || actualSp < 20f || actualSp > 24f)
                throw new AssertionError("credit size=" + actualSp + " secondary size=" + expectedSp);
            if (credit.getHeight() > view.getHeight() - view.getPaddingTop() - view.getPaddingBottom())
                throw new AssertionError("credit exceeds the surface");
            packet.putBoolean("creditOutroVisible", false);
            view.update(packet); layout(view, view.getWidth(), view.getHeight());
            if (view.metrics().getBoolean("creditVisible")) throw new AssertionError("credit persisted after seek");
        } finally { view.stop(); }
        checkUnfitCreditRecovery(surface);
    }

    private void checkUnfitCreditRecovery(String surface) {
        AutoSurfaceReadiness readiness;
        try {
            java.lang.reflect.Field field = AndroidAutoPrototype.class.getDeclaredField("surfaceReadiness");
            field.setAccessible(true);
            readiness = (AutoSurfaceReadiness) field.get(null);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        readiness.layoutChanged(surface);
        AutoLyricsView view = newView(surface);
        AutoLyricsView replacement = null;
        try {
            float density = view.getResources().getDisplayMetrics().density;
            int width = Math.round(320 * density), height = Math.round(100 * density);
            Bundle packet = sample(400, 4, 61);
            packet.putBoolean("creditOutroVisible", true);
            ArrayList<String> labels = new ArrayList<>();
            for (int i = 0; i < 10; i++) labels.add("Credit contributor " + i);
            packet.putStringArrayList("creditLabels", labels);
            layout(view, width, height);
            view.update(packet); layout(view, width, height);
            String creditKey = AutoLyricsView.sampleKey(packet);
            if (view.metrics().getInt("pages") != 0 || readiness.allows(surface, creditKey))
                throw new AssertionError("oversized credit must keep native content");
            replacement = new AutoLyricsView(host.getTargetContext());
            replacement.setTag(surface);
            layout(replacement, width, height);
            if (readiness.allows(surface, creditKey))
                throw new AssertionError("same-size remount retried rejected credit");
            Bundle next = sample(400, 4, 62);
            if (!readiness.allows(surface, AutoLyricsView.sampleKey(next)))
                throw new AssertionError("unfit credit blocked the next song's lyrics");
            layout(view, width, Math.round(600 * density));
            if (!readiness.allows(surface, creditKey) || view.metrics().getInt("pages") != 1)
                throw new AssertionError("resized surface did not display the credit");
            view.update(next); layout(view, width, Math.round(600 * density));
            if (view.metrics().getBoolean("creditVisible") || view.metrics().getInt("pages") == 0)
                throw new AssertionError("next song did not restore lyrics");
        } finally {
            view.stop();
            if (replacement != null) replacement.stop();
            readiness.layoutChanged(surface);
        }
    }

    /** Each timed word sweeps on its own timing; silent gaps hold and seeks restore. */
    private void checkTimedFill(String surface, boolean wordBoundaries) {
        AutoLyricsView view = newView(surface);
        activity.setContentView(view);
        layout(view, view.getWidth(), view.getHeight());
        if (!view.isAttachedToWindow()) throw new AssertionError("timed fixture must have a real window");
        if (!view.isShown()) throw new AssertionError("timed fixture must be visible");
        List<Bitmap> frames = new ArrayList<>();
        try {
            Bundle packet = sample(300, 6, 70);
            packet.putBoolean("lineLevelSync", false);
            packet.putLong("lineEndMs", 1200);
            ArrayList<Bundle> words = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Bundle word = new Bundle(); word.putString("text", "愛愛");
                word.putInt("sourceStart", i * 2); word.putInt("sourceEnd", (i + 1) * 2);
                word.putLong("startMs", i == 0 ? 0 : 900 + i * 100L);
                word.putLong("endMs", i == 0 ? 200 : 1000 + i * 100L);
                word.putBoolean("boundaryAfter", wordBoundaries || i == 0 || i == 2); words.add(word);
            }
            packet.putParcelableArrayList("words", words);
            Bitmap early = pausedFrame(view, packet, 300); frames.add(early);
            SpicyAnimatedTextView mountedToken = tailTokens(view, 2).get(0);
            int unsungAlpha = lastTextAlpha(view);
            if (unsungAlpha < 70 || unsungAlpha > 100)
                throw new AssertionError("unsung timed word lost its per-word floor: " + unsungAlpha);
            Bitmap late = pausedFrame(view, packet, 800); frames.add(late);
            if (!early.sameAs(late)) throw new AssertionError("timed fill moved during a silent gap");
            Bitmap sung = pausedFrame(view, packet, 1100); frames.add(sung);
            if (early.sameAs(sung)) throw new AssertionError("next timed token did not fill");
            Bitmap back = pausedFrame(view, packet, 300); frames.add(back);
            if (tailTokens(view, 2).get(0) != mountedToken)
                throw new AssertionError("seek fixture replaced the mounted word");
            if (!early.sameAs(back)) throw new AssertionError("timed fill did not restore after seek");
            checkPerWordSweep(view, packet, frames);
            packet.putBoolean("lineLevelSync", true);
            Bitmap lineUnsung = pausedFrame(view, packet, 0); frames.add(lineUnsung);
            if (lastTextAlpha(view) < 130) throw new AssertionError("line sync lost its bright soft sweep");
            saveFrames(surface + (wordBoundaries ? "-word" : "-syllable"), view, frames);
        } catch (Throwable error) {
            saveFrames(surface + (wordBoundaries ? "-word" : "-syllable"), view, frames);
            throw error;
        } finally { for (Bitmap frame : frames) frame.recycle(); view.stop(); }
        if (wordBoundaries) checkRows(surface, true);
    }

    /** The last word (1100-1200 ms) fills left to right inside its own glyphs. */
    private void checkPerWordSweep(AutoLyricsView view, Bundle packet, List<Bitmap> frames) {
        Bitmap quarter = pausedFrame(view, packet, 1125); frames.add(quarter);
        int[] word = wordBounds(view, tailTokens(view, 2));
        if (bandAlpha(quarter, word, 0f, .2f) < 200)
            throw new AssertionError("current word did not start its own sweep");
        int aheadAlpha = bandAlpha(quarter, word, .7f, 1f);
        if (aheadAlpha > 110) throw new AssertionError("current word filled ahead of its timing: " + aheadAlpha);
        Bitmap later = pausedFrame(view, packet, 1175); frames.add(later);
        if (bandAlpha(later, word, .7f, 1f) < aheadAlpha + 60)
            throw new AssertionError("sweep did not progress inside the word");
        // Sample just before the line ends; at 1200 ms the finished line dims as a whole.
        Bitmap done = pausedFrame(view, packet, 1195); frames.add(done);
        int finished = bandAlpha(done, word, .7f, 1f);
        if (finished < 200) throw new AssertionError("word did not finish its sweep: " + finished);
    }

    private static int[] wordBounds(View root, List<SpicyAnimatedTextView> tokens) {
        int[] origin = new int[2]; root.getLocationInWindow(origin);
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = 0, bottom = 0;
        for (View token : tokens) {
            int[] at = new int[2]; token.getLocationInWindow(at);
            left = Math.min(left, at[0] - origin[0]); top = Math.min(top, at[1] - origin[1]);
            right = Math.max(right, at[0] - origin[0] + token.getWidth());
            bottom = Math.max(bottom, at[1] - origin[1] + token.getHeight());
        }
        return new int[]{left, top, right, bottom};
    }

    /** Brightest alpha within a horizontal fraction of the word's box. */
    private static int bandAlpha(Bitmap frame, int[] word, float from, float to) {
        int width = word[2] - word[0], brightest = 0;
        int x0 = Math.max(0, word[0] + Math.round(width * from));
        int x1 = Math.min(frame.getWidth(), word[0] + Math.round(width * to));
        for (int y = Math.max(0, word[1]); y < Math.min(frame.getHeight(), word[3]); y++)
            for (int x = x0; x < x1; x++) brightest = Math.max(brightest, Color.alpha(frame.getPixel(x, y)));
        return brightest;
    }

    private static List<SpicyAnimatedTextView> tailTokens(View root, int codePoints) {
        List<SpicyAnimatedTextView> tokens = new ArrayList<>(); collectAnimated(root, tokens);
        int start = tokens.size(), covered = 0;
        while (start > 0 && covered < codePoints) {
            String text = tokens.get(--start).getText().toString();
            covered += text.codePointCount(0, text.length());
        }
        if (covered != codePoints) throw new AssertionError("fixture did not preserve word boundaries");
        return tokens.subList(start, tokens.size());
    }

    private static Bitmap textFrame(View text) {
        Bitmap bitmap = Bitmap.createBitmap(text.getWidth(), text.getHeight(), Bitmap.Config.ARGB_8888);
        text.draw(new Canvas(bitmap)); return bitmap;
    }

    private static void collectAnimated(View view, List<SpicyAnimatedTextView> result) {
        if (view instanceof SpicyAnimatedTextView) result.add((SpicyAnimatedTextView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectAnimated(group.getChildAt(i), result);
        }
    }

    private static Bitmap pausedFrame(AutoLyricsView view, Bundle packet, long position) {
        packet.putLong("positionMs", position);
        packet.putInt("generation", 1);
        view.update(packet); layout(view, view.getWidth(), view.getHeight());
        for (int i = 0; i < 60; i++) {
            fixtureFrameNanos += 33_333_333L;
            view.doFrame(fixtureFrameNanos);
        }
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap)); return bitmap;
    }

    private static int lastTextAlpha(View root) {
        if (root instanceof TextView) {
            Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            try {
                root.draw(new Canvas(bitmap));
                int brightest = 0;
                for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++)
                    brightest = Math.max(brightest, Color.alpha(bitmap.getPixel(x, y)));
                return brightest;
            } finally { bitmap.recycle(); }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                int alpha = lastTextAlpha(group.getChildAt(i));
                if (alpha >= 0) return alpha;
            }
        }
        return -1;
    }

    private AutoLyricsView newView(String surface) {
        com.eza.spicyex.ui.Motion.setTransitionFeel("Instant");
        AutoLyricsView view = new AutoLyricsView(host.getTargetContext());
        view.setTag(surface);
        float density = view.getResources().getDisplayMetrics().density;
        layout(view, Math.round(320 * density), Math.round(600 * density));
        return view;
    }

    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static Bitmap frame(AutoLyricsView view, long position, int words, int generation) {
        return frame(view, position, words, generation, false);
    }

    private static Bitmap frame(AutoLyricsView view, long position, int words, int generation, boolean timed) {
        Bundle packet = sample(position, words, generation);
        packet.putBoolean("lineLevelSync", !timed);
        view.update(packet);
        layout(view, view.getWidth(), view.getHeight());
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static Bundle sample(long position, int count, int generation) {
        Bundle packet = new Bundle();
        StringBuilder text = new StringBuilder(); for (int i = 0; i < count; i++) text.append("愛");
        packet.putInt("generation", generation); packet.putInt("lineIndex", 0);
        packet.putString("line", text.toString()); packet.putString("presentation", "lyric");
        packet.putLong("lineStartMs", 0); packet.putLong("lineEndMs", count * 200L);
        packet.putLong("positionMs", position); packet.putBoolean("playing", false);
        packet.putBoolean("hasSyncedLyrics", true); packet.putLong("firstVocalStartMs", 0);
        packet.putString("auto_secondary_text", "Main only");
        packet.putBoolean("lineLevelSync", true);
        ArrayList<Bundle> words = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Bundle word = new Bundle(); word.putString("text", "愛");
            word.putInt("sourceStart", i); word.putInt("sourceEnd", i + 1);
            word.putLong("startMs", i * 200L); word.putLong("endMs", (i + 1) * 200L);
            word.putBoolean("boundaryAfter", i == count - 1); words.add(word);
        }
        packet.putParcelableArrayList("words", words); return packet;
    }

    /** Rows are inferred independently from laid-out glyph leaves, not renderer row indices. */
    private static List<int[]> rows(View root) {
        TreeMap<Integer, int[]> rows = new TreeMap<>(); collect(root, root, rows);
        return new ArrayList<>(rows.values());
    }

    private static void collect(View root, View view, TreeMap<Integer, int[]> rows) {
        if (view instanceof TextView) {
            int top = 0; for (View current = view; current != root; current = (View) current.getParent()) top += current.getTop();
            android.text.Layout layout = ((TextView) view).getLayout();
            if (layout != null && layout.getLineCount() > 1) {
                top += ((TextView) view).getTotalPaddingTop();
                for (int i = 0; i < layout.getLineCount(); i++) {
                    int lineTop = top + layout.getLineTop(i);
                    int[] row = rows.computeIfAbsent(lineTop, ignored -> new int[]{0, 0, 0});
                    row[0] = lineTop; row[1] = top + layout.getLineBottom(i);
                    row[2] += Math.round(layout.getLineWidth(i));
                }
                return;
            }
            int[] row = rows.computeIfAbsent(top, ignored -> new int[]{0, 0, 0});
            row[0] = top; row[1] = Math.max(row[1], top + view.getHeight()); row[2] += view.getWidth();
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(root, group.getChildAt(i), rows);
        }
    }

    private static float[] normalized(Bitmap unsung, Bitmap current, Bitmap sung, List<int[]> rows) {
        float[] progress = new float[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            int[] row = rows.get(i); long base = ink(unsung, row), full = ink(sung, row);
            if (full <= base) throw new AssertionError("no sung/unsung pixel contrast");
            progress[i] = (ink(current, row) - base) / (float) (full - base);
        }
        return progress;
    }

    private static long ink(Bitmap bitmap, int[] row) {
        long sum = 0;
        for (int y = Math.max(0, row[0]); y < Math.min(bitmap.getHeight(), row[1]); y++)
            for (int x = 0; x < bitmap.getWidth(); x++) sum += Color.alpha(bitmap.getPixel(x, y));
        return sum;
    }

    private void saveFrames(String surface, AutoLyricsView view, List<Bitmap> frames) {
        try {
            java.io.File directory = new java.io.File(host.getTargetContext().getCacheDir(), "behavior/" + surface);
            if (!directory.isDirectory() && !directory.mkdirs()) return;
            for (int i = 0; i < frames.size(); i++) {
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(directory, "frame-" + i + ".png"))) {
                    frames.get(i).compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            }
            try (java.io.PrintWriter out = new java.io.PrintWriter(new java.io.File(directory, "geometry.txt"))) {
                out.println(view.metrics()); for (int[] row : rows(view)) out.println(java.util.Arrays.toString(row));
            }
        } catch (java.io.IOException ignored) { }
    }
}
