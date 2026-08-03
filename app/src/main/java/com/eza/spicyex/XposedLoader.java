package com.eza.spicyex;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.XModuleResources;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import com.eza.spicyex.hooks.*;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import org.luckypray.dexkit.DexKitBridge;

import java.io.File;
import java.lang.ref.WeakReference;

public class XposedLoader implements IXposedHookLoadPackage, IXposedHookZygoteInit, IXposedHookInitPackageResources {
    static {
        System.loadLibrary("dexkit");
    }

    private DexKitBridge bridge;
    private String modulePath = null;
    private static final String MODULE_VERSION = BuildStamp.FULL;
    private static boolean injectionToastShown = false;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.spotify.music")) return;
        Diagnostics.markHookRuntimeActive();
        XposedBridge.log("[SpotifyPlus] Loading SpotifyPlus v" + MODULE_VERSION);

        if (bridge == null) {
            try {
                bridge = DexKitBridge.create(lpparam.appInfo.sourceDir);
            } catch (Exception e) {
                XposedBridge.log(e);
            }
        }

        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = (Activity) param.thisObject;
                References.setCurrentActivity(activity);

                if (!injectionToastShown) {
                    injectionToastShown = true;
                    try {
                        SpotifyPlusConfig config = SpotifyPlusConfig.from(activity);
                        String mode = config.lyricsDisplayMode();
                        XposedBridge.log("[SpotifyPlus] Injection confirmed activity=" + activity.getClass().getName() + " displayMode=" + mode);
                    } catch (Throwable t) {
                        XposedBridge.log(t);
                    }
                }
            }
        });

        XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                References.clearCurrentActivity((Activity) param.thisObject);
            }
        });

        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = (Activity) param.thisObject;
                Typeface beautifulFont = References.beautifulFont.get();

                if (beautifulFont != null) return;

                try {
                    Resources resources = XModuleResources.createInstance(modulePath, null);
//                    beautifulFont = Typeface.createFromAsset(resources.getAssets(), "fonts/lyrics_medium.ttf");
                    beautifulFont = Typeface.createFromAsset(resources.getAssets(), "fonts/sf-pro-display-bold.ttf");

                    XposedBridge.log("[SpotifyPlus] Successfully loaded font!");
                } catch (Throwable t) {
                    XposedBridge.log("[SpotifyPlus] Failed to load font (error)");
                    XposedBridge.log(t);
                }

                if (beautifulFont != null) {
                    References.beautifulFont = new WeakReference<>(beautifulFont);
                }

            }
        });

        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.args[0];
                Diagnostics.initialize(context);
                Diagnostics.event("bootstrap", "application_attach",
                        Diagnostics.context("process", Application.getProcessName()));
                cleanUpCache(context);

                // Native Spicy build: mount Android-native lyrics shell and keep Spotify internals as fallback/reference.
                new NativeSpicyLyricsHook(context).init(lpparam, bridge);
                Diagnostics.markHookBootstrapComplete();
                Diagnostics.event("bootstrap", "hook_init_complete");
            }
        });
    }

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        modulePath = startupParam.modulePath;
    }

    private void cleanUpCache(Context context) {
        File[] files = context.getCacheDir().listFiles();

        for (File file : files) {
            if (file.getName().endsWith(".apk")) {
                file.delete();
            }
        }
    }

    @Override
    public void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam resparam) throws Throwable {
        if (!"com.spotify.music".equals(resparam.packageName)) {
            return;
        }

        References.modResources = XModuleResources.createInstance(modulePath, resparam.res);
        References.xresources = resparam.res;
    }
}
