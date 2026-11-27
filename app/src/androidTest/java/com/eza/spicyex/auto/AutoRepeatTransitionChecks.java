package com.eza.spicyex.auto;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import com.eza.spicyex.lyrics.SpicyAnimatedTextView;
import com.eza.spicyex.ui.Motion;
import java.util.ArrayList;
import java.util.List;

/** Production-view regressions for reusing mounted lyric rows on repeated lines and separate duet slots. */
final class AutoRepeatTransitionChecks {
    private static long fixtureFrameNanos = 1_000_000_000L;

    private AutoRepeatTransitionChecks() { }

    static void lineSyncedRepeat(Activity activity) {
        Motion.setTransitionFeel("Instant");
        AutoLyricsView view = newView(activity, "player-full");
        activity.setContentView(view);
        layout(view);
        if (!view.isAttachedToWindow()) throw new AssertionError("line repeat fixture must have a real window");
        Motion.setTransitionFeel("Relaxed");
        List<Bitmap> frames = new ArrayList<>();
        try {
            Bundle first = linePacket("愛愛愛愛", 0, 0, 1000, 950);
            Bitmap firstEnd = settledFrame(view, first); frames.add(firstEnd);
            View initialRow = slotRow(view, 0);
            List<SpicyAnimatedTextView> initialViews = animatedViews(initialRow);
            if (initialViews.size() != 1)
                throw new AssertionError("expected one line-level view: " + initialViews.size());

            Bundle repeat = linePacket("愛愛愛愛", 1, 1000, 2000, 1000);
            Bitmap repeatStart = settledFrame(view, repeat); frames.add(repeatStart);
            Bundle metrics = view.metrics();
            if (slotRow(view, 0) != initialRow)
                throw new AssertionError("repeated line rebuilt its row view");
            List<SpicyAnimatedTextView> reusedViews = animatedViews(slotRow(view, 0));
            if (reusedViews.size() != 1 || reusedViews.get(0) != initialViews.get(0))
                throw new AssertionError("repeated line rebuilt its text view");
            if (metrics.getLong("transitionCount") != 0 || metrics.getBoolean("transitionActive"))
                throw new AssertionError("repeated line started a crossfade transition: " + metrics);
            if (metrics.getLong("repeatReuses") != 1)
                throw new AssertionError("expected repeatReuses=1, got " + metrics.getLong("repeatReuses"));
            if (metrics.getFloat("mainAlpha") < .99f)
                throw new AssertionError("repeated line faded its main alpha: " + metrics.getFloat("mainAlpha"));

            long endInk = ink(firstEnd), startInk = ink(repeatStart);
            if (startInk >= endInk * .85f)
                throw new AssertionError("repeat start did not reset gradient: end=" + endInk + " start=" + startInk);

            repeat.putLong("positionMs", 1500);
            Bitmap repeatMid = settledFrame(view, repeat); frames.add(repeatMid);
            long midInk = ink(repeatMid);
            if (midInk <= startInk || midInk >= endInk)
                throw new AssertionError("repeat midpoint did not sweep gradient: start=" + startInk
                        + " mid=" + midInk + " end=" + endInk);

            repeat.putLong("positionMs", 1950);
            Bitmap repeatEnd = settledFrame(view, repeat); frames.add(repeatEnd);
            if (!firstEnd.sameAs(repeatEnd))
                throw new AssertionError("repeat end did not reach the same sung pixels");

            Bundle different = linePacket("愛愛", 2, 2000, 3000, 2000);
            view.update(different);
            if (slotRow(view, 0) == initialRow || view.metrics().getLong("transitionCount") != 1)
                throw new AssertionError("different line did not transition normally");
        } finally {
            for (Bitmap frame : frames) frame.recycle();
            view.stop();
            Motion.setTransitionFeel("Instant");
        }
    }

