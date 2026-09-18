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
import java.util.Arrays;

/** Owns lazy DexKit discovery; warm startup uses validated symbol records without native loading. */
public final class SpotifySymbolResolver implements AutoCloseable {
    // Bump when discovery matchers or record semantics change, not for unrelated module updates.
    private static final int RESOLVER_REVISION = 1;
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
        return cache.method("track." + wrapper.getName() + "." + returnType.getName(),
                () -> findTrackMethod(wrapper, returnType));
    }

    static Method findTrackMethod(Class<?> wrapper, Class<?> returnType) throws NoSuchMethodException {
        int required = Modifier.PUBLIC | Modifier.FINAL;
        for (Method method : wrapper.getDeclaredMethods()) {
            if ((method.getModifiers() & required) == required
                    && method.getParameterTypes().length == 0 && method.getReturnType() == returnType) {
                return method;
            }
        }
        throw new NoSuchMethodException(wrapper.getName() + " track accessor " + returnType.getName());
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
