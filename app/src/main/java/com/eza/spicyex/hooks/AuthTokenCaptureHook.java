package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.isBlank;
import static com.eza.spicyex.hooks.NativeLyricsUtils.safe;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;

import com.eza.spicyex.References;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Captures Spotify access tokens from OkHttp headers and Spotify auth response objects. */
final class AuthTokenCaptureHook {
    private static final String PREFS_MAIN = "SpotifyPlus";
    private static final String KEY_LAST_SPOTIFY_ACCESS_TOKEN = "native_spotify_access_token";
    private static final int AUTH_REQUEST_DEBUG_LIMIT = 20;
    private static final int AUTH_HEADER_DEBUG_LIMIT = 24;
    // Spotify's auth-token classes (kept names; fields are proto-style). The access token lives in
    // EsAccessToken$AccessToken.token_ (obfuscated getter), which the OkHttp-header capture misses.
    private static final String[] SPOTIFY_AUTH_TOKEN_CLASSES = {
            "com.spotify.authentication.login5esperanto.EsAccessToken$AccessToken",
            "com.spotify.authentication.login5esperanto.EsAccessTokenClient$AccessTokenResponse",
            "com.spotify.authentication.oauth.AccessToken",
            "com.spotify.authentication.tokenexchangeimpl.model.TokenResponse",
            "com.spotify.authentication.tokenexchangeesperanto.EsTokenExchange$TokenExchangeResponse"
    };
    private static final String[] ACCESS_TOKEN_FIELD_HINTS = {
            "accessToken", "accessToken_", "access_token",
            "token_" // EsAccessToken$AccessToken stores the access token here (proto field)
    };

    private static volatile int authRequestDebugCount;
    private static volatile int authHeaderDebugCount;

    private final ClassLoader classLoader;