    static void wordSyncedRepeat(Activity activity) {
        Motion.setTransitionFeel("Instant");
        AutoLyricsView view = newView(activity, "player-full");
        activity.setContentView(view);
        layout(view);
        if (!view.isAttachedToWindow()) throw new AssertionError("word repeat fixture must have a real window");
        Motion.setTransitionFeel("Relaxed");
        List<Bitmap> frames = new ArrayList<>();
        try {
            Bundle first = wordPacket(0, 0, 1200, new long[]{0, 400, 800}, new long[]{300, 700, 1200}, 1195);
            Bitmap firstEnd = settledFrame(view, first); frames.add(firstEnd);
            View initialRow = slotRow(view, 0);
            List<SpicyAnimatedTextView> initialTokens = animatedViews(initialRow);
            if (initialTokens.size() < 3)
                throw new AssertionError("expected timed word views: " + initialTokens.size());
            int sungTail = alphaOf(initialTokens.get(initialTokens.size() - 1));
            if (sungTail < 200) throw new AssertionError("initial line did not finish singing: " + sungTail);

            Bundle repeat = wordPacket(1, 1200, 2400, new long[]{1200, 1600, 2000},
                    new long[]{1500, 1900, 2400}, 1200);
            Bitmap repeatStart = settledFrame(view, repeat); frames.add(repeatStart);
            Bundle metrics = view.metrics();
            if (slotRow(view, 0) != initialRow)
                throw new AssertionError("repeated word-synced line rebuilt its row view");
            List<SpicyAnimatedTextView> reusedTokens = animatedViews(slotRow(view, 0));
            if (reusedTokens.size() != initialTokens.size())
                throw new AssertionError("word token count changed on repeat");
            for (int i = 0; i < initialTokens.size(); i++) {
                if (reusedTokens.get(i) != initialTokens.get(i))
                    throw new AssertionError("word token " + i + " was replaced on repeat");
            }
            if (metrics.getLong("transitionCount") != 0 || metrics.getBoolean("transitionActive"))
                throw new AssertionError("repeated word-synced line started a crossfade: " + metrics);
            if (metrics.getLong("repeatReuses") != 1)
                throw new AssertionError("expected repeatReuses=1, got " + metrics.getLong("repeatReuses"));
            int resetTail = alphaOf(reusedTokens.get(reusedTokens.size() - 1));
            if (resetTail < 70 || resetTail > 110)
                throw new AssertionError("repeat start did not reset trailing word to unsung floor: " + resetTail);

            repeat.putLong("positionMs", 1490);
            Bitmap firstWordDone = settledFrame(view, repeat); frames.add(firstWordDone);
            int headDone = alphaOf(reusedTokens.get(0));
            int tailStillUnsung = alphaOf(reusedTokens.get(reusedTokens.size() - 1));
            if (headDone < 200 || tailStillUnsung > 110)
                throw new AssertionError("repeat did not sweep first word independently: head=" + headDone
                        + " tail=" + tailStillUnsung);

            repeat.putLong("positionMs", 2395);
            Bitmap repeatEnd = settledFrame(view, repeat); frames.add(repeatEnd);
            int tailDone = alphaOf(reusedTokens.get(reusedTokens.size() - 1));
            if (tailDone < 200)
                throw new AssertionError("repeat did not finish sweeping trailing word: " + tailDone);
        } finally {
            for (Bitmap frame : frames) frame.recycle();
            view.stop();
            Motion.setTransitionFeel("Instant");
        }
    }

    static void duetRepeat(Context context) {
        Motion.setTransitionFeel("Relaxed");
        AutoLyricsView view = newView(context, "player-full");
        List<Bitmap> frames = new ArrayList<>();
        try {
            Bundle first = duetPacket(0, 0, 1000, 1, 200, 1200, 950);
            view.update(first);
            layout(view);
            if (!view.metrics().getBoolean("duet") || rows(view).getChildCount() != 2)
                throw new AssertionError("duet fixture did not mount two sections");
            View firstPrimaryRow = slotRow(view, 0);
            View firstCompanionRow = slotRow(view, 1);
            Bitmap before = draw(view); frames.add(before);

            Bundle repeat = duetPacket(2, 1200, 2200, 3, 1400, 2400, 1200);
            view.update(repeat);
            Bundle metrics = view.metrics();
            if (slotRow(view, 0) != firstPrimaryRow || slotRow(view, 1) != firstCompanionRow)
                throw new AssertionError("repeated duet rebuilt its mounted section rows");
            if (metrics.getLong("transitionCount") != 0 || metrics.getBoolean("transitionActive"))
                throw new AssertionError("repeated duet started a crossfade: " + metrics);
            if (metrics.getLong("repeatReuses") != 1 || !metrics.getBoolean("duet"))
                throw new AssertionError("repeated duet metrics mismatch: " + metrics);
            Bitmap after = draw(view); frames.add(after);
            if (ink(after) >= ink(before) * .85f)
                throw new AssertionError("repeated duet did not reset gradient");
        } finally {
            for (Bitmap frame : frames) frame.recycle();
            view.stop();
            Motion.setTransitionFeel("Instant");
        }
    }

