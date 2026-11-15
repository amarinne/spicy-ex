package com.eza.spicyex.lyrics.language;

import android.content.Context;

import com.eza.spicyex.BuildConfig;
import com.eza.spicyex.lyrics.language.mandarin.MandarinReader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Downloads and atomically activates the optional language model pack. */
public final class LanguageModelPack {
    public enum Phase {
        IDLE,
        DOWNLOADING,
        READY,
        ERROR
    }

    public static final class DownloadStatus {
        public final Phase phase;
        public final int progressPercent;
        public final String errorCode;

        public DownloadStatus(Phase phase, int progressPercent, String errorCode) {
            this.phase = phase == null ? Phase.IDLE : phase;
            this.progressPercent = Math.max(0, Math.min(100, progressPercent));
            this.errorCode = errorCode == null ? "" : errorCode;
        }
    }

    private static final String VERSION = "v2";
    /** Earlier pack roots. A finished install of this version removes them. */
    private static final String[] RETIRED_VERSIONS = {"v1"};
    private static final String READY = ".ready";
    private static final int BUFFER_SIZE = 1 << 16;
    // The v2 recipe is v1 (11 files, ~52 MiB compressed, ~153 MiB expanded) plus the
    // Mandarin g2pM reader (17 files, ~16 MiB expanded). Allow
    // ample ZIP overhead and headroom without allowing an unbounded transfer or install.
    private static final long MAX_DOWNLOAD_BYTES = 128L << 20;
    private static final long MAX_EXPANDED_BYTES = 384L << 20;
    private static final int MAX_ENTRIES = 128;
    private static final long DOWNLOAD_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(10);
    private static final Set<String> REQUIRED = requiredFiles();
    private static final ExecutorService DOWNLOADS = Executors.newSingleThreadExecutor();
    private static volatile Context appContext;
    private static volatile boolean downloadStarted;
    private static volatile boolean upgradeRequested;
    private static volatile Runnable readyListener;
    private static volatile DownloadStatus transientStatus = new DownloadStatus(Phase.IDLE, 0, "");

    private LanguageModelPack() {
    }

    public static void attachContext(Context context) {
        if (context == null) return;
        Context applicationContext = context.getApplicationContext();
        appContext = applicationContext == null ? context : applicationContext;
        upgradeRetiredPack();
    }

    /**
     * A user who installed an earlier pack already chose the download, so fetch the current
     * version once per process instead of turning their readings off until they ask again.
     */
    private static void upgradeRetiredPack() {
        if (upgradeRequested) return;
        Context context = appContext;
        File parent = context == null ? null : context.getFilesDir();
        if (!hasRetiredPack(parent) || isReady()) return;
        upgradeRequested = true;
        requestDownload();
    }

    static boolean hasRetiredPack(File parent) {
        if (parent == null) return false;
        for (String version : RETIRED_VERSIONS) {
            if (new File(new File(parent, "language-models-" + version), READY).isFile()) return true;
        }
        return false;
    }

    public static void setReadyListener(Runnable listener) {
        readyListener = listener;
        // A screen can be created after the settings download has completed. In that case the
        // one-shot download callback already happened, but the newly mounted document still
        // needs a chance to reprocess against the installed resources.
        if (listener != null && isReady()) listener.run();
    }

    public static void clearReadyListener(Runnable listener) {
        if (readyListener == listener) readyListener = null;
    }

    public static void clearTransientState() {
        // The worker retains single-flight ownership until its finally block runs.
        transientStatus = new DownloadStatus(Phase.IDLE, 0, "");
    }

    public static DownloadStatus status() {
        if (isReady()) return new DownloadStatus(Phase.READY, 100, "");
        return transientStatus;
    }

    public static void requestDownload() {
        if (appContext == null) { transientStatus = new DownloadStatus(Phase.ERROR, 0, "NO_CONTEXT"); return; }
        if (BuildConfig.LANGUAGE_MODEL_PACK_URL.isEmpty()) { transientStatus = new DownloadStatus(Phase.ERROR, 0, "NO_DOWNLOAD_URL"); return; }
        if (BuildConfig.LANGUAGE_MODEL_PACK_SHA256.isEmpty()) { transientStatus = new DownloadStatus(Phase.ERROR, 0, "NO_SHA256"); return; }
        if (isReady()) return;
        transientStatus = new DownloadStatus(Phase.DOWNLOADING, 0, "");
        prefetch();
    }

    public static boolean isReady() {
        File root = root();
        return isReady(root);
    }

    private static boolean isReady(File root) {
        if (root == null || !new File(root, READY).isFile()) return false;
        for (String required : REQUIRED) {
            if (!new File(root, required).isFile()) return false;
        }
        return true;
    }

