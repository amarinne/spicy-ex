package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.isBlank;
import static com.eza.spicyex.hooks.NativeLyricsUtils.safe;

import android.content.Context;
import android.net.Uri;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;
import com.eza.spicyex.xposed.XpReflect;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;

/** Captures Spotify access tokens from OkHttp headers and Spotify auth response objects. */
final class AuthTokenCaptureHook {
    private static final int AUTH_REQUEST_DEBUG_LIMIT = 20;
    private static final int AUTH_HEADER_DEBUG_LIMIT = 24;
    /** Upper bound for a plausible observed expires-in value (seconds); anything beyond is ignored. */
    private static final long MAX_PLAUSIBLE_EXPIRES_IN_SECONDS = 30L * 24L * 3600L;

    // Spotify's auth-token classes (kept names; fields are proto-style). The access token lives in
    // EsAccessToken$AccessToken.token_ (obfuscated getter), which the OkHttp-header capture misses.
    private static final String[] SPOTIFY_AUTH_TOKEN_CLASSES = {
            "com.spotify.authentication.login5esperanto.EsAccessToken$AccessToken",
            "com.spotify.authentication.login5esperanto.EsAccessTokenClient$AccessTokenResponse",
            "com.spotify.authentication.login5esperanto.EsAuthenticateResult$AuthenticateResult",
            "com.spotify.authentication.login5esperanto.EsAuthenticateResult$AuthenticateSuccess",
            "com.spotify.authentication.oauth.AccessToken",
            "com.spotify.authentication.tokenexchangeimpl.model.TokenResponse",
            "com.spotify.authentication.tokenexchangeesperanto.EsTokenExchange$TokenExchangeResponse",
            "com.spotify.connectivity.httpmonorepo.TokenResponse",
            "p.rz51"
    };

    // Known Webgate token provider and obfuscated OkHttp classes in Spotify 9.1.80.2221.
    private static final String[] KNOWN_WEBGATE_PROVIDERS = {"p.mk01", "p.aib1", "p.j7x0"};
    private static final String[] KNOWN_HEADER_BUILDERS = {"p.cuo", "p.s2v0"};
    private static final String KNOWN_REQUEST_CLASS = "p.u2v0";

    private static final String[] ACCESS_TOKEN_FIELD_HINTS = {
            "accessToken", "accessToken_", "access_token",
            "token_" // EsAccessToken$AccessToken stores the access token here (proto field)
    };

    private static volatile int authRequestDebugCount;
    private static volatile int authHeaderDebugCount;
    private static final Set<String> HOOKED_CLASSES = Collections.synchronizedSet(new HashSet<>());

    private final ClassLoader classLoader;
    private final DexKitBridge bridge;

    AuthTokenCaptureHook(ClassLoader classLoader) {
        this(classLoader, null);
    }

    AuthTokenCaptureHook(ClassLoader classLoader, DexKitBridge bridge) {
        this.classLoader = classLoader;
        this.bridge = bridge;
    }

    void hook() {
        // Retired: Spicy Lyrics API auth is inactive. Remote lyrics queries now use
        // unauthenticated Apple Music endpoints and no longer capture or persist Spotify tokens.
    }

