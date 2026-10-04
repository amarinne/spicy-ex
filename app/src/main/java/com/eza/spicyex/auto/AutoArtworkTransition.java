package com.eza.spicyex.auto;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;
import com.eza.spicyex.xposed.XpLog;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Keeps the outgoing projected content only until the native artwork handoff finishes. */
final class AutoArtworkTransition {
    private static final List<Snapshot> snapshots = new ArrayList<>();
    private static long capturedAt;
    // Skip the optional snapshot fade above 16 MiB across at most two projected roots.
    private static final long MAX_PIXELS = 4L * 1024 * 1024;

    static void capture() {
        snapshots.clear();
        capturedAt = SystemClock.elapsedRealtime();
        try {
            Class<?> type = Class.forName("android.view.WindowManagerGlobal");
            Object manager = type.getMethod("getInstance").invoke(null);
            Field field = type.getDeclaredField("mViews"); field.setAccessible(true);
            long remainingPixels = MAX_PIXELS;
            for (Object item : new ArrayList<>((List<?>) field.get(manager))) {
                View root = (View) item;
                if (!root.isShown() || root.getDisplay() == null || root.getDisplay().getDisplayId() == 0
                        || root.getWidth() <= 0 || root.getHeight() <= 0) continue;
                long pixels = (long) root.getWidth() * root.getHeight();
                if (snapshots.size() >= 2 || pixels > remainingPixels) continue;
                remainingPixels -= pixels;
                Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
                root.draw(new Canvas(bitmap));
                snapshots.add(new Snapshot(root, bitmap));
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            snapshots.clear();
            XpLog.log("[SpicyAuto] artwork fade capture unavailable " + error);
        }
        // Views take a bounded crop during composition. Never retain a whole projected frame.
        long generation = capturedAt;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (capturedAt == generation) snapshots.clear();
        }, 500);
    }

    static Bitmap take(View view) {
        if (SystemClock.elapsedRealtime() - capturedAt > 500 || view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        for (Snapshot snapshot : snapshots) {
            if (snapshot.root != view.getRootView()) continue;
            int[] location = new int[2], rootLocation = new int[2];
            view.getLocationInWindow(location); snapshot.root.getLocationInWindow(rootLocation);
            int x = location[0] - rootLocation[0], y = location[1] - rootLocation[1];
            if (x < 0 || y < 0 || x + view.getWidth() > snapshot.bitmap.getWidth()
                    || y + view.getHeight() > snapshot.bitmap.getHeight()) return null;
            return Bitmap.createBitmap(snapshot.bitmap, x, y, view.getWidth(), view.getHeight());
        }
        return null;
    }

    private static final class Snapshot {
        final View root;
        final Bitmap bitmap;
        Snapshot(View root, Bitmap bitmap) { this.root = root; this.bitmap = bitmap; }
    }
}
