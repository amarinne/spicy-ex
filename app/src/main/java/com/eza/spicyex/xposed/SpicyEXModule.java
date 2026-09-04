package com.eza.spicyex.xposed;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;

import androidx.annotation.NonNull;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.References;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.hooks.NativeSpicyLyricsHook;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import org.luckypray.dexkit.DexKitBridge;

import java.io.File;
import java.lang.ref.WeakReference;

/**
 * API 102 module entry. One Java entry class only (hot-reload requirement).
 *
 * <p>Package work starts from {@code onPackageReady} for {@code com.spotify.music}.
 * Non-matching packages are ignored without {@code detach()}: detaching would also
 * drop a later Spotify package callback in the same process.
 */
public final class SpicyEXModule extends XposedModule {
    private static final String TARGET_PACKAGE = "com.spotify.music";
    private static final String TAG = "[SpotifyPlus]";

    static {
        try {
            System.loadLibrary("dexkit");
        } catch (UnsatisfiedLinkError e) {
            XpLog.log(TAG + " dexkit native load failed: " + e);
        }
    }

    private static volatile boolean injectionToastShown = false;
    private volatile String processName = "";

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        processName = param.getProcessName();
        XpHooks.attach(this);
        XpLog.attach(this);
        try {
            XpRes.init(getModuleApplicationInfo());
            // Legacy handleInitPackageResources populated this; without it the entry
            // icon, fonts, and localized strings resolve to null.
            References.modResources = XpRes.moduleResources(null);
        } catch (Throwable throwable) {
            XpLog.log(TAG + " module resources init failed: " + throwable);
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) return;
        Diagnostics.markHookRuntimeActive();
        XpLog.log(TAG + " Loading SpotifyPlus");

        ClassLoader classLoader = param.getClassLoader();
        DexKitBridge bridge = null;
        try {
            bridge = DexKitBridge.create(param.getApplicationInfo().sourceDir);
        } catch (Throwable throwable) {
            XpLog.log(TAG + " dexkit bridge failed: " + throwable);
        }
        final DexKitBridge dexKit = bridge;
        final XpPackage xpPackage = new XpPackage(param.getPackageName(), classLoader);

        XpHooks.findAfter(Activity.class, "onResume", "entry:Activity#onResume", p -> {
            Activity activity = (Activity) p.thisObject;
            References.setCurrentActivity(activity);
            if (!injectionToastShown) {
                injectionToastShown = true;
                try {
                    SpotifyPlusConfig config = SpotifyPlusConfig.from(activity);
                    String mode = config.lyricsDisplayMode();
                    XpLog.log(TAG + " Injection confirmed activity=" + activity.getClass().getName()
                            + " displayMode=" + mode);
                } catch (Throwable throwable) {
                    XpLog.log(throwable);
                }
            }
        });

        XpHooks.findBefore(Activity.class, "onDestroy", "entry:Activity#onDestroy",
                p -> References.clearCurrentActivity((Activity) p.thisObject));

        XpHooks.findAfter(Activity.class, "onCreate", "entry:Activity#onCreate",
                p -> {
                    Activity activity = (Activity) p.thisObject;
                    Typeface beautifulFont = References.beautifulFont.get();
                    if (beautifulFont != null) return;
                    try {
                        android.content.res.Resources resources = XpRes.moduleResources(activity);
                        if (resources == null) return;
                        beautifulFont = Typeface.createFromAsset(
                                resources.getAssets(), "fonts/spotifymix-medium.ttf");
                        XpLog.log(TAG + " Successfully loaded font!");
                    } catch (Throwable throwable) {
                        XpLog.log(TAG + " Failed to load font (error)", throwable);
                    }
                    if (beautifulFont != null) {
                        References.beautifulFont = new WeakReference<>(beautifulFont);
                    }
                }, Bundle.class);

        XpHooks.findAfter(Application.class, "attach", "entry:Application#attach",
                p -> {
                    Context context = (Context) p.args[0];
                    Diagnostics.initialize(context);
                    Diagnostics.event("bootstrap", "application_attach",
                            Diagnostics.context("process", Application.getProcessName()));
                    cleanUpCache(context);
                    new NativeSpicyLyricsHook(context).init(xpPackage, dexKit);
                    Diagnostics.markHookBootstrapComplete();
                    Diagnostics.event("bootstrap", "hook_init_complete");
                }, Context.class);
    }

    private void cleanUpCache(Context context) {
        try {
            File[] files = context.getCacheDir().listFiles();
            if (files == null) return;
            for (File file : files) {
                if (file.getName().endsWith(".apk")) file.delete();
            }
        } catch (Throwable throwable) {
            XpLog.log(TAG + " cache cleanup failed: " + throwable);
        }
    }

    @Override
    public boolean onHotReloading(@NonNull XposedModuleInterface.HotReloadingParam param) {
        // Manual reload stays unverified; reject until the three-pass gate proves cleanup.
        // Per-feature reload contracts that must hold before this can return true:
        // - Sound/Meaning lane executors (LyricsSecondaryProcessingSession): stop dispatch,
        //   reject while a run is in flight; runs own no cross-generation state.
        // - DexKitBridge plus the dexkit native library: release bridge, or prove the new
        //   generation can share the already-loaded library without leaking the old loader.
        // - SpicyLyricBridgePublisher service connection: unbind before retire.
        // - Decor-posted retries (LyricsActivityTakeoverHook extra-button injection,
        //   NowPlayingInjector card/alignment retries): cancel, or prove their captured
        //   Activity cannot outlive the retiring generation.
        // - Must-reset statics (never transferred): LyricsActivityTakeoverHook takeoverArmed
        //   and nativeLyricsSessionActive, References player/track/ Typeface mirrors and
        //   cached Methods, SpotifyTokenStore state, XpHooks generation registry
        //   (see XpHooks.unhookAll), XpRes cached Resources.
        // - onHotReloaded must reinstall every hook by stable id because package
        //   callbacks are not replayed.
        return false;
    }

    String targetProcessName() {
        return processName;
    }
}
