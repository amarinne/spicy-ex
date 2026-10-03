package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.safe;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.providers.NativeLyricsSource;
import com.eza.spicyex.lyrics.providers.LyricsRepository;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.luckypray.dexkit.query.matchers.AnnotationElementMatcher;
import org.luckypray.dexkit.query.matchers.AnnotationMatcher;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.query.matchers.base.AnnotationEncodeValueMatcher;

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
    private volatile Object spotifyLyricsService;

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
        List<String> missing = new ArrayList<>();
        for (String name : NATIVE_CLASS_NAMES) {
            try {
                Class<?> cls = XpReflect.findClass(name, classLoader);
                hookResolvedNativeLyricsClass(cls, name);
            } catch (Throwable t) {
                missing.add(name);
                XpLog.log(NativeSpicyLyricsHook.TAG
                        + " native lyrics capture missing " + name + ": " + t.getClass().getSimpleName());
            }
        }
        if (missingClassesShipInApk(missing)) hookDeferredNativeLyricsClassLoading();
        discoverNativeLyricsClasses();
        traceNativeLyricsLoad();
        // Spotify's own lyrics arrive as a protobuf message on current builds: the offline
        // table stays empty, the response body never reaches a named HTTP client, and the
        // endpoint string is annotation-only. The response classes are hooked by name above.
        NativeLyricsNetworkHook.install(classLoader, nativeLyricsSource, trackProvider);
        installExplicitSpotifyRequest();
    }

    /** The Retrofit path of Spotify's own lyrics request: an annotation value, so it survives
     *  obfuscation where the interface, its method and the owning service are renamed with
     *  every release (hard-coded 9.1.84 names stopped resolving on 9.1.88). */
    private static final String COLOR_LYRICS_PATH = "color-lyrics/v2/track/{trackId}";

    /** Uses Spotify's own authenticated Retrofit client when its verified service is present. */
    private void installExplicitSpotifyRequest() {
        try {
            Class<?> single = XpReflect.findClass("io.reactivex.rxjava3.core.Single", classLoader);
            Method endpoint = symbols.cache.method("lyrics.colorLyricsEndpoint", () ->
                    symbols.dexKit().findMethod(FindMethod.create().matcher(MethodMatcher.create()
                                    .paramTypes(String.class, boolean.class, String.class, boolean.class)
                                    .addAnnotation(AnnotationMatcher.create().addElement(
                                            AnnotationElementMatcher.create().name("value").value(
                                                    AnnotationEncodeValueMatcher.createString(
                                                            COLOR_LYRICS_PATH))))))
                            .get(0).getMethodInstance(classLoader));
            if (!single.isAssignableFrom(endpoint.getReturnType())) return;
            Class<?> retrofit = endpoint.getDeclaringClass();
            List<Class<?>> owners = symbols.cache.classes("lyrics.colorLyricsService", () -> {
                List<String> names = new ArrayList<>();
                for (var data : symbols.dexKit().findClass(FindClass.create().matcher(
                        ClassMatcher.create().addFieldForType(retrofit.getName())))) {
                    names.add(data.getName());
                }
                return names;
            });
            Class<?> service = null;
            Field client = null;
            for (Class<?> owner : owners) {
                for (Field f : owner.getDeclaredFields()) {
                    if (f.getType() == retrofit && !Modifier.isStatic(f.getModifiers())) {
                        service = owner;
                        client = f;
                        break;
                    }
                }
                if (service != null) break;
            }
            if (service == null) throw new NoSuchFieldException("color-lyrics service");
            client.setAccessible(true);
            final Class<?> serviceClass = service;
            final Field clientField = client;
            XpHooks.hookAllConstructors(service, "lyrics:explicitSpotifyClient",
                    (XpHooks.After) param -> {
                        spotifyLyricsService = param.thisObject;
                        XpLog.log(NativeSpicyLyricsHook.TAG
                                + " explicit Spotify client captured");
                    });
            nativeLyricsSource.setRequester((track, callback) ->
                    requestSpotifyTrack(track, callback, serviceClass, clientField, endpoint));
            XpLog.log(NativeSpicyLyricsHook.TAG + " explicit Spotify request installed "
                    + service.getName() + " -> " + retrofit.getName() + "#" + endpoint.getName());
        } catch (Throwable error) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " explicit Spotify request unavailable: "
                    + error.getClass().getSimpleName());
        }
    }

    private void requestSpotifyTrack(SpotifyTrack track,
            LyricsRepository.NativeLyricsProvider.RequestCallback callback,
            Class<?> service, Field clientField, Method endpoint) {
        Object owner = spotifyLyricsService;
        if (owner == null || track == null || track.uri == null) {
            if (owner == null) XpLog.log(NativeSpicyLyricsHook.TAG
                    + " explicit Spotify client not created");
            callback.onResult(null, "Spotify lyrics request unavailable");
            return;
        }
        String id = com.eza.spicyex.lyrics.LyricUtils.trackIdFromUri(track.uri);
        if (id.isEmpty()) {
            callback.onResult(null, "Unsupported Spotify track");
            return;
        }
        AtomicBoolean done = new AtomicBoolean();
        Handler main = new Handler(Looper.getMainLooper());
        final Object[] disposable = {null};
        Runnable timeout = () -> {
            if (!done.compareAndSet(false, true)) return;
            dispose(disposable[0]);
            callback.onResult(null, "Spotify lyrics request timed out");
        };
        try {
            Object client = clientField.get(owner);
            // clientLanguage: the language Spotify's own getter returned was the app's locale.
            String language = Locale.getDefault().getLanguage();
            Object request = endpoint.invoke(client, id, false,
                    language == null ? "" : language, false);
            Class<?> consumer = XpReflect.findClass(
                    "io.reactivex.rxjava3.functions.Consumer", classLoader);
            Object success = Proxy.newProxyInstance(classLoader, new Class<?>[]{consumer},
                    (proxy, method, args) -> {
                        if ("accept".equals(method.getName())
                                && done.compareAndSet(false, true)) {
                            main.removeCallbacks(timeout);
                            Object response = args == null || args.length == 0 ? null : args[0];
                            nativeLyricsSource.captureCandidate(track, response,
                                    new Object[]{track.uri}, "explicit:spotify-retrofit");
                            com.eza.spicyex.lyrics.LyricsDocument doc =
                                    nativeLyricsSource.getNativeLyricsDocument(track);
                            callback.onResult(doc, doc == null
                                    ? "Spotify lyrics response unavailable" : "");
                        }
                        return null;
                    });
            Object failure = Proxy.newProxyInstance(classLoader, new Class<?>[]{consumer},
                    (proxy, method, args) -> {
                        if ("accept".equals(method.getName())
                                && done.compareAndSet(false, true)) {
                            main.removeCallbacks(timeout);
                            Object error = args == null || args.length == 0 ? null : args[0];
                            callback.onResult(null, spotifyRequestError(error));
                        }
                        return null;
                    });
            main.postDelayed(timeout, 10000L);
            disposable[0] = request.getClass().getMethod("subscribe", consumer, consumer)
                    .invoke(request, success, failure);
            if (done.get()) dispose(disposable[0]);
        } catch (Throwable error) {
            main.removeCallbacks(timeout);
            if (done.compareAndSet(false, true)) {
                callback.onResult(null, "Spotify lyrics request failed");
            }
            XpLog.log(NativeSpicyLyricsHook.TAG + " explicit Spotify request failed: "
                    + error.getClass().getSimpleName());
        }
    }

    private static String spotifyRequestError(Object error) {
        try {
            int code = (Integer) error.getClass().getMethod("code").invoke(error);
            if (code == 404) return "Spotify has no lyrics for this track";
        } catch (Throwable ignored) {
        }
        return "Spotify lyrics request failed";
    }

    private static void dispose(Object disposable) {
        if (disposable == null) return;
        try {
            disposable.getClass().getMethod("dispose").invoke(disposable);
        } catch (Throwable ignored) {
        }
    }

    /**
     * The deferred hook sits on ClassLoader#loadClass, so every class Spotify loads - thousands
     * at startup, more on each new screen - pays an Xposed callback for it. It can only ever
     * catch a class that is in the APK but was not loadable yet; a name that is not in the APK at
     * all (the offline-database entities and the old models are gone from current builds) never
     * arrives, so the hook is skipped then.
     */
    private boolean missingClassesShipInApk(List<String> missing) {
        if (missing.isEmpty()) return false;
        try {
            List<Class<?>> shipped = symbols.cache.classes("lyrics.deferredNames", () -> {
                List<String> found = new ArrayList<>();
                for (String name : missing) {
                    if (!symbols.dexKit().findClass(FindClass.create().matcher(
                            ClassMatcher.create().className(name))).isEmpty()) found.add(name);
                }
                return found;
            });
            if (shipped.isEmpty()) {
                XpLog.log(NativeSpicyLyricsHook.TAG
                        + " native lyrics deferred ClassLoader hook skipped: none of the missing classes ship");
                return false;
            }
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " native lyrics deferred check failed: " + t);
        }
        return true;
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
            if (!canReturnLyrics(method.getReturnType())) continue;
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

    /**
     * Whether a method's result could be (or own) Spotify's lyrics. The string probes resolve
     * obfuscated classes that R8 has merged with unrelated code - on 9.1.88 the
     * "INSERT OR REPLACE INTO lyrics_entities" class has accessors returning String, Boolean and
     * the main activity - and each hooked accessor paid a callback and a parse attempt on every
     * call. Plain values and framework objects never carry lyrics, so they are not hooked;
     * collections still are (their owner is re-read).
     */
    private static boolean canReturnLyrics(Class<?> type) {
        if (type.isPrimitive() || type.isArray()) return false;
        if (java.util.Collection.class.isAssignableFrom(type)) return true;
        String name = type.getName();
        if (type == Object.class) return true;
        return !(name.startsWith("java.") || name.startsWith("android.")
                || name.startsWith("androidx.") || name.startsWith("kotlin."));
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
