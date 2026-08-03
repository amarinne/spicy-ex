package com.eza.spicyex;

import android.content.Context;

import com.eza.spicyex.diagnostics.DiagnosticCaptureStore;
import com.eza.spicyex.diagnostics.DiagnosticEventBuffer;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;

/** Privacy-safe, rate-limited diagnostics for defensive hook/cache paths. */
public final class Diagnostics {
    private static final String TAG = "[SpotifyPlusDiagnostics]";
    private static final long MIN_LOG_INTERVAL_MS = 60_000L;
    private static final Map<String, Long> LAST_LOG_AT_MS = new ConcurrentHashMap<>();
    private static volatile Context applicationContext;
    private static volatile String hyperGlowBridgeStatus = "disabled";
    private static volatile boolean runtimeHookActive;
    private static volatile boolean hookBootstrapComplete;

    private Diagnostics() {
    }

    public static void initialize(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        applicationContext = app == null ? context : app;
        DiagnosticCaptureStore.state(applicationContext);
    }

    public static void markHookRuntimeActive() {
        runtimeHookActive = true;
    }

    public static void markHookBootstrapComplete() {
        hookBootstrapComplete = true;
    }

    public static boolean runtimeHookActive() {
        return runtimeHookActive;
    }

    public static boolean hookBootstrapComplete() {
        return hookBootstrapComplete;
    }

    public static void event(String component, String operation) {
        event(component, operation, null, Collections.emptyMap());
    }

    public static void event(String component, String operation, Map<String, String> safeContext) {
        event(component, operation, null, safeContext);
    }

    public static void event(String component, String operation, Throwable error,
                             Map<String, String> safeContext) {
        Context context = applicationContext;
        if (context == null) return;
        DiagnosticCaptureStore.record(context, component, operation, error, safeContext);
    }

    public static Map<String, String> context(String... keyValues) {
        return DiagnosticEventBuffer.context(keyValues);
    }

    public static void setHyperGlowBridgeStatus(String status) {
        hyperGlowBridgeStatus = DiagnosticEventBuffer.safeToken(status, 32);
    }

    public static String hyperGlowBridgeStatus() {
        return hyperGlowBridgeStatus.isEmpty() ? "unknown" : hyperGlowBridgeStatus;
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
        event(safeComponent, safeOperation, error, Collections.emptyMap());
        StringBuilder message = new StringBuilder(TAG)
                .append(' ')
                .append(safeComponent)
                .append('.')
                .append(safeOperation)
                .append(" failed: ")
                .append(error.getClass().getSimpleName());
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