    /**
     * When a duet line starts after and finishes before the main line, primary ownership flips
     * A -> B -> A. Neither slot may rebuild, crossfade, or blink across those role swaps.
     */
    static void duetFinishBeforeMain(Activity activity) {
        Motion.setTransitionFeel("Instant");
        AutoLyricsView view = newView(activity, "player-full");
        activity.setContentView(view);
        layout(view);
        if (!view.isAttachedToWindow()) throw new AssertionError("duet finish fixture must have a real window");
        Motion.setTransitionFeel("Relaxed");
        try {
            // t=500: main A (0..5000) active in slot 0; duet B (1000..3000) deferred in slot 1.
            Bundle opening = duetPacket("愛愛", 0, 0, 5000, "愛愛愛", 1, 1000, 3000, 500);
            view.update(opening);
            View mainRow = slotRow(view, 0);
            View duetRow = slotRow(view, 1);
            if (slotHost(view, 1).getVisibility() != View.INVISIBLE)
                throw new AssertionError("deferred duet was visible before its start");

            // t=1000: duet B starts and takes primary role; main A stays in slot 0 untouched.
            Bundle duetStarts = duetPacket("愛愛愛", 1, 1000, 3000, "愛愛", 0, 0, 5000, 1000);
            view.update(duetStarts);
            if (slotRow(view, 0) != mainRow || slotRow(view, 1) != duetRow)
                throw new AssertionError("role swap at duet start rebuilt a mounted section");
            if (view.metrics().getLong("transitionCount") != 0 || view.metrics().getBoolean("transitionActive"))
                throw new AssertionError("role swap at duet start started a crossfade: " + view.metrics());
            if (slotHost(view, 0).getAlpha() < .99f || mainAlpha(mainRow) < .99f)
                throw new AssertionError("main line dimmed when duet started");
            if (slotHost(view, 1).getVisibility() != View.VISIBLE)
                throw new AssertionError("duet did not become visible when it started");

            // Settle the 210ms deferred entrance while both are singing, then advance to t=3000 where duet B finishes before main A.
            ageSlotEntrance(view, 1, 300);
            duetStarts.putLong("positionMs", 2000);
            settledFrame(view, duetStarts).recycle();
            if (slotHost(view, 1).getAlpha() < .99f)
                throw new AssertionError("duet entrance did not settle before finish: " + slotHost(view, 1).getAlpha());
            Bundle duetFinished = duetPacket("愛愛", 0, 0, 5000, "愛愛愛", 1, 1000, 3000, 3000);
            view.update(duetFinished);
            Bundle metrics = view.metrics();
            if (slotRow(view, 0) != mainRow || slotRow(view, 1) != duetRow)
                throw new AssertionError("duet finishing before main line rebuilt a mounted section");
            if (metrics.getLong("transitionCount") != 0 || metrics.getBoolean("transitionActive"))
                throw new AssertionError("duet finishing before main line triggered a crossfade: " + metrics);
            if (slotHost(view, 0).getVisibility() != View.VISIBLE || slotHost(view, 0).getAlpha() < .99f
                    || mainAlpha(mainRow) < .99f)
                throw new AssertionError("main line blinked when duet finished: host="
                        + slotHost(view, 0).getAlpha() + " row=" + mainAlpha(mainRow));
            if (slotHost(view, 1).getVisibility() != View.VISIBLE || slotHost(view, 1).getAlpha() < .99f
                    || mainAlpha(duetRow) < .80f)
                throw new AssertionError("duet line blinked when finishing before main line: host="
                        + slotHost(view, 1).getAlpha() + " row=" + mainAlpha(duetRow));
        } finally {
            view.stop();
            Motion.setTransitionFeel("Instant");
        }
    }

