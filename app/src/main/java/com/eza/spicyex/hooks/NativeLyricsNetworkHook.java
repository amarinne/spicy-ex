package com.eza.spicyex.hooks;

import com.eza.spicyex.lyrics.NativeLyricsSource;
import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;
import com.eza.spicyex.xposed.XpReflect;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Captures Spotify's own color-lyrics response as it leaves the HTTP stack.
 *
 * <p>Neither the offline table nor the in-memory model carry the payload on current Spotify
 * builds: {@code lyrics_db} stays empty because lyrics are served live. The one place the
 * document always exists is the response body. Pairing is done by identity — the
 * interceptor chain hands back the response whose {@code body()} is the very object the
 * caller later reads, so the request URL and the body string meet on the same instance.
 *
 * <p>The body is never consumed here: the hook runs after OkHttp has already produced the
 * string, so Spotify reads its own copy exactly as before.
 */
final class NativeLyricsNetworkHook {
    private static final String TAG = "[SpotifyPlusNativeLyricsNet]";

    /** Response body instance to the track whose color-lyrics request produced it. */
    private static final Map<Object, String> PENDING =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private NativeLyricsNetworkHook() {
    }

    static void install(ClassLoader classLoader, NativeLyricsSource source,
                        NativeLyricsCaptureHook.TrackProvider trackProvider) {
        if (source == null) {
            XpLog.log(TAG + " install skipped: no native lyrics source");
            return;
        }
        if (classLoader == null) {
            XpLog.log(TAG + " install skipped: no Spotify class loader");
            return;
        }
        bind(source);
        hint = trackProvider;
        try {
            hookRequestPairing(classLoader, source);
            hookResponseBody(classLoader, source);
        } catch (Throwable t) {
            XpLog.log(TAG + " install failed: " + t);
        }
    }

    /**
     * Pairs each in-flight color-lyrics request with the response body it produced. Any
     * interceptor layer works; OkHttp's own chain is the reliable one.
     */
    private static void hookRequestPairing(ClassLoader classLoader, NativeLyricsSource source) {
        int hooked = 0;
        for (String className : new String[]{
                "okhttp3.internal.http.RealInterceptorChain",
                "okhttp3.internal.connection.RealInterceptorChain",
                "okhttp3.RealCall",
                "okhttp3.internal.connection.RealCall",
                "com.spotify.okhttp.OkHttpInterceptorChain",
                "com.spotify.okhttp.RealInterceptorChain"}) {
            try {
                Class<?> chain = XpReflect.findClassIfExists(className, classLoader);
                if (chain == null) continue;
                for (Method method : chain.getDeclaredMethods()) {
                    String name = method.getName();
                    if (!"proceed".equals(name) && !"intercept".equals(name)) continue;
                    if (method.getReturnType() == Void.TYPE) continue;
                    method.setAccessible(true);
                    final String tag = className + "#" + name;
                    XpHooks.hookAfter(method, "lyrics:net:" + tag, param -> {
                        pairRequestWithBody(param.args, param.getResult(), source);
                    });
                    hooked++;
                }
            } catch (Throwable ignored) {
            }
        }
        XpLog.log(NativeSpicyLyricsHook.TAG + " native lyrics response pairing hooks=" + hooked);
    }

    private static void pairRequestWithBody(Object[] args, Object response,
                                            NativeLyricsSource source) {
        try {
            if (response == null || args == null || args.length == 0) return;
            String url = readUrl(args[0]);
            if (url == null || !isLyricsUrl(url)) return;
            String trackId = trackIdFromLyricsUrl(url);
            if (trackId.isEmpty()) return;
            Object body = invokeNoArg(response, "body");
            if (body != null) PENDING.put(body, trackId);
        } catch (Throwable ignored) {
        }
    }

    /** Reads the body Spotify is about to parse, then clears the pairing entry. */
    private static void hookResponseBody(ClassLoader classLoader, NativeLyricsSource source) {
        try {
            Class<?> body = XpReflect.findClassIfExists("okhttp3.ResponseBody", classLoader);
            if (body == null) {
                XpLog.log(NativeSpicyLyricsHook.TAG + " native lyrics response hook: no ResponseBody");
                return;
            }
            XpHooks.hookAllMethods(body, "string", "lyrics:net:ResponseBody#string",
                    (XpHooks.After) param -> capture(param.thisObject, param.getResult()));
            XpHooks.hookAllMethods(body, "bytes", "lyrics:net:ResponseBody#bytes",
                    (XpHooks.After) param -> {
                        Object result = param.getResult();
                        if (result instanceof byte[]) {
                            capture(param.thisObject,
                                    new String((byte[]) result, java.nio.charset.StandardCharsets.UTF_8));
                        }
                    });
            XpLog.log(NativeSpicyLyricsHook.TAG + " native lyrics response body hooks installed");
        } catch (Throwable t) {
            XpLog.log(TAG + " response body hook failed: " + t);
        }
    }

    private static void capture(Object body, Object payload) {
        try {
            if (body == null || !(payload instanceof String)) return;
            String paired = body == null ? null : PENDING.remove(body);
            String text = ((String) payload).trim();
            if (text.length() < 32) return;
            String trackId = !isBlank(paired) ? paired : embeddedTrackId(text);
            if (isBlank(trackId)) trackId = currentTrackId();
            if (isBlank(trackId)) return;
            if (source() != null) source().captureColorLyricsResponse(trackId, text);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** Track identity carried by the payload itself, when the response includes one. */
    private static String embeddedTrackId(String json) {
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("spotify:track:([A-Za-z0-9]{22})").matcher(json);
            if (matcher.find()) return matcher.group(1);
            return "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static NativeLyricsSource source;
    private static NativeLyricsCaptureHook.TrackProvider hint;

    static void bind(NativeLyricsSource value) {
        source = value;
    }

    private static NativeLyricsSource source() {
        return source;
    }

    /** Current-track fallback for responses that carry no track identity of their own. */
    private static String currentTrackId() {
        try {
            if (hint == null) return "";
            com.eza.spicyex.SpotifyTrack track = hint.getCurrentTrack();
            return track == null ? "" : com.eza.spicyex.lyrics.LyricUtils.trackIdFromUri(track.uri);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isLyricsUrl(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("color-lyrics") || lower.contains("lyrics/v3")
                || lower.contains("lyrics/v2");
    }

    /** The track id is the last path segment of the color-lyrics URL. */
    private static String trackIdFromLyricsUrl(String url) {
        try {
            String path = url;
            int query = path.indexOf('?');
            if (query > 0) path = path.substring(0, query);
            int slash = path.lastIndexOf('/');
            String last = slash >= 0 ? path.substring(slash + 1) : path;
            if (last.matches("[A-Za-z0-9]{22}")) return last;
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("([A-Za-z0-9]{22})").matcher(path);
            return matcher.find() ? matcher.group(1) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String readUrl(Object request) {
        try {
            if (request == null) return null;
            Object url = invokeNoArg(request, "url");
            if (url == null) {
                url = request instanceof String ? request : null;
            }
            if (url == null) return null;
            Object text = invokeNoArg(url, "toString");
            if (text instanceof String) return (String) text;
            return String.valueOf(url);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
