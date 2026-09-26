package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.safe;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.NativeLyricsSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Locale;

import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;
import com.eza.spicyex.xposed.XpReflect;
import com.eza.spicyex.xposed.SpotifySymbolResolver;
import java.util.ArrayList;
import java.util.List;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

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
            "com.spotify.lyrics.data.model.ColorLyrics", // renamed in newer Spotify (>= 9.1.56)
            // The parsed service response. The endpoint takes `Accept: application/protobuf`, so
            // on current builds the lines only exist in this protobuf message: the offline table
            // stays empty and the model no longer carries them. These names survive obfuscation
            // because the generated protobuf code and the Retrofit interface refer to them by
            // name, which makes them the one durable handle on Spotify's own lyrics.
            "com.spotify.lyrics.serviceretrofit.proto.v3.LyricsWrapperResponse",
            "com.spotify.lyrics.serviceretrofit.proto.v3.LyricsV3Response",
            "com.spotify.lyrics.serviceretrofit.proto.ColorLyricsResponse",
            "com.spotify.lyrics.serviceretrofit.proto.LyricsResponse"
    };
    /**
     * The color-lyrics endpoint is only an annotation value on the Retrofit interface, so a
     * string probe cannot reach it and those probes resolved nothing on every build so far.
     * The parsed protobuf response is hooked by name instead, which is where the lines are.
     */

    private static final String[][] DEXKIT_PROBES = {
            {"lyrics_entities("},
            {"SELECT * FROM lyrics_entities WHERE track_id = ?"},
            {"INSERT OR REPLACE INTO `lyrics_entities`"},
            {"syncStatus", "vocalRemovalStatus"},
            {"GeneratedJsonAdapter(LyricsDatabaseEntity.Line)"},
            {"GeneratedJsonAdapter(LyricsDatabaseEntity.Syllable)"},
            {"lyricsLines_"},
            {"LyricsLineTag"}
    };
    /**
     * Method-level trace probes for the native lyrics load path: the loader that fetches
     * color-lyrics for a track and the DAO that reads lyrics_entities. Each probe runs its
     * DexKit trace once and the resolved method is cached by symbol record, mirroring the
     * playback wrapper getState discovery. Hooking the load call itself (rather than only
     * model constructors) is what keeps the Spotify row fed on Spotify builds where the
     * model class names moved.
     */
    private static final String[][] NATIVE_LOAD_TRACES = {
            {"SELECT * FROM lyrics_entities WHERE track_id = ?"},
            {"syncStatus", "vocalRemovalStatus"},
    };

    private final LinkedHashSet<String> hookedClassNames = new LinkedHashSet<>();
    private final java.util.Map<String, Integer> seenCounts = new java.util.HashMap<>();
    private final ClassLoader classLoader;
    private final SpotifySymbolResolver symbols;
    private final NativeLyricsSource nativeLyricsSource;
    private final TrackProvider trackProvider;

    NativeLyricsCaptureHook(
            ClassLoader classLoader,
            SpotifySymbolResolver symbols,
            NativeLyricsSource nativeLyricsSource,
            TrackProvider trackProvider
    ) {
        this.classLoader = classLoader;
        this.symbols = symbols;
        this.nativeLyricsSource = nativeLyricsSource;
        this.trackProvider = trackProvider;
    }

    void hook() {
        NativeSpicyLyricsHook.dbgEnter("hookNativeLyricsCapture");
        for (String name : NATIVE_CLASS_NAMES) {
            try {
                Class<?> cls = XpReflect.findClass(name, classLoader);
                hookResolvedNativeLyricsClass(cls, name);
            } catch (Throwable t) {
                XpLog.log(NativeSpicyLyricsHook.TAG
                        + " native lyrics capture missing " + name + ": " + t.getClass().getSimpleName());
            }
        }
        hookDeferredNativeLyricsClassLoading();
        discoverNativeLyricsClasses();
        traceNativeLyricsLoad();
        // Spotify's own lyrics arrive as a protobuf message on current builds: the offline
        // table stays empty, the response body never reaches a named HTTP client, and the
        // endpoint string is annotation-only. The response classes are hooked by name above.
        NativeLyricsNetworkHook.install(classLoader, nativeLyricsSource, trackProvider);
    }

    private void hookDeferredNativeLyricsClassLoading() {
        try {
            XpHooks.findAfter(ClassLoader.class, "loadClass",
                    "lyrics:ClassLoader#loadClass", param -> {
                        if (!(param.args != null && param.args.length > 0
                                && param.args[0] instanceof String)) return;
                        String name = (String) param.args[0];
                        if (!isNativeLyricsClassName(name)) return;
                        Object result = param.getResult();
                        if (!(result instanceof Class)) return;
                        hookResolvedNativeLyricsClass((Class<?>) result, "deferred:" + name);
                    }, String.class, boolean.class);
            XpLog.log(NativeSpicyLyricsHook.TAG + " native lyrics deferred ClassLoader hook installed");
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " native lyrics deferred hook failed: " + t);
        }
    }

    private void discoverNativeLyricsClasses() {
        for (String[] probe : DEXKIT_PROBES) {
            try {
                List<Class<?>> found = symbols.cache.classes("lyrics." + String.join("|", probe), () -> {
                    var matches = symbols.dexKit().findClass(
                            FindClass.create().matcher(ClassMatcher.create().usingStrings(probe)));
                    List<String> classes = new ArrayList<>();
                    for (var data : matches) {
                        if (classes.size() >= 8) break;
                        classes.add(data.getName());
                    }
                    return classes;
                });
                for (Class<?> cls : found) {
                    hookResolvedNativeLyricsClass(cls, "resolved:" + String.join(",", probe));
                }
            } catch (Throwable t) {
                XpLog.log(NativeSpicyLyricsHook.TAG
                        + " native lyrics DexKit probe failed strings=" + String.join(",", probe) + ": " + t);
            }
        }
    }

    /**
     * One-time DexKit trace of the native lyrics load path. Each probe resolves its loader
     * method once and persists it as a symbol record; warm startups reuse the record without
     * loading DexKit. The traced method's return value is captured as a native candidate, so
     * the Spotify row reflects whatever Spotify itself loaded for the track.
     */
    private void traceNativeLyricsLoad() {
        for (String[] probe : NATIVE_LOAD_TRACES) {
            try {
                java.lang.reflect.Method traced = symbols.cache.method(
                        "lyrics.nativeLoad." + String.join("|", probe), () -> {
                            var matches = symbols.dexKit().findMethod(
                                    FindMethod.create().matcher(
                                            MethodMatcher.create().usingStrings(probe)));
                            for (var data : matches) {
                                try {
                                    java.lang.reflect.Method candidate =
                                            data.getMethodInstance(classLoader);
                                    if (candidate == null) continue;
                                    int modifiers = candidate.getModifiers();
                                    if (Modifier.isAbstract(modifiers)
                                            || Modifier.isNative(modifiers)) continue;
                                    return candidate;
                                } catch (Throwable ignored) {
                                }
                            }
                            throw new NoSuchMethodException(
                                    "lyrics native load " + String.join(",", probe));
                        });
                final String tag = "traced:" + traced.getDeclaringClass().getName()
                        + "#" + traced.getName();
                XpHooks.hookAfter(traced, "lyrics:" + tag, param -> {
                    Object result = param.getResult();
                    if (result != null) {
                        captureNativeLyricsCandidate(result, param.args, tag);
                    }
                });
                XpLog.log(NativeSpicyLyricsHook.TAG
                        + " native lyrics load trace installed "
                        + traced.getDeclaringClass().getName() + "#" + traced.getName());
                // The response parser lives next to the request builder, so hook the whole
                // declaring class as well rather than only the one traced method.
                hookResolvedNativeLyricsClass(traced.getDeclaringClass(),
                        "colorEndpointNeighbour:" + String.join(",", probe));
            } catch (Throwable t) {
                XpLog.log(NativeSpicyLyricsHook.TAG
                        + " native lyrics load trace failed strings=" + String.join(",", probe)
                        + ": " + t);
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
            XpHooks.hookAllConstructors(cls, "lyrics:" + className + "#ctor", (XpHooks.After) param -> {
                captureNativeLyricsCandidate(param.thisObject, param.args, sourceTag + ":ctor:" + className);
            });
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG
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
                XpHooks.hookAfter(method, "lyrics:" + className + "#" + method.getName(), param -> {
                    Object result = param.getResult();
                    // Re-read the list's owner, including metadata, on later sheet visits.
                    if (result instanceof java.util.Collection) result = param.thisObject;
                    if (result != null) {
                        captureNativeLyricsCandidate(
                                result,
                                param.args,
                                sourceTag + ":method:" + className + "#" + method.getName()
                        );
                    }
                });
                methodHooks++;
            } catch (Throwable ignored) {
            }
        }
        XpLog.log(NativeSpicyLyricsHook.TAG
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
        if (candidate instanceof java.util.Collection) return;
        // Throttled invocation trace: proves whether the hooked Spotify lyrics path fires at
        // all on the installed Spotify build, independent of whether parsing succeeds.
        // First five sightings per hook source; steady state stays quiet.
        try {
            synchronized (seenCounts) {
                int seen = seenCounts.containsKey(sourceTag) ? seenCounts.get(sourceTag) : 0;
                if (seen < 5) {
                    seenCounts.put(sourceTag, seen + 1);
                    XpLog.log(NativeSpicyLyricsHook.TAG + " native lyrics hook fired source="
                            + safe(sourceTag) + " class="
                            + (candidate == null ? "null" : candidate.getClass().getName())
                            + " args=" + (ctorArgs == null ? 0 : ctorArgs.length));
                }
            }
        } catch (Throwable ignored) {
        }
        nativeLyricsSource.captureCandidate(trackProvider.getCurrentTrack(), candidate, ctorArgs, sourceTag);
    }
}
