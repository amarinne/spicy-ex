package com.eza.spicyex.hooks;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import com.eza.spicyex.lyrics.providers.SpicyOrgRetention;
import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;

/** Uses an existing inert receiver action without changing Spotify's manifest or playback. */
public final class SpicyOrgRetentionHook {
    private SpicyOrgRetentionHook() { }

    public static void install(Context context, ClassLoader loader) {
        try {
            android.content.pm.ActivityInfo receiver = context.getPackageManager().getReceiverInfo(
                    new ComponentName(context.getPackageName(), SpicyOrgRetention.RECEIVER), 0);
            if (!receiver.enabled) return;
            Class<?> type = Class.forName(SpicyOrgRetention.RECEIVER, false, loader);
            XpHooks.hookBefore(type.getDeclaredMethod("onReceive", Context.class, Intent.class),
                    "org-retention:receiver", p -> {
                        Context owner = (Context) p.args[0];
                        if (!SpicyOrgRetention.accepts(owner, (Intent) p.args[1])) return;
                        BroadcastReceiver.PendingResult pending = ((BroadcastReceiver) p.thisObject).goAsync();
                        p.setResult(null);
                        SpicyOrgRetention.run(owner, pending::finish);
                    });
            SpicyOrgRetention.install(context);
        } catch (Throwable error) {
            XpLog.log("[SpicyOrgRetention] receiver unavailable", error);
        }
    }
}
