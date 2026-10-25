package com.eza.spicyex.xposed;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;

import org.luckypray.dexkit.DexKitBridge;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Owns lazy DexKit discovery; warm startup uses validated symbol records without native loading. */
public final class SpotifySymbolResolver implements AutoCloseable {
    // Bump when discovery matchers or record semantics change, not for unrelated module updates.
    private static final int RESOLVER_REVISION = 2;
    private static final Set<String> AMBIGUITY_WARNINGS = ConcurrentHashMap.newKeySet();
    public final ResolvedSymbolCache cache;
    private final String sourceDir;
    private DexKitBridge bridge;
    private boolean nativeFailed;
    private int bridgeAttempts;
    private final long started = SystemClock.elapsedRealtime();

    public SpotifySymbolResolver(Context context, ClassLoader loader) {
        ApplicationInfo app = context.getApplicationInfo();
        sourceDir = app.sourceDir;
        StringBuilder identity = new StringBuilder("resolver=").append(RESOLVER_REVISION);
        File cacheFile = null;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            identity.append('|').append(context.getPackageName()).append('|')
                    .append(Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode)
                    .append('|').append(info.lastUpdateTime);
            appendApk(identity, app.sourceDir);
            if (app.splitSourceDirs != null) {
                String[] splits = app.splitSourceDirs.clone();
                Arrays.sort(splits);
                for (String path : splits) appendApk(identity, path);
            }
            cacheFile = new File(context.getNoBackupFilesDir(), "spicy-symbols-"
                    + processName().replaceAll("[^A-Za-z0-9_.-]", "_") + ".properties");
        } catch (Exception error) {
            XpLog.log("[SpotifyPlus] symbol cache storage unavailable: " + error.getClass().getSimpleName());
        }
        cache = new ResolvedSymbolCache(cacheFile, identity.toString(), loader);
    }

    private static String processName() {
        if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName();
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(new File("/proc/self/cmdline").toPath());
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).split("\\x00", 2)[0];
        } catch (Exception error) {
            throw new IllegalStateException("Process identity unavailable", error);
        }
    }

    private static void appendApk(StringBuilder key, String path) {
        File apk = new File(path);
        key.append('|').append(path).append(':').append(apk.length()).append(':').append(apk.lastModified());
    }

    public synchronized DexKitBridge dexKit() {
        if (bridge != null) return bridge;
        if (nativeFailed) throw new IllegalStateException("DexKit unavailable after bounded retry");
        // Discovery now starts at Application.attach. Retry the library load itself, as in B678.
        for (int attempt = 0; attempt < 2; attempt++) {
            bridgeAttempts++;
            try {
                System.loadLibrary("dexkit");
                bridge = java.util.Objects.requireNonNull(DexKitBridge.create(sourceDir));
                return bridge;
            } catch (Throwable error) {
                if (attempt == 1) {
                    nativeFailed = true;
                    throw new IllegalStateException("DexKit native initialization failed", error);
                }
                XpLog.log("[SpotifyPlus] retrying DexKit native initialization");
            }
        }
        throw new IllegalStateException("DexKit unavailable");
    }

    public Method trackMethod(Class<?> wrapper, Class<?> returnType) throws Exception {
        return trackMethod(cache, wrapper, returnType);
    }

    static Method trackMethod(ResolvedSymbolCache cache, Class<?> wrapper, Class<?> returnType)
            throws Exception {
        return cache.method("track." + wrapper.getName() + "." + returnType.getName(),
                method -> eligible(method, wrapper, returnType),
                () -> findTrackMethod(wrapper, returnType));
    }

    static Method findTrackMethod(Class<?> wrapper, Class<?> returnType) throws NoSuchMethodException {
        return selectTrackMethod(rankTrackCandidates(wrapper, returnType), wrapper, returnType);
    }

    static Ranked rankTrackCandidates(Class<?> wrapper, Class<?> returnType) {
        return rankTrackCandidates(wrapper, returnType, wrapper.getDeclaredMethods());
    }

    static Ranked rankTrackCandidates(Class<?> wrapper, Class<?> returnType, Method[] declared) {
        Map<String, Method> unique = new LinkedHashMap<>();
        for (Method method : declared) {
            if (eligible(method, wrapper, returnType)) {
                unique.putIfAbsent(ResolvedSymbolCache.signature(method), method);
            }
        }
        List<Method> ordered = new ArrayList<>(unique.values());
        ordered.sort((left, right) -> {
            int byScore = Integer.compare(candidateScore(right), candidateScore(left));
            if (byScore != 0) return byScore;
            return ResolvedSymbolCache.signature(left)
                    .compareTo(ResolvedSymbolCache.signature(right));
        });
        int topScore = ordered.isEmpty() ? -1 : candidateScore(ordered.get(0));
        boolean ambiguous = ordered.size() > 1
                && candidateScore(ordered.get(1)) == topScore;
        return new Ranked(Collections.unmodifiableList(ordered), topScore, ambiguous);
    }

    static Method selectTrackMethod(Ranked ranked, Class<?> wrapper, Class<?> returnType)
            throws NoSuchMethodException {
        if (ranked.ordered.isEmpty()) {
            throw new NoSuchMethodException(wrapper.getName() + " track accessor "
                    + returnType.getName());
        }
        Method winner = ranked.ordered.get(0);
        if (ranked.ambiguous) logAmbiguity(wrapper, returnType, ranked);
        return winner;
    }

    private static void logAmbiguity(Class<?> wrapper, Class<?> returnType, Ranked ranked) {
        String key = wrapper.getName() + '|' + returnType.getName();
        if (!AMBIGUITY_WARNINGS.add(key)) return;
        List<String> tied = new ArrayList<>();
        for (Method method : ranked.ordered) {
            if (candidateScore(method) != ranked.topScore) break;
            tied.add(ResolvedSymbolCache.signature(method));
        }
        XpLog.log("[SpotifyPlus] " + wrapper.getName() + " has " + tied.size()
                + " equal-score track accessors for " + returnType.getName()
                + ": " + String.join(", ", tied), null);
    }

    private static boolean eligible(Method method, Class<?> wrapper, Class<?> returnType) {
        int modifiers = method.getModifiers();
        int required = Modifier.PUBLIC | Modifier.FINAL;
        return method.getDeclaringClass() == wrapper
                && method.getParameterCount() == 0
                && method.getReturnType() == returnType
                && (modifiers & required) == required
                && !Modifier.isStatic(modifiers)
                && !Modifier.isAbstract(modifiers);
    }

    private static int candidateScore(Method method) {
        int score = 0;
        if (!method.isSynthetic() && !method.isBridge()) score += 2;
        if (!Modifier.isNative(method.getModifiers())) score += 1;
        return score;
    }

    static final class Ranked {
        final List<Method> ordered;
        final int topScore;
        final boolean ambiguous;

        Ranked(List<Method> ordered, int topScore, boolean ambiguous) {
            this.ordered = ordered;
            this.topScore = topScore;
            this.ambiguous = ambiguous;
        }
    }

    @Override public synchronized void close() {
        try {
            if (bridge != null) bridge.close();
        } finally {
            bridge = null;
            XpLog.log("[SpotifyPlus] symbol discovery " + cache.stats() + " nativeAttempts=" + bridgeAttempts
                    + " elapsedMs=" + (SystemClock.elapsedRealtime() - started) + " bridgeReleased=true");
        }
    }
}
