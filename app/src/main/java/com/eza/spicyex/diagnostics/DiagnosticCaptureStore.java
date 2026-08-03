package com.eza.spicyex.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Spotify-cache capture state. It never reads logcat, LSPosed files, or arbitrary paths. */
public final class DiagnosticCaptureStore {
    public static final long TTL_MS = 30L * 60L * 1000L;
    private static final long DEDUPE_MS = 5_000L;
    private static final String PREFS = "spicy_diagnostic_capture_v1";
    private static final String FILE_NAME = "spicy-diagnostic-events-v1.jsonl";
    private static final String DESCRIPTION_FILE_NAME = "spicy-diagnostic-description-v1.txt";
    private static final Object LOCK = new Object();
    private static final Map<String, Long> LAST_EVENT_AT = new ConcurrentHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable expiryTask;

    private DiagnosticCaptureStore() {
    }

    public static CaptureState start(Context context, String reportId, String category,
                                     String description) {
        synchronized (LOCK) {
            if (!DiagnosticReportContract.validReportId(reportId)
                    || !DiagnosticReportContract.validCategory(category)
                    || !DiagnosticReportContract.validDescription(description)) {
                return CaptureState.inactive();
            }
            clearLocked(context);
            File file = eventFile(context);
            boolean created = write(file, "");
            boolean descriptionCreated = writeDescription(context, description);
            long wall = System.currentTimeMillis();
            long elapsed = SystemClock.elapsedRealtime();
            boolean committed = prefs(context).edit()
                    .putBoolean("active", true)
                    .putString("report_id", reportId)
                    .putString("category", category)
                    .putLong("started_wall", wall)
                    .putLong("started_elapsed", elapsed)
                    .putBoolean("interrupted", !created || !descriptionCreated)
                    .putBoolean("truncated", false)
                    .commit();
            if (!committed) {
                clearLocked(context);
                return CaptureState.inactive();
            }
            scheduleExpiry(context, TTL_MS);
            return readLocked(context, true);
        }
    }

    public static CaptureState state(Context context) {
        synchronized (LOCK) {
            return readLocked(context, true);
        }
    }

    public static FinishedCapture finish(Context context) {
        synchronized (LOCK) {
            CaptureState state = readLocked(context, true);
            if (!state.active) return null;
            String events = readBounded(eventFile(context));
            boolean interrupted = state.interrupted;
            if (events == null) {
                events = "";
                interrupted = true;
            }
            FinishedCapture result = new FinishedCapture(
                    state.reportId,
                    state.category,
                    state.description,
                    state.startedAtWallMs,
                    System.currentTimeMillis(),
                    interrupted ? "interrupted" : "captured",
                    interrupted,
                    state.truncated,
                    events
            );
            clearLocked(context);
            return result;
        }
    }

    public static void cancel(Context context) {
        synchronized (LOCK) {
            clearLocked(context);
        }
    }

    public static void record(Context context, String component, String operation, Throwable error,
                              Map<String, String> safeContext) {
        synchronized (LOCK) {
            CaptureState state = readLocked(context, true);
            if (!state.active) return;
            DiagnosticEventBuffer.Event event = DiagnosticEventBuffer.event(
                    System.currentTimeMillis(), component, operation, error, safeContext);
            String key = event.component + '|' + event.operation + '|' + event.exceptionClass
                    + '|' + event.context;
            long now = SystemClock.elapsedRealtime();
            Long last = LAST_EVENT_AT.get(key);
            if (last != null && !DiagnosticCapturePolicy.shouldRecord(last, now, DEDUPE_MS)) return;
            LAST_EVENT_AT.put(key, now);
            File file = eventFile(context);
            String existing = readBounded(file);
            if (existing == null) {
                existing = "";
                markInterrupted(context);
            }
            DiagnosticEventBuffer.Result result = DiagnosticEventBuffer.append(existing, event);
            if (!write(file, result.jsonl)) {
                markInterrupted(context);
                return;
            }
            if (result.truncated) prefs(context).edit().putBoolean("truncated", true).apply();
        }
    }

