package com.eza.spicyex.lyrics.providers;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.lyrics.catalog.CatalogStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A Spotify-owned alarm can reopen its private catalog after ordinary process death. */
public final class SpicyOrgRetention {
    public static final String RECEIVER = "androidx.profileinstaller.ProfileInstallReceiver";
    public static final String ACTION = "com.eza.spicyex.action.ORG_RETENTION";
    private static final String PREFS = "spicy-org-retention";
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static volatile boolean installed;
    private static long scheduledAt = -1;

    private SpicyOrgRetention() { }

    /** Called only after the matching receiver hook is installed in Spotify. */
    public static void install(Context context) {
        installed = true;
        run(context, null);
    }

    public static boolean accepts(Context context, Intent intent) {
        if (!installed || intent == null || !ACTION.equals(intent.getAction())) return false;
        String nonce = preferences(context).getString("nonce", "");
        return !nonce.isEmpty() && nonce.equals(intent.getStringExtra("nonce"));
    }

    /** Completion releases a manifest receiver's pending result, even if storage fails. */
    public static void run(Context context, Runnable completion) {
        Context app = context.getApplicationContext();
        final Context owner = app == null ? context : app;
        worker.execute(() -> {
            try {
                scheduledAt = -1; // A delivered alarm must be rearmed after a failed cleanup.
                int removed = CatalogStore.pruneExpiredOrg(owner);
                preferences(owner).edit().putLong("lastRunAtMs", System.currentTimeMillis())
                        .putInt("lastRemoved", removed).commit();
                if (removed < 0) setAlarm(owner, System.currentTimeMillis() + 60 * 60 * 1000L);
                else updateAlarm(owner);
            } catch (Throwable error) {
                Diagnostics.warn("SpicyOrgRetention", "cleanup", error);
            } finally {
                if (completion != null) completion.run();
            }
        });
    }

    /** Serialized reads prevent an older callback from replacing a newer deadline. */
    public static void reschedule(Context context) {
        if (!installed || context == null) return;
        Context app = context.getApplicationContext();
        final Context owner = app == null ? context : app;
        worker.execute(() -> {
            try { updateAlarm(owner); }
            catch (Throwable error) { Diagnostics.warn("SpicyOrgRetention", "schedule", error); }
        });
    }

    private static void updateAlarm(Context context) {
        long deadline = CatalogStore.nextOrgExpiryAt(context);
        if (deadline < 0 || deadline == scheduledAt) return;
        setAlarm(context, deadline);
    }

    /** Debug acceptance can deliver the real alarm early; cleanup still checks each row's age. */
    public static void testAlarm(Context context, int seconds) {
        if (!com.eza.spicyex.BuildConfig.DEBUG || !installed || seconds < 1 || seconds > 60)
            throw new IllegalArgumentException("Expected supported debug alarm and 1..60 seconds");
        worker.execute(() -> {
            try { setAlarm(context, System.currentTimeMillis() + seconds * 1000L); }
            catch (Throwable error) { Diagnostics.warn("SpicyOrgRetention", "testAlarm", error); }
        });
    }

    private static void setAlarm(Context context, long deadline) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) throw new IllegalStateException("Alarm service unavailable");
        SharedPreferences prefs = preferences(context);
        String nonce = prefs.getString("nonce", "");
        if (nonce.isEmpty()) {
            nonce = java.util.UUID.randomUUID().toString();
            if (!prefs.edit().putString("nonce", nonce).commit())
                throw new IllegalStateException("Retention identity not saved");
        }
        Intent intent = new Intent(ACTION).setComponent(new ComponentName(context.getPackageName(), RECEIVER))
                .putExtra("nonce", nonce);
        PendingIntent alarm = PendingIntent.getBroadcast(context, 0x53504f52, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (deadline == 0) manager.cancel(alarm);
        else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                Math.max(System.currentTimeMillis() + 1000, deadline), alarm);
        scheduledAt = deadline;
        prefs.edit().putLong("nextRunAtMs", deadline).commit();
    }

    public static String status(Context context) {
        SharedPreferences prefs = preferences(context);
        return "supported=" + installed + " nextRunAtMs=" + prefs.getLong("nextRunAtMs", 0)
                + " lastRunAtMs=" + prefs.getLong("lastRunAtMs", 0)
                + " lastRemoved=" + prefs.getInt("lastRemoved", 0)
                + " accessTerminated=" + SpicyOrgAccessState.isTerminated(context)
                + " canAcquire=" + SpicyOrgAccessState.canAcquire(context);
    }

    public static boolean lastRunSucceeded(Context context) {
        return preferences(context).getInt("lastRemoved", -1) >= 0;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
