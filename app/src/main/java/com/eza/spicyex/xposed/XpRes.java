package com.eza.spicyex.xposed;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;

/**
 * Module {@link Resources} provider replacing the legacy module-resources
 * contract, which has no API 102 equivalent.
 *
 * <p>The entry builds {@link Resources} eagerly from the module APK path at
 * load time (system display metrics, default configuration), so drawables,
 * fonts, and strings resolve even when no host context exists yet or package
 * visibility blocks {@code createPackageContext}. When a host context is
 * available, a package context is preferred for theme-correct resources.
 */
public final class XpRes {
    static final String MODULE_PACKAGE = "com.eza.spicyex";

    private static volatile String moduleSourceDir;
    private static volatile Resources cached;

    private XpRes() {
    }

    public static void init(ApplicationInfo moduleInfo) {
        if (moduleInfo != null) init(moduleInfo.sourceDir);
    }

    public static synchronized void init(String sourceDir) {
        if (sourceDir != null) moduleSourceDir = sourceDir;
        Resources built = buildFromApk(moduleSourceDir);
        if (built != null) cached = built;
    }

    /** Module resources, or null only when the APK path was never captured. */
    public static synchronized Resources moduleResources(Context hostContext) {
        if (hostContext != null) {
            try {
                Context moduleContext = MODULE_PACKAGE.equals(hostContext.getPackageName())
                        ? hostContext
                        : hostContext.createPackageContext(
                                MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
                return moduleContext.getResources();
            } catch (Throwable ignored) {
            }
        }
        if (cached != null) return cached;
        Resources built = buildFromApk(moduleSourceDir);
        if (built != null) cached = built;
        return cached;
    }

    @SuppressWarnings("deprecation")
    private static Resources buildFromApk(String sourceDir) {
        if (sourceDir == null) return null;
        try {
            AssetManager assets = AssetManager.class.newInstance();
            AssetManager.class.getMethod("addAssetPath", String.class).invoke(assets, sourceDir);
            Resources system = Resources.getSystem();
            DisplayMetrics metrics = system.getDisplayMetrics();
            Configuration config = new Configuration(system.getConfiguration());
            return new Resources(assets, metrics, config);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
