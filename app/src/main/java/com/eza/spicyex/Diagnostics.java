package com.eza.spicyex;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;

/** Privacy-safe, rate-limited diagnostics for defensive hook/cache paths. */
public final class Diagnostics {
    private static final String TAG = "[SpotifyPlusDiagnostics]";
    private static final long MIN_LOG_INTERVAL_MS = 60_000L;
    private static final Map<String, Long> LAST_LOG_AT_MS = new ConcurrentHashMap<>();

    private Diagnostics() {
    }

    public static void warn(String component, String operation, Throwable error) {
        warn(component, operation, error, null);
    }

    public static void warn(String component, String operation, Throwable error, String safeContext) {
        if (error == null) return;
        String safeComponent = sanitize(component);
        String safeOperation = sanitize(operation);
        String key = safeComponent + "|" + safeOperation + "|" + error.getClass().getName();
        long now = System.currentTimeMillis();
        Long last = LAST_LOG_AT_MS.get(key);
        if (last != null && now - last < MIN_LOG_INTERVAL_MS) return;
        LAST_LOG_AT_MS.put(key, now);
        StringBuilder message = new StringBuilder(TAG)
                .append(' ')
                .append(safeComponent)
                .append('.')
                .append(safeOperation)
                .append(" failed: ")
                .append(error.getClass().getSimpleName());
        String errorMessage = sanitize(error.getMessage());
        if (!errorMessage.isEmpty()) message.append(" (").append(errorMessage).append(')');
        String context = sanitize(safeContext);
        if (!context.isEmpty()) message.append(" context=").append(context);
        XposedBridge.log(message.toString());
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        String stripped = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (stripped.length() > 96) return stripped.substring(0, 96) + "…";
        return stripped;
    }
}
