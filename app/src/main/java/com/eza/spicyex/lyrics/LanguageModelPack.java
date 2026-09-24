package com.eza.spicyex.lyrics;

import android.content.Context;

import com.eza.spicyex.BuildConfig;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private static final String VERSION = "v1";
    private static final String READY = ".ready";
    private static final int BUFFER_SIZE = 1 << 16;
    private static final Set<String> REQUIRED = requiredFiles();
    private static final ExecutorService DOWNLOADS = Executors.newSingleThreadExecutor();
    private static volatile Context appContext;
    private static volatile boolean downloadStarted;
    private static volatile Runnable readyListener;
    private static volatile DownloadStatus transientStatus = new DownloadStatus(Phase.IDLE, 0, "");

    private LanguageModelPack() {
    }

    public static void attachContext(Context context) {
        if (context == null) return;
        Context applicationContext = context.getApplicationContext();
        appContext = applicationContext == null ? context : applicationContext;
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
        downloadStarted = false;
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

    public static void deleteDownload() {
        deleteRecursively(root());
        clearTransientState();
    }

    public static boolean isReady() {
        File root = root();
        if (root == null || !new File(root, READY).isFile()) return false;
        for (String required : REQUIRED) {
            if (!new File(root, required).isFile()) return false;
        }
        return true;
    }

    /** Size of the installed files, excluding filesystem allocation overhead. */
    public static long installedSizeBytes() {
        return isReady() ? sizeBytes(root()) : 0;
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

    private static void prefetch() {
        if (downloadStarted || isReady() || BuildConfig.LANGUAGE_MODEL_PACK_URL.isEmpty()) return;
        synchronized (LanguageModelPack.class) {
            if (downloadStarted || isReady()) return;
            downloadStarted = true;
        }
        DOWNLOADS.execute(() -> {
            try {
                downloadAndInstall();
            } catch (Throwable failure) {
                transientStatus = new DownloadStatus(Phase.ERROR, 0, describe(failure));
            } finally {
                downloadStarted = false;
            }
        });
    }

    private static void downloadAndInstall() throws Exception {
        Context context = appContext;
        File parent = context == null ? null : context.getFilesDir();
        if (parent == null) {
            transientStatus = new DownloadStatus(Phase.ERROR, 0, "NO_CONTEXT");
            return;
        }
        File archive = new File(parent, "language-models." + VERSION + ".zip.part");
        OkHttpClient client = new OkHttpClient.Builder().build();
        Request request = new Request.Builder().url(BuildConfig.LANGUAGE_MODEL_PACK_URL).build();
        Response response = client.newCall(request).execute();
        try {
            if (!response.isSuccessful() || response.body() == null) {
                transientStatus = new DownloadStatus(Phase.ERROR, 0, "HTTP_" + response.code());
                return;
            }
            long total = response.body().contentLength();
            try (InputStream in = response.body().byteStream();
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(archive))) {
                copy(in, out, total);
            }
        } finally {
            response.close();
        }
        if (!BuildConfig.LANGUAGE_MODEL_PACK_SHA256.equalsIgnoreCase(sha256(archive))) {
            archive.delete();
            transientStatus = new DownloadStatus(Phase.ERROR, 0, "SHA256");
            return;
        }

        File root = new File(parent, "language-models-" + VERSION);
        File staging = new File(parent, root.getName() + ".partial");
        deleteRecursively(staging);
        if (!staging.mkdirs()) {
            transientStatus = new DownloadStatus(Phase.ERROR, 0, "STAGING");
            return;
        }
        unzip(archive, staging);
        archive.delete();
        for (String required : REQUIRED) {
            if (!new File(staging, required).isFile()) {
                deleteRecursively(staging);
                transientStatus = new DownloadStatus(Phase.ERROR, 0, "MISSING_FILE");
                return;
            }
        }
        if (!new File(staging, READY).createNewFile()) {
            deleteRecursively(staging);
            transientStatus = new DownloadStatus(Phase.ERROR, 0, "READY_FILE");
            return;
        }
        deleteRecursively(root);
        if (!staging.renameTo(root)) {
            deleteRecursively(staging);
            transientStatus = new DownloadStatus(Phase.ERROR, 0, "RENAME");
            return;
        }
        transientStatus = new DownloadStatus(Phase.READY, 100, "");
        JapaneseReadingEngine.shared().trimMemory();
        LanguageDetectorManager.shared().trimMemory();
        Runnable listener = readyListener;
        if (listener != null) listener.run();
    }

    private static void unzip(File archive, File destination) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[BUFFER_SIZE];
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                File output = safeChild(destination, entry.getName());
                if (output == null) throw new IOException("invalid model path");
                File parent = output.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("cannot create model directory");
                }
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) out.write(buffer, 0, read);
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
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder out = new StringBuilder(64);
        for (byte value : digest.digest()) out.append(String.format("%02x", value));
        return out.toString();
    }

    private static void copy(InputStream in, OutputStream out, long totalBytes) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long readTotal = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            readTotal += read;
            if (totalBytes > 0) {
                int progress = (int) (readTotal * 100L / totalBytes);
                transientStatus = new DownloadStatus(Phase.DOWNLOADING, progress, "");
            }
        }
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
        return files;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }

    private static long sizeBytes(File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();
        File[] children = file.listFiles();
        if (children == null) return 0;
        long total = 0;
        for (File child : children) total += sizeBytes(child);
        return total;
    }
}
