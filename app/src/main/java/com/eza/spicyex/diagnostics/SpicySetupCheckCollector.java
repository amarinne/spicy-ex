package com.eza.spicyex.diagnostics;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;

import de.robv.android.xposed.XposedBridge;

/** Bounded runtime checks only. Never invokes root or reads logs/files. */
public final class SpicySetupCheckCollector {
    private static final String[] LSPATCH_MARKERS = {
            "org.lsposed.lspatch.loader.LSPApplication",
            "org.lsposed.lspatch.metaloader.LSPAppComponentFactoryStub",
            "org.lsposed.lspatch.service.ILSPApplicationService"
    };
    private static final String[] LSPOSED_MARKERS = {
            "org.lsposed.lspd.impl.LSPosedBridge",
            "org.lsposed.lspd.core.Main",
            "org.lsposed.lspd.nativebridge.NativeAPI",
            "org.lsposed.lspd.nativebridge.HookBridge",
            "org.lsposed.lspd.loader.Main",
            "org.lsposed.lspd.core.nativebridge.NativeAPI",
            "org.lsposed.lspd.core.nativebridge.HookBridge"
    };

    private SpicySetupCheckCollector() {
    }

    public static SpicySetupCheckPolicy.Result collect(Context context, SettingsStore settings) {
        int xposedApiVersion = xposedApiVersion();
        boolean xposedApiAvailable = xposedApiVersion > 0;
        String processName = Application.getProcessName();
        String bridgeStatus = safeToken(Diagnostics.hyperGlowBridgeStatus());
        return SpicySetupCheckPolicy.resolve(new SpicySetupCheckPolicy.Input(
                Diagnostics.runtimeHookActive(),
                xposedApiAvailable,
                SpicySetupCheckPolicy.installationMode(
                        hasLspatchMarker(context), hasAnyClass(LSPOSED_MARKERS),
                        xposedApiVersion, runtimeClassLoaderEvidence()),
                "com.spotify.music".equals(processName),
                Diagnostics.runtimeHookActive(),
                modulePackageStatus(context),
                context.checkSelfPermission(Manifest.permission.INTERNET)
                        == PackageManager.PERMISSION_GRANTED,
                Diagnostics.hookBootstrapComplete(),
                settings != null && settings.get(Settings.HYPERGLOW_ENABLED),
                bridgeStatus
        ));
    }

    private static boolean hasLspatchMarker(Context context) {
        try {
            String factory = context.getApplicationInfo().appComponentFactory;
            String application = context.getApplicationInfo().className;
            if (startsWithLspatch(factory) || startsWithLspatch(application)) return true;
        } catch (Throwable ignored) {
            // Fall through to bounded runtime class markers.
        }
        return hasAnyClass(LSPATCH_MARKERS);
    }

    private static boolean startsWithLspatch(String value) {
        return value != null && value.startsWith("org.lsposed.lspatch.");
    }

    private static int xposedApiVersion() {
        try {
            return Math.max(0, XposedBridge.getXposedVersion());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static boolean hasAnyClass(String[] names) {
        ClassLoader[] loaders = runtimeClassLoaders();
        for (String name : names) {
            for (ClassLoader loader : loaders) {
                try {
                    // A null loader intentionally probes the boot class path used by LSPosed.
                    Class.forName(name, false, loader);
                    return true;
                } catch (ClassNotFoundException ignored) {
                    // Try the next bounded marker/loader.
                } catch (LinkageError ignored) {
                    // A broken marker is not positive evidence.
                }
            }
        }
        return false;
    }

    private static String[] runtimeClassLoaderEvidence() {
        ClassLoader[] loaders = runtimeClassLoaders();
        String[] evidence = new String[loaders.length * 2];
        int index = 0;
        for (ClassLoader loader : loaders) {
            if (loader == null) continue;
            try {
                evidence[index++] = bounded(loader.getClass().getName(), 256);
            } catch (Throwable ignored) {
                // Missing class-loader identity is not positive evidence.
            }
            try {
                evidence[index++] = bounded(String.valueOf(loader), 512);
            } catch (Throwable ignored) {
                // Missing class-loader description is not positive evidence.
            }
        }
        return evidence;
    }

    private static ClassLoader[] runtimeClassLoaders() {
        return new ClassLoader[]{
                XposedBridge.class.getClassLoader(),
                SpicySetupCheckCollector.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader()
        };
    }

    private static String bounded(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) return value;
        return value.substring(0, maxChars);
    }

    private static String modulePackageStatus(Context context) {
        try {
            context.getPackageManager().getPackageInfo("com.eza.spicyex", 0);
            return "present";
        } catch (PackageManager.NameNotFoundException ignored) {
            return "not_visible_or_missing";
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    private static String safeToken(String value) {
        String token = DiagnosticEventBuffer.safeToken(value, 32);
        return token.isEmpty() ? "unknown" : token;
    }
}
