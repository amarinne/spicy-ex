package com.eza.spicyex.auto;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.eza.spicyex.ui.Motion;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Production-view regressions for slot replacement and reserved-row entrance. */
final class AutoDuetTransitionChecks {
    static void replacement(Context context) {
        Motion.setTransitionFeel("Instant");
        for (boolean roleSwap : new boolean[]{false, true}) {
            AutoLyricsView view = view(context);
            try {
                Bundle packet = packet("愛".repeat(36), 1, 0);
                view.update(packet);
                layout(view);
                ViewGroup rows = rows(view);
                float size = textSize(rows.getChildAt(0));
                int height = rows.getChildAt(0).getHeight();
                Bundle next = packet("愛", 2, 0);
                if (roleSwap) {
                    Bundle survivor = new Bundle(next);
                    survivor.remove("duet");
                    Bundle newcomer = next.getBundle("duet");
                    next.putString("line", newcomer.getString("line"));
                    next.putInt("lineIndex", 2);
                    survivor.putInt("lineIndex", 0);
                    next.putBundle("duet", survivor);
                }
                view.update(next);
                layout(view);
                rows = rows(view);
                if (!view.metrics().getBoolean("duet")) throw new AssertionError("replacement lost duet");
                if (Math.abs(size - textSize(rows.getChildAt(0))) > .01f
                        || height != rows.getChildAt(0).getHeight())
                    throw new AssertionError("surviving sentence resized: before=" + size
                            + " after=" + textSize(rows.getChildAt(0)) + " roleSwap=" + roleSwap);
            } finally { view.stop(); }
        }
    }

    static void deferred(Context context) {
        Motion.setTransitionFeel("Relaxed");
        AutoLyricsView view = view(context);
        try {
            Bundle packet = packet("愛愛", 1, 1000);
            packet.putLong("positionMs", 500);
            view.update(packet); layout(view);
            View companion = rows(view).getChildAt(1);
            if (companion.getVisibility() != View.INVISIBLE) throw new AssertionError("reservation was visible early");
            int height = companion.getHeight();
            packet.putLong("positionMs", 1000);
            view.update(packet); render(view);
            companion = rows(view).getChildAt(1);
            if (companion.getVisibility() != View.VISIBLE || opacity(companion) > .15f)
                throw new AssertionError("reserved row appeared immediately: opacity=" + opacity(companion));
            // Move the real animation clock without sleeping on Android's main thread.
            ageEntrance(view, 105); render(view);
            float middle = opacity(companion);
            if (middle < .3f || middle > .8f) throw new AssertionError("missing intermediate fade: " + middle);
            ageEntrance(view, 300); render(view);
            if (opacity(companion) < .99f || companion.getHeight() != height)
                throw new AssertionError("fade did not settle in reserved geometry");
            packet.putLong("positionMs", 500); view.update(packet); render(view);
            if (companion.getVisibility() != View.INVISIBLE) throw new AssertionError("seek did not hide reserved row");
            packet.putLong("positionMs", 1000); view.update(packet); render(view);
            if (opacity(companion) > .15f) throw new AssertionError("seek replay skipped fade");
            Motion.setTransitionFeel("Instant");
            packet.putLong("positionMs", 500); view.update(packet); render(view);
            packet.putLong("positionMs", 1000); view.update(packet); render(view);
            if (opacity(companion) < .99f) throw new AssertionError("Instant retained an animation");
        } finally { view.stop(); Motion.setTransitionFeel("Instant"); }
    }

    private static float opacity(View row) {
        View main = row instanceof ViewGroup ? ((ViewGroup) row).getChildAt(0) : row;
        return row.getAlpha() * main.getAlpha();
    }

    private static void ageEntrance(AutoLyricsView view, long elapsed) {
        try {
            Field field = AutoLyricsView.class.getDeclaredField("companionEntranceStarted");
            field.setAccessible(true); field.setLong(view, SystemClock.elapsedRealtime() - elapsed);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void render(AutoLyricsView view) {
        try {
            Method method = AutoLyricsView.class.getDeclaredMethod("render", float.class);
            method.setAccessible(true); method.invoke(view, 1f / 30f);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static ViewGroup rows(AutoLyricsView view) {
        ViewGroup stage = (ViewGroup) view.getChildAt(0);
        return (ViewGroup) stage.getChildAt(stage.getChildCount() - 1);
    }

    private static float textSize(View view) {
        if (view instanceof TextView) return ((TextView) view).getTextSize();
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            float size = textSize(((ViewGroup) view).getChildAt(i));
            if (size > 0) return size;
        }
        throw new AssertionError("missing sentence text");
    }

    private static AutoLyricsView view(Context context) {
        AutoLyricsView view = new AutoLyricsView(context); view.setTag("player-full");
        layout(view); return view;
    }

    private static void layout(AutoLyricsView view) {
        float density = view.getResources().getDisplayMetrics().density;
        int width = Math.round(320 * density), height = Math.round(260 * density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static Bundle packet(String companion, int ordinal, long start) {
        Bundle packet = row("愛愛", 0, 0);
        packet.putInt("generation", 701); packet.putBoolean("auto_duet", true);
        packet.putString("auto_secondary_text", "Main only"); packet.putLong("positionMs", 1500);
        packet.putBundle("duet", row(companion, ordinal, start));
        return packet;
    }

    private static Bundle row(String text, int ordinal, long start) {
        Bundle row = new Bundle(); row.putString("line", text); row.putInt("lineIndex", ordinal);
        row.putString("presentation", "lyric"); row.putBoolean("lineLevelSync", true);
        row.putLong("lineStartMs", start); row.putLong("lineEndMs", 5000);
        return row;
    }
}