    private void hookAccessTokenCapture() {
        NativeSpicyLyricsHook.dbgEnter("hookAccessTokenCapture");
        try {
            Class<?> requestBuilder = XpReflect.findClass("okhttp3.Request$Builder", classLoader);
            XpHooks.Before headerPairHook = param -> {
                if (param.args == null || param.args.length < 2) return;
                Object nameObj = param.args[0];
                Object valueObj = param.args[1];
                if (!(nameObj instanceof String) || !(valueObj instanceof String)) return;
                captureAuthHeader((String) nameObj, (String) valueObj);
            };
            tryHookAll(requestBuilder, "header", "auth:RequestBuilder#header", headerPairHook);
            tryHookAll(requestBuilder, "addHeader", "auth:RequestBuilder#addHeader", headerPairHook);
            tryHookAll(requestBuilder, "headers", "auth:RequestBuilder#headers", (XpHooks.Before) param -> {
                if (param.args == null || param.args.length < 1) return;
                captureAuthorizationValue(readHeaderValue(param.args[0], "Authorization"));
            });
            tryHookAll(requestBuilder, "build", "auth:RequestBuilder#build", (XpHooks.After) param -> {
                captureAuthorizationValue(readHeaderValue(param.getResult(), "Authorization"));
                logBuiltRequestProbe(param.getResult());
            });

            try {
                Class<?> headersBuilder = XpReflect.findClass("okhttp3.Headers$Builder", classLoader);
                tryHookAll(headersBuilder, "add", "auth:HeadersBuilder#add", headerPairHook);
                tryHookAll(headersBuilder, "set", "auth:HeadersBuilder#set", headerPairHook);
                tryHookAll(headersBuilder, "addUnsafeNonAscii", "auth:HeadersBuilder#addUnsafeNonAscii", headerPairHook);
            } catch (Throwable ignored) {
            }

            try {
                Class<?> requestClass = XpReflect.findClass("okhttp3.Request", classLoader);
                XpHooks.hookAllConstructors(requestClass, "auth:Request#ctor", (XpHooks.After) param -> {
                    captureAuthorizationValue(readHeaderValue(param.thisObject, "Authorization"));
                    logBuiltRequestProbe(param.thisObject);
                });
            } catch (Throwable ignored) {
            }

            XpLog.log(NativeSpicyLyricsHook.TAG + " OkHttp auth capture hooks installed");
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " auth capture hook failed: " + t);
        }
    }

    private void hookKnownWebgateClasses() {
        int webgateHooked = 0;
        for (String className : KNOWN_WEBGATE_PROVIDERS) {
            try {
                Class<?> clazz = XpReflect.findClassIfExists(className, classLoader);
                if (clazz != null && hookWebgateProviderClass(clazz, "known:" + className)) {
                    webgateHooked++;
                }
            } catch (Throwable ignored) {
            }
        }
        int buildersHooked = 0;
        for (String className : KNOWN_HEADER_BUILDERS) {
            try {
                Class<?> clazz = XpReflect.findClassIfExists(className, classLoader);
                if (clazz != null && hookHeaderBuilderClass(clazz, "known:" + className)) {
                    buildersHooked++;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Class<?> reqClass = XpReflect.findClassIfExists(KNOWN_REQUEST_CLASS, classLoader);
            if (reqClass != null) {
                hookRequestClass(reqClass, "known:" + KNOWN_REQUEST_CLASS);
            }
        } catch (Throwable ignored) {
        }
        XpLog.log(NativeSpicyLyricsHook.TAG
                + " known Webgate and OkHttp hooks installed webgate=" + webgateHooked
                + " builders=" + buildersHooked);
    }

    private void hookWebgateViaDexKit() {
        if (bridge == null) return;
        int dexKitMatches = 0;
        String[][] providerProbes = {
                {"tokenResponse with null accessToken"},
                {"sp://auth/v2/token responded with an error: "},
                {"sp://auth/v2/token"}
        };
        for (String[] probe : providerProbes) {
            try {
                var found = bridge.findClass(FindClass.create().matcher(ClassMatcher.create().usingStrings(probe)));
                for (var data : found) {
                    try {
                        Class<?> cls = data.getInstance(classLoader);
                        if (hookWebgateProviderClass(cls, "dexkit:" + String.join(",", probe))) {
                            dexKitMatches++;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable t) {
                XpLog.log(NativeSpicyLyricsHook.TAG + " DexKit Webgate probe failed: " + t);
            }
        }

        try {
            var found = bridge.findClass(FindClass.create().matcher(
                    ClassMatcher.create().usingStrings("Request{method=", ", headers=[")));
            for (var data : found) {
                try {
                    Class<?> reqClass = data.getInstance(classLoader);
                    hookRequestClass(reqClass, "dexkit:request");
                    for (Constructor<?> ctor : reqClass.getDeclaredConstructors()) {
                        Class<?>[] pts = ctor.getParameterTypes();
                        if (pts.length == 1 && pts[0] != Object.class && !pts[0].isPrimitive()) {
                            hookHeaderBuilderClass(pts[0], "dexkit:requestBuilder");
                        }
                    }
                    dexKitMatches++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " DexKit Request probe failed: " + t);
        }

        XpLog.log(NativeSpicyLyricsHook.TAG + " DexKit Webgate hooks installed matches=" + dexKitMatches);
    }

    private boolean hookWebgateProviderClass(Class<?> clazz, String sourceTag) {
        if (clazz == null || !HOOKED_CLASSES.add(clazz.getName() + ":webgate")) return false;
        int count = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getReturnType() != String.class) continue;
            if (method.getParameterTypes().length > 1) continue;
            final String methodName = method.getName();
            try {
                method.setAccessible(true);
                XpHooks.hookAfter(method, "auth:webgate:" + clazz.getName() + "#" + methodName, param -> {
                    Object result = param.getResult();
                    if (result instanceof String) {
                        captureAccessTokenCandidate((String) result, 0L, sourceTag + "#" + methodName);
                    }
                });
                count++;
            } catch (Throwable ignored) {
            }
        }
        return count > 0;
    }

    private boolean hookHeaderBuilderClass(Class<?> clazz, String sourceTag) {
        if (clazz == null || !HOOKED_CLASSES.add(clazz.getName() + ":headerBuilder")) return false;
        int count = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            Class<?>[] pTypes = method.getParameterTypes();
            if (pTypes.length == 2 && pTypes[0] == String.class && pTypes[1] == String.class) {
                final String methodName = method.getName();
                try {
                    method.setAccessible(true);
                    XpHooks.hookBefore(method, "auth:headerBuilder:" + clazz.getName() + "#" + methodName, param -> {
                        if (param.args != null && param.args.length >= 2) {
                            Object name = param.args[0];
                            Object val = param.args[1];
                            if (name instanceof String && val instanceof String) {
                                captureAuthHeader((String) name, (String) val);
                            }
                        }
                    });
                    count++;
                } catch (Throwable ignored) {
                }
            }
        }
        return count > 0;
    }

    private void hookRequestClass(Class<?> clazz, String sourceTag) {
        if (clazz == null || !HOOKED_CLASSES.add(clazz.getName() + ":request")) return;
        try {
            XpHooks.hookAllConstructors(clazz, "auth:req:" + clazz.getName(), (XpHooks.After) param -> {
                extractAuthorizationFromRequestObject(param.thisObject, sourceTag);
            });
        } catch (Throwable ignored) {
        }
    }

    private static void extractAuthorizationFromRequestObject(Object request, String sourceTag) {
        if (request == null) return;
        try {
            for (Field field : request.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object val = field.get(request);
                if (val == null) continue;
                for (Field innerField : val.getClass().getDeclaredFields()) {
                    if (innerField.getType() == String[].class) {
                        innerField.setAccessible(true);
                        String[] arr = (String[]) innerField.get(val);
                        if (arr != null) {
                            for (int i = 0; i < arr.length - 1; i += 2) {
                                if ("authorization".equalsIgnoreCase(arr[i])) {
                                    captureAuthorizationValue(arr[i + 1]);
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void tryHookAll(Class<?> clazz, String methodName, String id, XpHooks.Before hook) {
        try {
            XpHooks.hookAllMethods(clazz, methodName, id, hook);
        } catch (Throwable ignored) {
        }
    }

    private static void tryHookAll(Class<?> clazz, String methodName, String id, XpHooks.After hook) {
        try {
            XpHooks.hookAllMethods(clazz, methodName, id, hook);
        } catch (Throwable ignored) {
        }
    }

    private static String readHeaderValue(Object headersOrRequest, String name) {
        if (headersOrRequest == null || isBlank(name)) return null;
        for (String methodName : new String[]{"header", "get"}) {
            try {
                Object result = XpReflect.callMethod(headersOrRequest, methodName, name);
                if (result instanceof String) return (String) result;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void captureAuthorizationValue(String headerValue) {
        captureAuthHeader("Authorization", headerValue);
    }

    private static void logBuiltRequestProbe(Object request) {
        if (request == null || authRequestDebugCount >= AUTH_REQUEST_DEBUG_LIMIT) return;
        try {
            Object rawUrl = XpReflect.callMethod(request, "url");
            if (rawUrl == null) return;
            String url = rawUrl.toString();
            String lower = url.toLowerCase(Locale.ROOT);
            if (!lower.contains("spotify") && !lower.contains("spclient")) return;
            String auth = readHeaderValue(request, "Authorization");
            Uri uri = Uri.parse(url);
            String host = safe(uri.getHost());
            String path = safe(uri.getPath());
            authRequestDebugCount++;
            XpLog.log(NativeSpicyLyricsHook.TAG + " okhttp request#" + authRequestDebugCount
                    + " host=" + host
                    + " path=" + path
                    + " auth=" + (!isBlank(auth)));
        } catch (Throwable ignored) {
        }
    }

    private static void captureAuthHeader(String headerName, String headerValue) {
        if (headerName == null || headerValue == null) return;
        if (!headerName.equalsIgnoreCase("authorization")) return;
        boolean bearer = headerValue.toLowerCase(Locale.ROOT).startsWith("bearer");
        if (bearer || authHeaderDebugCount < AUTH_HEADER_DEBUG_LIMIT) {
            authHeaderDebugCount++;
            NativeSpicyLyricsHook.dbg("captureAuthHeader",
                    "authorization hasValue=true bearer=" + bearer);
        }
        if (!bearer) return;
        String token = headerValue.replaceFirst("(?i)^bearer", "").trim();
        if (token.isEmpty() || token.equals("0")) return;
        // Header captures carry no observed expiry. All capture paths share SpotifyTokenStore.
        SpotifyTokenStore.store(token, System.currentTimeMillis(), 0L, "okhttp:authorization-header");
    }

    static void restorePersistedAccessToken(Context context) {
        // Retired: Spicy Lyrics API auth is inactive.
    }

    // Probe Spotify's auth-token classes directly - the OkHttp-header capture misses the token on
    // modern Spotify (auth doesn't flow through hooked okhttp). The token fires on cold-start refresh.
    private void hookSpotifyAuthTokenObjects() {
        int hooked = 0;
        for (String className : SPOTIFY_AUTH_TOKEN_CLASSES) {
            try {
                Class<?> clazz = XpReflect.findClass(className, classLoader);
                XpHooks.hookAllConstructors(clazz, "auth:" + className + "#ctor", (XpHooks.After) param -> {
                    captureAccessTokenObject(param.thisObject, className + "#ctor");
                });
                for (Method method : clazz.getDeclaredMethods()) {
                    if (method.getParameterTypes().length != 0) continue;
                    Class<?> rt = method.getReturnType();
                    // Modern Spotify exposes the access token directly through the obfuscated
                    // AccessTokenResponse#o() String getter. Older builds return a nested token
                    // object. Keep the object path precise, and admit only that known direct
                    // getter rather than sweeping arbitrary String methods.
                    boolean directAccessTokenGetter = rt == String.class
                            && isDirectAccessTokenGetter(clazz, method.getName());
                    if (!directAccessTokenGetter && (rt == String.class || rt.isPrimitive()
                            || !rt.getName().toLowerCase(Locale.ROOT).contains("token"))) continue;
                    final String name = method.getName();
                    try {
                        XpHooks.hookAfter(method, "auth:" + className + "#" + name, param -> {
                            if (directAccessTokenGetter) {
                                Object result = param.getResult();
                                if (result instanceof String) {
                                    captureAccessTokenCandidate((String) result, 0L,
                                            className + "#" + name);
                                }
                            } else {
                                captureAccessTokenObject(param.getResult(), className + "#" + name);
                            }
                        });
                    } catch (Throwable ignored) {
                    }
                }
                hooked++;
            } catch (Throwable ignored) {
            }
        }
        XpLog.log(NativeSpicyLyricsHook.TAG
                + " Spotify auth token object hooks installed classes=" + hooked);
    }

    private static boolean isDirectAccessTokenGetter(Class<?> clazz, String methodName) {
        if (clazz == null || methodName == null) return false;
        String className = clazz.getName();
        boolean knownResponse = className.equals(
                "com.spotify.authentication.login5esperanto.EsAccessTokenClient$AccessTokenResponse")
                || className.equals("com.spotify.connectivity.httpmonorepo.TokenResponse")
                || className.equals("p.rz51");
        return knownResponse && (methodName.equals("o") || methodName.equals("c")
                || methodName.equals("accessToken") || methodName.equals("getAccessToken"));
    }

    private static void captureAccessTokenObject(Object tokenObject, String source) {
        captureAccessTokenObject(tokenObject, source, 0);
    }

    // Capture only the access-token field (token_/accessToken), recursing one level into nested
    // objects (AccessTokenResponse -> AccessToken.token_). Never grabs arbitrary long strings.
    // Sibling expires-in/expiry fields of the same object are picked up as best-effort observed
    // expiry and persisted alongside the token so restores can apply the expiry freshness rule.
    private static void captureAccessTokenObject(Object tokenObject, String source, int depth) {
        if (tokenObject == null || depth > 2) return;
        if (tokenObject instanceof String) {
            captureAccessTokenCandidate((String) tokenObject, 0L, source);
            return;
        }
        Class<?> clazz = tokenObject.getClass();
        String cn = clazz.getName();
        if (clazz.isArray() || cn.startsWith("java.") || cn.startsWith("android.") || cn.startsWith("kotlin.")) return;
        String tokenCandidate = null;
        String obfuscatedTokenCandidate = null;
        Long expiresInSeconds = null;
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(tokenObject);
                    if (value instanceof String) {
                        if (tokenCandidate == null && isAccessTokenFieldName(field.getName())) {
                            tokenCandidate = (String) value;
                        }
                        if (obfuscatedTokenCandidate == null && isSpotifyAccessTokenClass(cn)
                                && looksLikeSpotifyAccessToken((String) value)) {
                            obfuscatedTokenCandidate = (String) value;
                        }
                    } else if (value instanceof Number) {
                        if (expiresInSeconds == null) {
                            expiresInSeconds = observedExpiresInSeconds(
                                    field.getName(), ((Number) value).longValue());
                        }
                    } else if (value != null && depth < 2) {
                        captureAccessTokenObject(value, source + "#" + field.getName(), depth + 1);
                    }
                } catch (Throwable ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        if (tokenCandidate == null) tokenCandidate = obfuscatedTokenCandidate;
        if (tokenCandidate != null) {
            captureAccessTokenCandidate(tokenCandidate,
                    expiresInSeconds == null ? 0L : expiresInSeconds, source);
        }
    }

    // Best-effort observed expiry from sibling fields of Spotify auth response objects. Returns
    // seconds-until-expiry only for plausible values under a recognized field name; unknown fields,
    // implausible magnitudes, and computed past expiries all yield null (expiry stays unknown).
    private static Long observedExpiresInSeconds(String fieldName, long rawValue) {
        if (isBlank(fieldName) || rawValue <= 0) return null;
        String lower = fieldName.toLowerCase(Locale.ROOT);
        if (!lower.contains("expire") && !lower.contains("expiry") && !lower.contains("expires")) return null;
        if (rawValue > 1_000_000_000_000L) {
            // Epoch milliseconds: convert to seconds remaining at capture time.
            long until = (rawValue - System.currentTimeMillis()) / 1000L;
            return until > 0 && until <= MAX_PLAUSIBLE_EXPIRES_IN_SECONDS ? until : null;
        }
        if (rawValue <= MAX_PLAUSIBLE_EXPIRES_IN_SECONDS) return rawValue;
        return null;
    }

    private static boolean isAccessTokenFieldName(String name) {
        if (isBlank(name)) return false;
        for (String hint : ACCESS_TOKEN_FIELD_HINTS) {
            if (name.equals(hint)) return true;
        }
        return false;
    }

    private static boolean isSpotifyAccessTokenClass(String className) {
        return className != null && (className.endsWith("$AccessToken")
                || className.endsWith("AccessTokenResponse")
                || className.equals("p.rz51"));
    }

    private static void captureAccessTokenCandidate(String candidate, long expiresInSeconds, String source) {
        if (isBlank(candidate)) return;
        String token = candidate.trim();
        if (token.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            token = token.substring("bearer ".length()).trim();
        }
        if (!looksLikeSpotifyAccessToken(token)) return;
        long now = System.currentTimeMillis();
        long expiresAtMillis = expiresInSeconds > 0 ? now + expiresInSeconds * 1000L : 0L;
        SpotifyTokenStore.store(token, now, expiresAtMillis, source);
    }

    private static boolean looksLikeSpotifyAccessToken(String token) {
        if (isBlank(token) || "0".equals(token)) return false;
        if (token.length() < 20 || token.length() > 4096) return false;
        for (int i = 0; i < token.length(); i++) {
            if (Character.isWhitespace(token.charAt(i))) return false;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        return !lower.equals("bearer") && !lower.equals("access_token")
                && !lower.equals("token") && !lower.startsWith("spotify:");
    }
}