    private static CaptureState readLocked(Context context, boolean repairMissingFile) {
        SharedPreferences preferences = prefs(context);
        if (!preferences.getBoolean("active", false)) return CaptureState.inactive();
        long wall = preferences.getLong("started_wall", -1L);
        long elapsed = preferences.getLong("started_elapsed", -1L);
        long nowWall = System.currentTimeMillis();
        long nowElapsed = SystemClock.elapsedRealtime();
        if (DiagnosticCapturePolicy.expired(wall, elapsed, nowWall, nowElapsed, TTL_MS)) {
            clearLocked(context);
            return CaptureState.inactive();
        }
        boolean interrupted = preferences.getBoolean("interrupted", false);
        File file = eventFile(context);
        if (DiagnosticCapturePolicy.interruptedAfterRestore(true, file.exists()) && repairMissingFile) {
            interrupted = true;
            write(file, "");
            preferences.edit().putBoolean("interrupted", true).apply();
        }
        String description = readDescription(context);
        if (!DiagnosticReportContract.validDescription(description)) {
            description = "";
            interrupted = true;
            preferences.edit().putBoolean("interrupted", true).apply();
        }
        scheduleExpiry(context, TTL_MS - (nowElapsed - elapsed));
        return new CaptureState(
                true,
                preferences.getString("report_id", ""),
                preferences.getString("category", "other"),
                description,
                wall,
                elapsed,
                interrupted,
                preferences.getBoolean("truncated", false)
        );
    }

    private static void scheduleExpiry(Context context, long delayMs) {
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        if (expiryTask != null) MAIN.removeCallbacks(expiryTask);
        expiryTask = () -> {
            synchronized (LOCK) {
                readLocked(app, false);
            }
        };
        MAIN.postDelayed(expiryTask, Math.max(1L, delayMs));
    }

    private static void markInterrupted(Context context) {
        prefs(context).edit().putBoolean("interrupted", true).apply();
    }

    private static String readBounded(File file) {
        try {
            if (!file.exists() || file.length() > DiagnosticEventBuffer.MAX_BYTES) return null;
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean write(File file, String value) {
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > DiagnosticEventBuffer.MAX_BYTES) return false;
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(bytes);
        } catch (Throwable ignored) {
            temporary.delete();
            return false;
        }
        if (file.exists() && !file.delete()) {
            temporary.delete();
            return false;
        }
        return temporary.renameTo(file);
    }

    private static void clearLocked(Context context) {
        if (expiryTask != null) MAIN.removeCallbacks(expiryTask);
        expiryTask = null;
        LAST_EVENT_AT.clear();
        eventFile(context).delete();
        new File(eventFile(context).getParentFile(), FILE_NAME + ".tmp").delete();
        descriptionFile(context).delete();
        prefs(context).edit().clear().commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File eventFile(Context context) {
        return new File(context.getCacheDir(), FILE_NAME);
    }

    private static File descriptionFile(Context context) {
        return new File(context.getNoBackupFilesDir(), DESCRIPTION_FILE_NAME);
    }

    private static boolean writeDescription(Context context, String description) {
        byte[] bytes = description.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > DiagnosticReportContract.DESCRIPTION_BYTES) return false;
        try (FileOutputStream output = new FileOutputStream(descriptionFile(context), false)) {
            output.write(bytes);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String readDescription(Context context) {
        File file = descriptionFile(context);
        try {
            if (!file.exists() || file.length() <= 0L
                    || file.length() > DiagnosticReportContract.DESCRIPTION_BYTES) return "";
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static final class CaptureState {
        public final boolean active;
        public final String reportId;
        public final String category;
        public final String description;
        public final long startedAtWallMs;
        public final long startedAtElapsedMs;
        public final boolean interrupted;
        public final boolean truncated;

        CaptureState(boolean active, String reportId, String category, String description,
                     long startedAtWallMs, long startedAtElapsedMs, boolean interrupted,
                     boolean truncated) {
            this.active = active;
            this.reportId = reportId;
            this.category = category;
            this.description = description;
            this.startedAtWallMs = startedAtWallMs;
            this.startedAtElapsedMs = startedAtElapsedMs;
            this.interrupted = interrupted;
            this.truncated = truncated;
        }

        static CaptureState inactive() {
            return new CaptureState(false, "", "other", "", 0L, 0L, false, false);
        }
    }

    public static final class FinishedCapture {
        public final String reportId;
        public final String category;
        public final String description;
        public final long startedAtWallMs;
        public final long finishedAtWallMs;
        public final String outcome;
        public final boolean interrupted;
        public final boolean truncated;
        public final String eventsJsonl;

        FinishedCapture(String reportId, String category, String description,
                        long startedAtWallMs, long finishedAtWallMs, String outcome,
                        boolean interrupted, boolean truncated, String eventsJsonl) {
            this.reportId = reportId;
            this.category = category;
            this.description = description;
            this.startedAtWallMs = startedAtWallMs;
            this.finishedAtWallMs = finishedAtWallMs;
            this.outcome = outcome;
            this.interrupted = interrupted;
            this.truncated = truncated;
            this.eventsJsonl = eventsJsonl;
        }
    }
}