    /**
     * Main and duet slots render independently: a duet repeat rebinds only the duet slot, and a
     * duet replacement crossfades only the duet slot without touching the continuing main line.
     */
    static void duetIndependentSlots(Activity activity) {
        Motion.setTransitionFeel("Instant");
        AutoLyricsView view = newView(activity, "player-full");
        activity.setContentView(view);
        layout(view);
        if (!view.isAttachedToWindow()) throw new AssertionError("duet slot fixture must have a real window");
        Motion.setTransitionFeel("Relaxed");
        try {
            Bundle first = duetPacket("愛愛", 0, 0, 6000, "愛愛愛", 1, 500, 2500, 1500);
            view.update(first);
            View mainRow = slotRow(view, 0);
            View duetRow = slotRow(view, 1);

            // Duet repeats (lineIndex 2, same text) while main A continues untouched.
            Bundle duetRepeated = duetPacket("愛愛", 0, 0, 6000, "愛愛愛", 2, 2500, 4500, 2500);
            view.update(duetRepeated);
            if (slotRow(view, 0) != mainRow || slotRow(view, 1) != duetRow)
                throw new AssertionError("duet repeat rebuilt a mounted section");
            if (view.metrics().getLong("transitionCount") != 0 || view.metrics().getLong("repeatReuses") != 1)
                throw new AssertionError("duet repeat did not reuse in place: " + view.metrics());

            // Duet changes to different text (lineIndex 3) while main A still continues untouched.
            Bundle duetChanged = duetPacket("愛愛", 0, 0, 6000, "愛愛愛愛", 3, 4500, 5500, 4500);
            view.update(duetChanged);
            if (slotRow(view, 0) != mainRow)
                throw new AssertionError("changing duet rebuilt the continuing main line");
            if (mainAlpha(mainRow) < .99f || slotHost(view, 0).getAlpha() < .99f)
                throw new AssertionError("changing duet faded the continuing main line");
            if (slotRow(view, 1) == duetRow)
                throw new AssertionError("changing duet did not mount the new duet row");
            if (view.metrics().getLong("transitionCount") != 1)
                throw new AssertionError("changing duet did not animate the duet slot: " + view.metrics());
        } finally {
            view.stop();
            Motion.setTransitionFeel("Instant");
        }
    }

    private static Bitmap settledFrame(AutoLyricsView view, Bundle packet) {
        view.update(packet);
        for (int i = 0; i < 6; i++) {
            fixtureFrameNanos += 33_333_333L;
            view.doFrame(fixtureFrameNanos);
        }
        return draw(view);
    }

    private static Bitmap draw(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static long ink(Bitmap bitmap) {
        long sum = 0;
        for (int y = 0; y < bitmap.getHeight(); y++)
            for (int x = 0; x < bitmap.getWidth(); x++)
                sum += Color.alpha(bitmap.getPixel(x, y));
        return sum;
    }

    private static int alphaOf(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            view.draw(new Canvas(bitmap));
            int brightest = 0;
            for (int y = 0; y < bitmap.getHeight(); y++)
                for (int x = 0; x < bitmap.getWidth(); x++)
                    brightest = Math.max(brightest, Color.alpha(bitmap.getPixel(x, y)));
            return brightest;
        } finally { bitmap.recycle(); }
    }

    private static float mainAlpha(View row) {
        View main = row instanceof ViewGroup && ((ViewGroup) row).getChildCount() > 0
                ? ((ViewGroup) row).getChildAt(0) : row;
        return row.getAlpha() * main.getAlpha();
    }

    private static List<SpicyAnimatedTextView> animatedViews(View root) {
        List<SpicyAnimatedTextView> views = new ArrayList<>();
        collectAnimated(root, views);
        return views;
    }

