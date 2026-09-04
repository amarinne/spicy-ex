package com.eza.spicyex.xposed;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * Framework log facade over LibXposed API 102 with a logcat/stdout fallback so
 * JVM unit tests keep working without a module host.
 *
 * <p>Call sites keep the legacy log shapes: message,
 * message plus throwable, or bare throwable.
 */
public final class XpLog {
    private static final String TAG = "SpicyEX";
    private static volatile XposedInterface api;

    private XpLog() {
    }

    public static void attach(XposedInterface xposed) {
        api = xposed;
    }

    /** Runtime Xposed API version, or 102 when no module host is attached (JVM tests). */
    public static int apiVersion() {
        XposedInterface current = api;
        if (current == null) return XposedInterface.API_102;
        try {
            return current.getApiVersion();
        } catch (Throwable ignored) {
            return XposedInterface.API_102;
        }
    }

    public static void log(String message) {
        log(Log.INFO, TAG, message == null ? "null" : message, null);
    }

    public static void log(Throwable throwable) {
        if (throwable == null) {
            log("null");
            return;
        }
        log(Log.WARN, TAG, throwable.toString(), throwable);
    }

    public static void log(String message, Throwable throwable) {
        log(Log.WARN, TAG, message == null ? String.valueOf(throwable) : message, throwable);
    }

    public static void log(int priority, String tag, String message) {
        log(priority, tag, message, null);
    }

    public static void log(int priority, String tag, String message, Throwable throwable) {
        XposedInterface current = api;
        if (current != null) {
            try {
                current.log(priority, tag, message == null ? "null" : message, throwable);
                return;
            } catch (Throwable ignored) {
                // Fall through to the local fallback below.
            }
        }
        try {
            Log.println(priority, tag == null ? TAG : tag,
                    message == null ? "null" : message
                            + (throwable == null ? "" : "\n" + Log.getStackTraceString(throwable)));
        } catch (Throwable ignored) {
            System.out.println((tag == null ? TAG : tag) + ": " + message);
            if (throwable != null) throwable.printStackTrace(System.out);
        }
    }
}