    public static InputStream open(String relativePath) {
        File root = root();
        if (root == null || !isReady()) return null;
        File file = safeChild(root, relativePath);
        try {
            return file != null && file.isFile() ? new FileInputStream(file) : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    @FunctionalInterface
    interface Installation { void run() throws Exception; }

    @FunctionalInterface
    interface Downloader { void download(File archive) throws Exception; }

    @FunctionalInterface
    interface Renamer { boolean rename(File from, File to); }

    private static void prefetch() {
        prefetch(DOWNLOADS, LanguageModelPack::downloadAndInstall);
    }

    // Package-local seams keep tests on the production single-flight and install owners.
    static void prefetch(Executor executor, Installation installation) {
        if (downloadStarted || isReady() || BuildConfig.LANGUAGE_MODEL_PACK_URL.isEmpty()) return;
        synchronized (LanguageModelPack.class) {
            if (downloadStarted || isReady()) return;
            downloadStarted = true;
        }
        try {
            executor.execute(() -> {
                try {
                    installation.run();
                } catch (Throwable failure) {
                    transientStatus = new DownloadStatus(Phase.ERROR, 0, describe(failure));
                } finally {
                    downloadStarted = false;
                }
            });
        } catch (RuntimeException failure) {
            downloadStarted = false;
            transientStatus = new DownloadStatus(Phase.ERROR, 0, describe(failure));
        }
    }

    private static void downloadAndInstall() throws Exception {
        Context context = appContext;
        File parent = context == null ? null : context.getFilesDir();
        if (parent == null) {
            transientStatus = new DownloadStatus(Phase.ERROR, 0, "NO_CONTEXT");
            return;
        }
        downloadAndInstall(parent, BuildConfig.LANGUAGE_MODEL_PACK_SHA256,
                LanguageModelPack::downloadArchive, File::renameTo);
    }

    static OkHttpClient downloadClient() {
        // Includes redirects, connection setup, and the entire response body, even if a
        // server keeps sending a few bytes often enough to avoid the read timeout.
        return new OkHttpClient.Builder().callTimeout(DOWNLOAD_TIMEOUT_NANOS, TimeUnit.NANOSECONDS).build();
    }

    private static void downloadArchive(File archive) throws IOException {
        Request request = new Request.Builder().url(BuildConfig.LANGUAGE_MODEL_PACK_URL).build();
        try (Response response = downloadClient().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP_" + response.code());
            }
            long total = response.body().contentLength();
            if (total > MAX_DOWNLOAD_BYTES) throw new IOException("DOWNLOAD_LIMIT");
            try (InputStream in = response.body().byteStream();
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(archive))) {
                copy(in, out, total);
            }
        }
    }

    static void downloadAndInstall(File parent, String expectedSha256, Downloader downloader,
                                   Renamer renamer) throws Exception {
        File archive = new File(parent, "language-models." + VERSION + ".zip.part");
        File root = new File(parent, "language-models-" + VERSION);
        File staging = new File(parent, root.getName() + ".partial");
        File previous = new File(parent, root.getName() + ".previous");
        try {
            // A killed process can leave temporary files or a parked root. Restore the
            // latter before retrying; never discard the only surviving install.
            deleteRecursively(archive);
            deleteRecursively(staging);
            if (previous.exists()) {
                if (!root.exists()) {
                    if (!renamer.rename(previous, root)) throw new IOException("RECOVERY");
                } else if (!isReady(root)) {
                    throw new IOException("RECOVERY");
                }
                if (previous.exists()) deleteRecursively(previous);
            }
            if (!isReady(root)) {
                checkInterrupted();
                downloader.download(archive);
                if (!expectedSha256.equalsIgnoreCase(sha256(archive))) throw new IOException("SHA256");
                if (!staging.mkdirs()) throw new IOException("STAGING");
                // Only the pinned, trusted archive reaches extraction.
                unzip(archive, staging);
                for (String required : REQUIRED) {
                    if (!new File(staging, required).isFile()) throw new IOException("MISSING_FILE");
                }
                if (!new File(staging, READY).createNewFile()) throw new IOException("READY_FILE");
                checkInterrupted();
                // A ready root is normally skipped by prefetch. Keep one that became
                // ready during the download too; this is recovery, not an update system.
                if (!isReady(root)) activate(staging, root, previous, renamer);
            }
            removeRetired(parent);
        } finally {
            try { deleteRecursively(archive); }
            finally { deleteRecursively(staging); }
        }
        transientStatus = new DownloadStatus(Phase.READY, 100, "");
        JapaneseReadingEngine.shared().trimMemory();
        LanguageDetectorManager.shared().trimMemory();
        Runnable listener = readyListener;
        if (listener != null) listener.run();
    }

    private static void removeRetired(File parent) {
        for (String version : RETIRED_VERSIONS) {
            try {
                deleteRecursively(new File(parent, "language-models-" + version));
            } catch (IOException ignored) {
                // A leftover old root costs storage only; the next install retries.
            }
        }
    }

    private static void activate(File staging, File root, File previous, Renamer renamer) throws IOException {
        boolean parked = root.exists();
        if (parked && !renamer.rename(root, previous)) throw new IOException("RENAME");
        boolean activated = false;
        try {
            checkInterrupted();
            if (!renamer.rename(staging, root)) throw new IOException("RENAME");
            activated = true;
        } finally {
            if (!activated && parked && !renamer.rename(previous, root)) throw new IOException("RECOVERY");
        }
        if (parked) deleteRecursively(previous);
    }

