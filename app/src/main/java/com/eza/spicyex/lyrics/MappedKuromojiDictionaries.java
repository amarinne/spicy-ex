package com.eza.spicyex.lyrics;

import android.content.Context;

import com.atilika.kuromoji.dict.ConnectionCosts;
import com.atilika.kuromoji.util.ResourceResolver;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.FileChannel;

/**
 * Serves kuromoji's largest dictionary table from a memory-mapped file instead of the Java heap.
 *
 * <p>kuromoji-unidic is about 150MB once expanded, and every part of it is read through
 * {@code ByteBufferIO}, which calls {@link ByteBuffer#allocate} - a heap buffer. Inside Spotify's
 * process, whose heap stops growing at 512MB, that is the single largest resident cost the module
 * carries, and it is entirely read-only data that never changes after load.
 *
 * <p>connectionCosts.bin is 71.5MB of that 150MB - just under half, in one table, and the one
 * dictionary kuromoji exposes a public constructor for ({@code ConnectionCosts(int, ShortBuffer)}).
 * Extracted once and mapped, it costs no Java heap at all: the pages are clean and file-backed, so
 * the kernel can drop and re-read them under pressure without the process noticing, which is
 * strictly better behaviour than the all-or-nothing release this module does by hand.
 *
 * <p>Mapping is also faster than the path it replaces, which inflates 46MB of compressed jar
 * entries and copies the result into a fresh heap array. The extraction is paid once, in the
 * background warm-up, and every later process start just maps.
 *
 * <p>Anything that goes wrong here returns null and the caller falls back to kuromoji's own
 * loader, so the worst case is the memory profile we had before.
 */
final class MappedKuromojiDictionaries {
    private static final String CACHE_DIR = "kuromoji-mmap";
    /** Bump to invalidate extracted copies if the bundled dictionary ever changes. */
    private static final String STAMP = ".v1";

    private MappedKuromojiDictionaries() {
    }

    /**
     * @param resolver the builder's own resolver, so the resource is looked up exactly where
     *                 kuromoji looks for it - the dictionary lives in the unidic package, not
     *                 beside {@link ConnectionCosts}, so a class-relative lookup here finds
     *                 nothing.
     * @return a ConnectionCosts backed by mapped file pages, or null to use kuromoji's own loader.
     */
    static ConnectionCosts connectionCosts(Context context, ResourceResolver resolver) {
        if (context == null || resolver == null) return null;
        try {
            File file = ensureExtracted(context, resolver,
                    ConnectionCosts.CONNECTION_COSTS_FILENAME);
            if (file == null) return null;
            return map(file);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Maps an already-extracted connectionCosts.bin. Separate from {@link #connectionCosts} only
     * so the header parsing can be tested without an Android Context.
     *
     * @return the mapped table, or null if the file does not look like one.
     */
    static ConnectionCosts map(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r");
             FileChannel channel = raf.getChannel()) {
            MappedByteBuffer mapped =
                    channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            // Layout, from ConnectionCosts.read + ByteBufferIO.read: the table's own size, then
            // the byte length, then the shorts. Both readers are DataInput, which is big-endian -
            // the same order a ByteBuffer uses by default.
            int size = mapped.getInt();
            int byteLength = mapped.getInt();
            if (size <= 0 || byteLength <= 0 || byteLength > mapped.remaining()) return null;
            ByteBuffer costs = mapped.slice();
            costs.limit(byteLength);
            return new ConnectionCosts(size, costs.asShortBuffer());
        }
    }

    /** Copies one dictionary resource out of the APK, once, and returns the file. */
    private static File ensureExtracted(Context context, ResourceResolver resolver, String name)
            throws IOException {
        File dir = new File(context.getCacheDir(), CACHE_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        File target = new File(dir, name + STAMP);
        if (target.isFile() && target.length() > 0) return target;

        File partial = new File(dir, name + STAMP + ".partial");
        try (InputStream in = resolver.resolve(name)) {
            if (in == null) return null;
            try (OutputStream out = new FileOutputStream(partial)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            }
        }
        // Rename last, so a process killed mid-extraction never leaves a half file that the next
        // start would happily map and read garbage out of.
        if (!partial.renameTo(target)) {
            partial.delete();
            return null;
        }
        return target;
    }
}