    AuthTokenCaptureHook(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    void hook() {
        hookAccessTokenCapture();
        hookSpotifyAuthTokenObjects();
    }

    private void hookAccessTokenCapture() {
        NativeSpicyLyricsHook.dbgEnter("hookAccessTokenCapture");
        try {
            Class<?> requestBuilder = XposedHelpers.findClass("okhttp3.Request$Builder", classLoader);
            XC_MethodHook headerPairHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 2) return;
                    Object nameObj = param.args[0];
                    Object valueObj = param.args[1];
                    if (!(nameObj instanceof String) || !(valueObj instanceof String)) return;
                    captureAuthHeader((String) nameObj, (String) valueObj);
                }
            };
            tryHookAll(requestBuilder, "header", headerPairHook);
            tryHookAll(requestBuilder, "addHeader", headerPairHook);
            tryHookAll(requestBuilder, "headers", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 1) return;
                    captureAuthorizationValue(readHeaderValue(param.args[0], "Authorization"));
                }
            });
            tryHookAll(requestBuilder, "build", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    captureAuthorizationValue(readHeaderValue(param.getResult(), "Authorization"));
                    logBuiltRequestProbe(param.getResult());
                }
            });

            try {
                Class<?> headersBuilder = XposedHelpers.findClass("okhttp3.Headers$Builder", classLoader);
                tryHookAll(headersBuilder, "add", headerPairHook);
                tryHookAll(headersBuilder, "set", headerPairHook);
                tryHookAll(headersBuilder, "addUnsafeNonAscii", headerPairHook);
            } catch (Throwable ignored) {
            }

            try {
                Class<?> requestClass = XposedHelpers.findClass("okhttp3.Request", classLoader);
                XposedBridge.hookAllConstructors(requestClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        captureAuthorizationValue(readHeaderValue(param.thisObject, "Authorization"));
                        logBuiltRequestProbe(param.thisObject);
                    }
                });
            } catch (Throwable ignored) {
            }

            XposedBridge.log(NativeSpicyLyricsHook.TAG + " OkHttp auth capture hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " auth capture hook failed: " + t);
        }
    }

    private static void tryHookAll(Class<?> clazz, String methodName, XC_MethodHook hook) {
        try {
            XposedBridge.hookAllMethods(clazz, methodName, hook);
        } catch (Throwable ignored) {
        }
    }

    private static String readHeaderValue(Object headersOrRequest, String name) {
        if (headersOrRequest == null || isBlank(name)) return null;
        for (String methodName : new String[]{"header", "get"}) {
            try {
                Object result = XposedHelpers.callMethod(headersOrRequest, methodName, name);
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
            Object rawUrl = XposedHelpers.callMethod(request, "url");
            if (rawUrl == null) return;
            String url = rawUrl.toString();
            String lower = url.toLowerCase(Locale.ROOT);
            if (!lower.contains("spotify") && !lower.contains("spclient")) return;
            String auth = readHeaderValue(request, "Authorization");
            Uri uri = Uri.parse(url);
            String host = safe(uri.getHost());
            String path = safe(uri.getPath());
            authRequestDebugCount++;
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " okhttp request#" + authRequestDebugCount
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
                    "authorization hasValue=true bearer=" + bearer + " len=" + headerValue.length());
        }
        if (!bearer) return;
        String token = headerValue.replaceFirst("(?i)^bearer", "").trim();
        if (token.isEmpty() || token.equals("0")) return;
        String old = References.accessToken;
        if (old != null && old.equals(token)) return;
        References.accessToken = token;
        persistAccessToken(token);
        XposedBridge.log(NativeSpicyLyricsHook.TAG + " captured Spotify access token len=" + token.length());
    }

    private static Context appContext() {
        Activity activity = References.currentActivity;
        if (activity != null) return activity.getApplicationContext();
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
            if (app instanceof Context) return ((Context) app).getApplicationContext();
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void persistAccessToken(String token) {
        Context context = appContext();
        if (context == null || isBlank(token)) return;
        try {
            context.getSharedPreferences(PREFS_MAIN, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_SPOTIFY_ACCESS_TOKEN, token)
                    .apply();
        } catch (Throwable t) {
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " token persist failed: " + t);
        }
    }

    static void restorePersistedAccessToken(Context context) {
        if (context == null || !isBlank(References.accessToken)) return;
        try {
            String stored = context.getSharedPreferences(PREFS_MAIN, Context.MODE_PRIVATE)
                    .getString(KEY_LAST_SPOTIFY_ACCESS_TOKEN, "");
            if (isBlank(stored) || "0".equals(stored)) return;
            References.accessToken = stored;
            XposedBridge.log(NativeSpicyLyricsHook.TAG
                    + " restored persisted Spotify access token len=" + stored.length());
        } catch (Throwable t) {
            XposedBridge.log(NativeSpicyLyricsHook.TAG + " token restore failed: " + t);
        }
    }

    // Probe Spotify's auth-token classes directly - the OkHttp-header capture misses the token on
    // modern Spotify (auth doesn't flow through hooked okhttp). The token fires on cold-start refresh.
    private void hookSpotifyAuthTokenObjects() {
        int hooked = 0;
        for (String className : SPOTIFY_AUTH_TOKEN_CLASSES) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, classLoader);
                XposedBridge.hookAllConstructors(clazz, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        captureAccessTokenObject(param.thisObject, className + "#ctor");
                    }
                });
                for (Method method : clazz.getDeclaredMethods()) {
                    if (method.getParameterTypes().length != 0) continue;
                    Class<?> rt = method.getReturnType();
                    // Hook only object getters returning a token-shaped object; extract the precise
                    // access-token field. Skip raw String getters (may return a refresh token / "Bearer").
                    if (rt == String.class || rt.isPrimitive()
                            || !rt.getName().toLowerCase(Locale.ROOT).contains("token")) continue;
                    final String name = method.getName();
                    try {
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
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
        XposedBridge.log(NativeSpicyLyricsHook.TAG
                + " Spotify auth token object hooks installed classes=" + hooked);
    }

    private static void captureAccessTokenObject(Object tokenObject, String source) {
        captureAccessTokenObject(tokenObject, source, 0);
    }

    // Capture only the access-token field (token_/accessToken), recursing one level into nested
    // objects (AccessTokenResponse -> AccessToken.token_). Never grabs arbitrary long strings.
    private static void captureAccessTokenObject(Object tokenObject, String source, int depth) {
        if (tokenObject == null || depth > 2) return;
        if (tokenObject instanceof String) {
            captureAccessTokenCandidate((String) tokenObject, source);
            return;
        }
        Class<?> clazz = tokenObject.getClass();
        String cn = clazz.getName();
        if (clazz.isArray() || cn.startsWith("java.") || cn.startsWith("android.") || cn.startsWith("kotlin.")) return;
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(tokenObject);
                    if (value instanceof String) {
                        if (isAccessTokenFieldName(field.getName())) {
                            captureAccessTokenCandidate((String) value, source + "#" + field.getName());
                        }
                    } else if (value != null && depth < 2) {
                        captureAccessTokenObject(value, source + "#" + field.getName(), depth + 1);
                    }
                } catch (Throwable ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    private static boolean isAccessTokenFieldName(String name) {
        if (isBlank(name)) return false;
        for (String hint : ACCESS_TOKEN_FIELD_HINTS) {
            if (name.equals(hint)) return true;
        }
        return false;
    }

    private static void captureAccessTokenCandidate(String candidate, String source) {
        if (isBlank(candidate)) return;
        String token = candidate.trim();
        if (token.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            token = token.substring("bearer ".length()).trim();
        }
        if (!looksLikeSpotifyAccessToken(token)) return;
        storeSpotifyAccessToken(token, source);
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

    private static void storeSpotifyAccessToken(String token, String source) {
        if (!looksLikeSpotifyAccessToken(token)) return;
        String old = References.accessToken;
        if (old != null && old.equals(token)) return;
        References.accessToken = token;
        persistAccessToken(token);
        XposedBridge.log(NativeSpicyLyricsHook.TAG
                + " captured Spotify access token source=" + source + " len=" + token.length());
    }
}
