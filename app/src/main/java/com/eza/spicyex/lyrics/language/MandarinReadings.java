package com.eza.spicyex.lyrics.language;

import android.os.Looper;

import com.eza.spicyex.lyrics.language.mandarin.MandarinReader;
import com.eza.spicyex.lyrics.language.mandarin.MandarinReadingLine;
import com.eza.spicyex.xposed.XpLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;

/**
 * Process-wide owner of the Mandarin eza式 reader.
 *
 * <p>The g2pM data ships in the downloaded language model pack, beside the Japanese dictionaries.
 * Without the pack, {@link #available()} is false and eza式 Mandarin falls back to Standard Pinyin
 * (see {@link RomanizationOptions}). The data is parsed once, off the main thread. Chinese conversion paths already run
 * on the Sound/Lyrics workers; a main-thread caller that arrives before the load finishes gets no
 * reading for that pass instead of blocking the UI. There is no second dictionary and no fallback
 * reader: a missing or modified pack leaves Mandarin readings unavailable and logs the error.
 */
public final class MandarinReadings {
    private static final String TAG = "[SpicyMandarinReader]";
    private static final Object LOCK = new Object();
    private static final MandarinReader.PackSource SOURCE = new MandarinReader.PackSource() {
        @Override
        public byte[] manifest() throws IOException {
            return packFile("manifest.json");
        }

        @Override
        public byte[] file(String fileName) throws IOException {
            return packFile(fileName);
        }
    };

    private static volatile MandarinReader reader;
    private static volatile String failure;
    private static volatile CountDownLatch ready;
    private static volatile boolean started;

    private MandarinReadings() {
    }

    /** Start the one background load if it has not started. Safe to call repeatedly. */
    public static void warm() {
        ensureStarted();
    }

    public static boolean isReady() {
        return reader != null;
    }

    /**
     * Whether the reader's data is installed. On a device this is the language model pack; JVM
     * tests find the pack's source folder on the classpath.
     */
    public static boolean available() {
        if (LanguageModelPack.isReady()) return true;
        return MandarinReadings.class.getResource("/" + MandarinReader.PACK_DIRECTORY + "manifest.json") != null;
    }

    public static String failure() {
        return failure;
    }

    public static MandarinReadingLine read(String text) {
        MandarinReader current = reader();
        return current == null ? null : current.read(text);
    }

    /** The shared reader, or {@code null} when the pack is unavailable or still loading on main. */
    public static MandarinReader reader() {
        MandarinReader current = reader;
        if (current != null) return current;
        ensureStarted();
        CountDownLatch latch = ready;
        if (latch == null || isMainThread()) return null;
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
        return reader;
    }

    public static MandarinReadingLine readIfReady(String text) {
        MandarinReader current = reader;
        return current == null ? null : current.read(text);
    }

    private static void ensureStarted() {
        // Without the data a load can only fail. Wait for the pack so the first load succeeds.
        if (started || !available()) return;
        synchronized (LOCK) {
            if (started) return;
            started = true;
            ready = new CountDownLatch(1);
            Thread loader = new Thread(MandarinReadings::load, "spicy-mandarin-reader");
            loader.setDaemon(true);
            loader.start();
        }
    }

    private static void load() {
        try {
            reader = MandarinReader.load(SOURCE);
        } catch (Throwable error) {
            failure = error.toString();
            XpLog.log(TAG + " Mandarin reader unavailable: " + error, error);
        } finally {
            CountDownLatch latch = ready;
            if (latch != null) latch.countDown();
        }
    }

    private static byte[] packFile(String name) throws IOException {
        String path = MandarinReader.PACK_DIRECTORY + name;
        InputStream stream = LanguageModelPack.openOrClasspath(path, "/" + path);
        if (stream == null) throw new IOException("Missing Mandarin g2pM file: " + name);
        try (InputStream in = stream;
             ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static boolean isMainThread() {
        try {
            return Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper();
        } catch (Throwable unavailableInUnitTest) {
            return false;
        }
    }
}
