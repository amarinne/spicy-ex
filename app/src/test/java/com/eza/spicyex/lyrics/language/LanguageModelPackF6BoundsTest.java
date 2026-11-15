package com.eza.spicyex.lyrics.language;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LanguageModelPackF6BoundsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void unknownLengthDownloadStopsBeforeWritingBeyondBudget() throws Exception {
        final long[] remaining = {128L * 1024 * 1024 + 1};
        final long[] written = {0};
        InputStream endless = new InputStream() {
            @Override public int read() { throw new AssertionError("bulk reads expected"); }
            @Override public int read(byte[] b, int off, int len) {
                if (remaining[0] == 0) return -1;
                int n = (int) Math.min(remaining[0], len);
                remaining[0] -= n;
                return n;
            }
        };
        OutputStream sink = new OutputStream() {
            @Override public void write(int b) { written[0]++; }
            @Override public void write(byte[] b, int off, int len) { written[0] += len; }
        };
        expectIo(() -> invoke("copy", new Class<?>[]{InputStream.class, OutputStream.class, long.class},
                endless, sink, -1L));
        assertTrue(written[0] <= 128L * 1024 * 1024);
    }

    @Test public void interruptedDownloadStopsWithoutWriting() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        Thread.currentThread().interrupt();
        try {
            expectIo(() -> invoke("copy", new Class<?>[]{InputStream.class, OutputStream.class, long.class},
                    new ByteArrayInputStream(new byte[]{1}), sink, -1L));
            assertEquals(0, sink.size());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void directoryEntriesCannotEscapeDestination() throws Exception {
        File zip = archive("../outside/");
        File destination = temporary.newFolder();
        expectIo(() -> invoke("unzip", new Class<?>[]{File.class, File.class}, zip, destination));
    }

    @Test public void directoryEntriesCountTowardArchiveBudget() throws Exception {
        String[] entries = new String[129];
        for (int i = 0; i < entries.length; i++) entries[i] = "directory-" + i + "/";
        File zip = archive(entries);
        File destination = temporary.newFolder();
        expectIo(() -> invoke("unzip", new Class<?>[]{File.class, File.class}, zip, destination));
    }

    @Test public void slowUnknownLengthStreamExpiresWithoutWaitingForReadTimeout() throws Exception {
        long[] now = {0};
        int[] reads = {0};
        InputStream slow = new ByteArrayInputStream(new byte[100]) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                reads[0]++;
                now[0] += 6;
                return super.read(b, off, 1);
            }
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        expectIo(() -> LanguageModelPack.copy(slow, out, -1, 100, 10, () -> now[0]));
        assertEquals(2, reads[0]);
        assertEquals(1, out.size());
    }

    @Test public void stalledEofAndZeroByteReadsCannotAvoidDeadline() throws Exception {
        for (int response : new int[]{0, -1}) {
            long[] now = {0};
            InputStream stalled = new InputStream() {
                @Override public int read() { return response; }
                private int reads;
                @Override public int read(byte[] b, int off, int len) {
                    if (++reads > 2) throw new AssertionError("deadline did not stop stalled reads");
                    now[0] = 10;
                    return response;
                }
            };
            expectIo(() -> LanguageModelPack.copy(stalled, new ByteArrayOutputStream(), -1, 100, 10, () -> now[0]));
        }
    }

    @Test public void networkCallDeadlineAlsoCoversBlockedReadsAndRedirects() {
        assertEquals(600_000, LanguageModelPack.downloadClient().callTimeoutMillis());
    }

    @Test public void declaredAndUnknownLengthDownloadsEnforceExactBoundary() throws Exception {
        for (long declared : new long[]{-1, 8}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            LanguageModelPack.copy(new ByteArrayInputStream(new byte[8]), out, declared, 8, 10, () -> 0);
            assertEquals(8, out.size());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        expectIo(() -> LanguageModelPack.copy(new ByteArrayInputStream(new byte[9]), out, -1, 8, 10, () -> 0));
        assertEquals(0, out.size());
        InputStream unread = new InputStream() { @Override public int read() { throw new AssertionError("must reject declared size first"); } };
        expectIo(() -> LanguageModelPack.copy(unread, out, 9, 8, 10, () -> 0));
    }

    @Test public void expandedBudgetCountsActualBytesAcrossEntries() throws Exception {
        File zip = payloadArchive(new String[]{"a", "b"}, new byte[][]{new byte[6], new byte[5]});
        File destination = temporary.newFolder();
        expectIo(() -> LanguageModelPack.unzip(zip, destination, 10, 2));
        assertTrue(Files.size(new File(destination, "a").toPath())
                + Files.size(new File(destination, "b").toPath()) <= 10);
        File exact = temporary.newFolder();
        LanguageModelPack.unzip(zip, exact, 11, 2);
        assertEquals(11, Files.size(new File(exact, "a").toPath()) + Files.size(new File(exact, "b").toPath()));
    }

    @Test public void directoryPayloadAlsoConsumesExpandedBudget() throws Exception {
        File zip = payloadArchive(new String[]{"a/", "b"}, new byte[][]{new byte[6], new byte[5]});
        expectIo(() -> LanguageModelPack.unzip(zip, temporary.newFolder(), 10, 2));
    }

    @Test public void entryBudgetIncludesFilesAndDirectoriesAtExactBoundary() throws Exception {
        File zip = payloadArchive(new String[]{"a/", "b"}, new byte[][]{new byte[0], new byte[0]});
        LanguageModelPack.unzip(zip, temporary.newFolder(), 0, 2);
        expectIo(() -> LanguageModelPack.unzip(zip, temporary.newFolder(), 0, 1));
    }

    private File payloadArchive(String[] names, byte[][] payloads) throws Exception {
        File zip = temporary.newFile();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
            for (int i = 0; i < names.length; i++) {
                out.putNextEntry(new ZipEntry(names[i])); out.write(payloads[i]); out.closeEntry();
            }
        }
        return zip;
    }

    private File archive(String... names) throws Exception {
        File zip = temporary.newFile();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
            for (String name : names) { out.putNextEntry(new ZipEntry(name)); out.closeEntry(); }
        }
        return zip;
    }

    static void invoke(String name, Class<?>[] signature, Object... args) throws Exception {
        Method method = LanguageModelPack.class.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        try { method.invoke(null, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            throw failure;
        }
    }

    interface Action { void run() throws Exception; }
    static void expectIo(Action action) throws Exception {
        try { action.run(); fail("expected bounded or invalid pack rejection"); }
        catch (IOException expected) { }
    }
}