    private static void collectAnimated(View view, List<SpicyAnimatedTextView> result) {
        if (view instanceof SpicyAnimatedTextView) result.add((SpicyAnimatedTextView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectAnimated(group.getChildAt(i), result);
        }
    }

    private static ViewGroup rows(AutoLyricsView view) {
        ViewGroup stage = (ViewGroup) view.getChildAt(0);
        return (ViewGroup) stage.getChildAt(stage.getChildCount() - 1);
    }

    private static ViewGroup slotHost(AutoLyricsView view, int index) {
        return (ViewGroup) rows(view).getChildAt(index);
    }

    private static View slotRow(AutoLyricsView view, int index) {
        return slotHost(view, index).getChildAt(0);
    }

    private static void ageSlotEntrance(AutoLyricsView view, int index, long elapsedMs) {
        try {
            java.lang.reflect.Field slotsField = AutoLyricsView.class.getDeclaredField("slots");
            slotsField.setAccessible(true);
            Object[] slots = (Object[]) slotsField.get(view);
            java.lang.reflect.Field started = slots[index].getClass().getDeclaredField("deferredEntranceStarted");
            started.setAccessible(true);
            started.setLong(slots[index], android.os.SystemClock.elapsedRealtime() - elapsedMs);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static AutoLyricsView newView(Context context, String surface) {
        AutoLyricsView view = new AutoLyricsView(context);
        view.setTag(surface);
        layout(view);
        return view;
    }

    private static void layout(AutoLyricsView view) {
        float density = view.getResources().getDisplayMetrics().density;
        int width = Math.round(320 * density), height = Math.round(260 * density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static Bundle linePacket(String text, int index, long startMs, long endMs, long positionMs) {
        Bundle packet = new Bundle();
        packet.putInt("generation", 901);
        packet.putInt("lineIndex", index);
        packet.putString("line", text);
        packet.putString("presentation", "lyric");
        packet.putLong("lineStartMs", startMs);
        packet.putLong("lineEndMs", endMs);
        packet.putLong("positionMs", positionMs);
        packet.putBoolean("playing", false);
        packet.putBoolean("hasSyncedLyrics", true);
        packet.putLong("firstVocalStartMs", 0);
        packet.putString("auto_secondary_text", "Main only");
        packet.putBoolean("lineLevelSync", true);
        return packet;
    }

    private static Bundle wordPacket(int index, long startMs, long endMs, long[] wordStarts, long[] wordEnds,
                                     long positionMs) {
        Bundle packet = linePacket("愛愛愛愛愛愛", index, startMs, endMs, positionMs);
        packet.putBoolean("lineLevelSync", false);
        ArrayList<Bundle> words = new ArrayList<>();
        for (int i = 0; i < wordStarts.length; i++) {
            Bundle word = new Bundle();
            word.putString("text", "愛愛");
            word.putInt("sourceStart", i * 2);
            word.putInt("sourceEnd", (i + 1) * 2);
            word.putLong("startMs", wordStarts[i]);
            word.putLong("endMs", wordEnds[i]);
            word.putBoolean("boundaryAfter", true);
            words.add(word);
        }
        packet.putParcelableArrayList("words", words);
        return packet;
    }

    private static Bundle duetPacket(int primaryIndex, long pStart, long pEnd,
                                     int companionIndex, long cStart, long cEnd, long positionMs) {
        return duetPacket("愛愛", primaryIndex, pStart, pEnd, "愛愛愛", companionIndex, cStart, cEnd, positionMs);
    }

    private static Bundle duetPacket(String primaryText, int primaryIndex, long pStart, long pEnd,
                                     String companionText, int companionIndex, long cStart, long cEnd,
                                     long positionMs) {
        Bundle packet = linePacket(primaryText, primaryIndex, pStart, pEnd, positionMs);
        packet.putBoolean("auto_duet", true);
        Bundle companion = linePacket(companionText, companionIndex, cStart, cEnd, positionMs);
        packet.putBundle("duet", companion);
        return packet;
    }
}
