package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.safe;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.NativeLyricsSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;

/** Installs Spotify native lyrics model capture hooks and forwards candidates to NativeLyricsSource. */
final class NativeLyricsCaptureHook {
    interface TrackProvider {
        SpotifyTrack getCurrentTrack();
    }

    private static final String[] NATIVE_CLASS_NAMES = {
            "com.spotify.lyrics.offlineimpl.database.LyricsDatabaseEntity",
            "com.spotify.lyrics.offlineimpl.database.LyricsDatabaseEntity$Line",
            "com.spotify.lyrics.offlineimpl.database.LyricsDatabaseEntity$Syllable",
            "com.spotify.lyrics.offlineimpl.database.LyricsDatabaseEntity$Provider",
            "com.spotify.lyrics.data.model.Lyrics",      // <= ~9.1.28
            "com.spotify.lyrics.data.model.ColorLyrics"  // renamed in newer Spotify (>= 9.1.56)
    };
    private static final String[][] DEXKIT_PROBES = {
            {"lyrics_entities("},
            {"SELECT * FROM lyrics_entities WHERE track_id = ?"},
            {"INSERT OR REPLACE INTO `lyrics_entities`"},
            {"syncStatus", "vocalRemovalStatus"},
            {"GeneratedJsonAdapter(LyricsDatabaseEntity.Line)"},
            {"GeneratedJsonAdapter(LyricsDatabaseEntity.Syllable)"},
            {"lyricsLines_"},
            {"LyricsLineTag"},
            {"color-lyrics/v3/track/{trackId}"},
            {"color-lyrics/v2/track/{trackId}"}
    };

    private final LinkedHashSet<String> hookedClassNames = new LinkedHashSet<>();
    private final ClassLoader classLoader;
    private final DexKitBridge bridge;
    private final NativeLyricsSource nativeLyricsSource;
    private final TrackProvider trackProvider;

    NativeLyricsCaptureHook(
            ClassLoader classLoader,
            DexKitBridge bridge,
            NativeLyricsSource nativeLyricsSource,
            TrackProvider trackProvider
    ) {
        this.classLoader = classLoader;
        this.bridge = bridge;
        this.nativeLyricsSource = nativeLyricsSource;
        this.trackProvider = trackProvider;
    }

    void hook() {
        NativeSpicyLyricsHook.dbgEnter("hookNativeLyricsCapture");
        for (String name : NATIVE_CLASS_NAMES) {
            try {
                Class<?> cls = XposedHelpers.findClass(name, classLoader);
                hookResolvedNativeLyricsClass(cls, name);
            } catch (Throwable t) {
                XposedBridge.log(NativeSpicyLyricsHook.TAG
                        + " native lyrics capture missing " + name + ": " + t.getClass().getSimpleName());
            }
        }
        hookDeferredNativeLyricsClassLoading();
        discoverNativeLyricsClasses();
    }

    private void hookDeferredNativeLyricsClassLoading() {
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.args != null && param.args.length > 0
                                    && param.args[0] instanceof String)) return;
                            String name = (String) param.args[0];
                            if (!isNativeLyricsClassName(name)) return;
                            Object result = param.getResult();
                            if (!(result instanceof Class)) return;
                            hookResolvedNativeLyricsClass((Class<?>) result, "deferred:" + name);
                        }
                    });
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " native lyrics deferred ClassLoader hook installed");
        } catch (Throwable t) {
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " native lyrics deferred hook failed: " + t);
        }
    }

    private void discoverNativeLyricsClasses() {
        if (bridge == null) return;
        for (String[] probe : DEXKIT_PROBES) {
            try {
                var found = bridge.findClass(FindClass.create().matcher(ClassMatcher.create().usingStrings(probe)));
                XposedBridge.log(NativeSpicyLyricsHook.TAG
                        + " native lyrics DexKit probe strings=" + String.join(",", probe)
                        + " matches=" + found.size());
                int count = 0;
                for (org.luckypray.dexkit.result.ClassData data : found) {
                    if (count++ >= 8) break;
                    String name = data.getName();
                    XposedBridge.log(NativeSpicyLyricsHook.TAG + " native lyrics DexKit candidate " + name);
                    try {
                        hookResolvedNativeLyricsClass(data.getInstance(classLoader),
                                "dexkit:" + String.join(",", probe));
                    } catch (Throwable t) {
                        XposedBridge.log(NativeSpicyLyricsHook.TAG
                                + " native lyrics DexKit candidate load failed " + name
                                + ": " + t.getClass().getSimpleName());
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(NativeSpicyLyricsHook.TAG
                        + " native lyrics DexKit probe failed strings=" + String.join(",", probe) + ": " + t);
            }
        }
    }

    private void hookResolvedNativeLyricsClass(Class<?> cls, String sourceTag) {
        if (cls == null) return;
        String className = cls.getName();
        synchronized (hookedClassNames) {
            if (hookedClassNames.contains(className)) return;
            if (hookedClassNames.size() > 40) return;
            hookedClassNames.add(className);
        }
        try {
            XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    captureNativeLyricsCandidate(param.thisObject, param.args, sourceTag + ":ctor:" + className);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(NativeSpicyLyricsHook.TAG
                    + " native lyrics constructor hook failed " + className + ": " + t.getClass().getSimpleName());
        }
        int methodHooks = 0;
        for (Method method : cls.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers)) continue;
            if (method.getReturnType() == Void.TYPE) continue;
            if (methodHooks >= 18) break;
            try {
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object result = param.getResult();
                        if (result != null) {
                            captureNativeLyricsCandidate(
                                    result,
                                    param.args,
                                    sourceTag + ":method:" + className + "#" + method.getName()
                            );
                        }
                    }
                });
                methodHooks++;
            } catch (Throwable ignored) {
            }
        }
        XposedBridge.log(NativeSpicyLyricsHook.TAG
                + " native lyrics capture hook installed " + className
                + " methods=" + methodHooks
                + " source=" + sourceTag);
    }

    private static boolean isNativeLyricsClassName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("spotify.lyrics")
                || lower.contains("lyricsdatabaseentity")
                || lower.contains("lyricsresponse")
                || lower.contains("lyricsv3response")
                || lower.contains("colorlyricsresponse");
    }

    private void captureNativeLyricsCandidate(Object candidate, Object[] ctorArgs, String sourceTag) {
        NativeSpicyLyricsHook.dbg("captureNativeLyricsCandidate",
                "source=" + safe(sourceTag)
                        + " class=" + (candidate == null ? "null" : candidate.getClass().getName())
                        + " args=" + (ctorArgs == null ? 0 : ctorArgs.length));
        nativeLyricsSource.captureCandidate(trackProvider.getCurrentTrack(), candidate, ctorArgs, sourceTag);
    }
}