    private static void unzip(File archive, File destination) throws IOException {
        unzip(archive, destination, MAX_EXPANDED_BYTES, MAX_ENTRIES);
    }

    static void unzip(File archive, File destination, long byteLimit, int entryLimit) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[BUFFER_SIZE];
            long expanded = 0;
            int entries = 0;
            checkInterrupted();
            while ((entry = zip.getNextEntry()) != null) {
                checkInterrupted();
                if (++entries > entryLimit) throw new IOException("ENTRY_LIMIT");
                File output = safeChild(destination, entry.getName());
                if (output == null) throw new IOException("invalid model path");
                if (entry.getSize() > byteLimit - expanded) throw new IOException("EXPANDED_LIMIT");
                if (!entry.isDirectory()) {
                    File parent = output.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("cannot create model directory");
                    }
                }
                try (OutputStream out = entry.isDirectory() ? null
                        : new BufferedOutputStream(new FileOutputStream(output))) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        checkInterrupted();
                        if (read > byteLimit - expanded) throw new IOException("EXPANDED_LIMIT");
                        expanded += read;
                        // Directory payloads also consume the expanded-byte budget.
                        if (out != null) out.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    /**
     * The pack's copy of a model file, or else the classpath resource of that name. The APK carries
     * no model files (see app/build.gradle), so on a device that fallback finds nothing; it exists
     * for JVM tests, where the libraries' own copies and the pack's source folder are on the
     * classpath.
     */
    static InputStream openOrClasspath(String relativePath, String classpathResource) {
        InputStream downloaded = open(relativePath);
        return downloaded != null ? downloaded
                : LanguageModelPack.class.getResourceAsStream(classpathResource);
    }

    private static File root() {
        Context context = appContext;
        return context == null ? null : new File(context.getFilesDir(), "language-models-" + VERSION);
    }

    private static File safeChild(File root, String relativePath) {
        if (relativePath == null || relativePath.isEmpty() || relativePath.contains("\\")) return null;
        File child = new File(root, relativePath);
        try {
            String base = root.getCanonicalPath() + File.separator;
            return child.getCanonicalPath().startsWith(base) ? child : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                checkInterrupted();
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder out = new StringBuilder(64);
        for (byte value : digest.digest()) out.append(String.format("%02x", value));
        return out.toString();
    }

    private static void copy(InputStream in, OutputStream out, long totalBytes) throws IOException {
        copy(in, out, totalBytes, MAX_DOWNLOAD_BYTES, DOWNLOAD_TIMEOUT_NANOS, System::nanoTime);
    }

    static void copy(InputStream in, OutputStream out, long totalBytes, long byteLimit,
                     long timeoutNanos, LongSupplier clock) throws IOException {
        if (totalBytes > byteLimit) throw new IOException("DOWNLOAD_LIMIT");
        long started = clock.getAsLong();
        byte[] buffer = new byte[BUFFER_SIZE];
        long readTotal = 0;
        while (true) {
            checkDownloadTime(started, timeoutNanos, clock);
            int read = in.read(buffer);
            checkDownloadTime(started, timeoutNanos, clock);
            if (read == -1) return;
            if (read > byteLimit - readTotal) throw new IOException("DOWNLOAD_LIMIT");
            out.write(buffer, 0, read);
            readTotal += read;
            if (totalBytes > 0) {
                int progress = (int) (readTotal * 100L / totalBytes);
                transientStatus = new DownloadStatus(Phase.DOWNLOADING, progress, "");
            }
        }
    }

    private static void checkDownloadTime(long started, long timeoutNanos, LongSupplier clock)
            throws InterruptedIOException {
        checkInterrupted();
        if (clock.getAsLong() - started >= timeoutNanos) throw new InterruptedIOException("DOWNLOAD_TIMEOUT");
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("interrupted model install");
    }

    private static String describe(Throwable failure) {
        String code = failure.getClass().getSimpleName();
        String message = failure.getMessage();
        if (message != null && !message.trim().isEmpty()) return code + ": " + message;
        return code;
    }

    private static Set<String> requiredFiles() {
        Set<String> files = new HashSet<>();
        files.add("jmdict/JmdictFurigana.txt.gz");
        files.add("jmdict/JmdictPreferredReadings.txt.gz");
        files.add("tika/langdetect-20260320.bin");
        files.add("kuromoji/characterDefinitions.bin");
        files.add("kuromoji/connectionCosts.bin");
        files.add("kuromoji/doubleArrayTrie.bin");
        files.add("kuromoji/tokenInfoDictionary.bin");
        files.add("kuromoji/tokenInfoFeaturesMap.bin");
        files.add("kuromoji/tokenInfoPartOfSpeechMap.bin");
        files.add("kuromoji/tokenInfoTargetMap.bin");
        files.add("kuromoji/unknownDictionary.bin");
        files.addAll(MandarinReader.packPaths());
        return files;
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        if (!file.delete()) throw new IOException("cannot remove " + file.getName());
    }
}
