package com.eza.spicyex.lyrics.language;

import static org.junit.Assert.*;
import static com.eza.spicyex.lyrics.language.LanguageModelPackF6BoundsTest.expectIo;

import android.content.Context;
import android.content.ContextWrapper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LanguageModelPackF6RecoveryTest {
    private static final String[] REQUIRED = withMandarin(
            "jmdict/JmdictFurigana.txt.gz", "jmdict/JmdictPreferredReadings.txt.gz",
            "tika/langdetect-20260320.bin", "kuromoji/characterDefinitions.bin",
            "kuromoji/connectionCosts.bin", "kuromoji/doubleArrayTrie.bin",
            "kuromoji/tokenInfoDictionary.bin", "kuromoji/tokenInfoFeaturesMap.bin",
            "kuromoji/tokenInfoPartOfSpeechMap.bin", "kuromoji/tokenInfoTargetMap.bin",
            "kuromoji/unknownDictionary.bin");
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Map<Field, Object> saved = new LinkedHashMap<>();
    private File parent;
    private File root;
    private File previous;
    private byte[] goodZip;

    @Before public void setUp() throws Exception {
        for (String name : new String[]{"appContext", "readyListener", "transientStatus", "downloadStarted", "upgradeRequested"}) {
            Field field = LanguageModelPack.class.getDeclaredField(name);
            field.setAccessible(true);
            saved.put(field, field.get(null));
            if (name.equals("downloadStarted") || name.equals("upgradeRequested")) field.set(null, false);
            if (name.equals("readyListener")) field.set(null, null);
        }
        parent = temporary.newFolder();
        root = new File(parent, "language-models-v2");
        previous = new File(parent, "language-models-v2.previous");
        LanguageModelPack.attachContext(new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return parent; }
        });
        LanguageModelPack.clearTransientState();
        goodZip = zip(requiredEntries());
    }

    @After public void tearDown() throws Exception {
        Thread.interrupted();
        for (Map.Entry<Field, Object> item : saved.entrySet()) item.getKey().set(null, item.getValue());
    }

    @Test public void successPublishesOnlyCompleteReadyPackAndNotifies() throws Exception {
        AtomicInteger notified = new AtomicInteger();
        LanguageModelPack.setReadyListener(() -> {
            assertTrue(LanguageModelPack.isReady());
            assertNoTemporaryFiles();
            notified.incrementAndGet();
        });
        install(goodZip);
        assertTrue(LanguageModelPack.isReady());
        assertEquals(LanguageModelPack.Phase.READY, LanguageModelPack.status().phase);
        assertEquals(1, notified.get());
        try (InputStream model = LanguageModelPack.open(REQUIRED[0])) {
            assertEquals("model", new String(model.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertNull(LanguageModelPack.open("../outside"));
        LanguageModelPack.setReadyListener(notified::incrementAndGet);
        assertEquals(2, notified.get());
    }

    @Test public void readyVersionIsSkippedAndPreservedInsteadOfReplaced() throws Exception {
        install(goodZip);
        File marker = new File(root, "retained");
        Files.write(marker.toPath(), new byte[]{7});
        LanguageModelPack.downloadAndInstall(parent, hash(goodZip), archive -> fail("ready root must skip download"), File::renameTo);
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        LanguageModelPack.prefetch(queue::add, () -> fail("ready root must skip prefetch"));
        assertTrue(queue.isEmpty());
        assertTrue(marker.isFile());
        assertNoTemporaryFiles();
    }

    @Test public void readyMarkerAndEveryRequiredFileAreStillRequired() throws Exception {
        install(goodZip);
        assertTrue(new File(root, ".ready").delete());
        assertFalse(LanguageModelPack.isReady());
        assertTrue(new File(root, ".ready").createNewFile());
        assertTrue(new File(root, REQUIRED[3]).delete());
        assertFalse(LanguageModelPack.isReady());
        assertNull(LanguageModelPack.open(REQUIRED[0]));
        install(goodZip);
        assertTrue(LanguageModelPack.isReady());
    }

    @Test public void hashMismatchIsRejectedBeforeArchiveIsParsedAndRetryWorks() throws Exception {
        byte[] invalidZip = "invalid archive".getBytes(StandardCharsets.UTF_8);
        IOException failure = assertThrows(IOException.class, () ->
                LanguageModelPack.downloadAndInstall(parent, hash(goodZip), archive -> Files.write(archive.toPath(), invalidZip), File::renameTo));
        assertEquals("SHA256", failure.getMessage());
        assertFailedThenRetry();
    }

    @Test public void downloadWriteFailureCleansPartialAndAllowsRetry() throws Exception {
        expectIo(() -> LanguageModelPack.downloadAndInstall(parent, hash(goodZip), archive -> {
            Files.write(archive.toPath(), new byte[]{1, 2, 3});
            throw new IOException("injected disk write failure");
        }, File::renameTo));
        assertFailedThenRetry();
    }

    @Test public void interruptedWorkCleansPartialPreservesInterruptAndAllowsRetry() throws Exception {
        expectIo(() -> LanguageModelPack.downloadAndInstall(parent, hash(goodZip), archive -> {
            Files.write(archive.toPath(), goodZip);
            Thread.currentThread().interrupt();
        }, File::renameTo));
        assertTrue(Thread.currentThread().isInterrupted());
        assertNoTemporaryFiles();
        Thread.interrupted();
        assertFailedThenRetry();
    }

    @Test public void missingRequiredFileCleansStagingAndAllowsRetry() throws Exception {
        Map<String, byte[]> entries = requiredEntries();
        entries.remove(REQUIRED[0]);
        expectIo(() -> install(zip(entries)));
        assertFailedThenRetry();
    }

    @Test public void extractionDirectoryConflictCleansStagingAndAllowsRetry() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("kuromoji", new byte[]{1});
        entries.putAll(requiredEntries());
        expectIo(() -> install(zip(entries)));
        assertFailedThenRetry();
    }

    @Test public void malformedArchiveCleansStagingAndAllowsRetry() throws Exception {
        // STORED keeps the payload visible so this fixture can corrupt its CRC deterministically.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] payload = "unique-model-payload".getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(payload);
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            ZipEntry entry = new ZipEntry("model");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(payload.length);
            entry.setCompressedSize(payload.length);
            entry.setCrc(crc.getValue());
            out.putNextEntry(entry);
            out.write(payload);
            out.closeEntry();
        }
        byte[] corrupt = bytes.toByteArray();
        corrupt[30 + "model".length()] ^= 1;
        expectIo(() -> install(corrupt));
        assertFailedThenRetry();
    }

    @Test public void escapingFileAndBackslashEntriesCleanStagingAndAllowRetry() throws Exception {
        for (String name : new String[]{"../outside", "kuromoji/../../outside", "bad\\path"}) {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put(name, new byte[]{1});
            expectIo(() -> install(zip(entries)));
            assertFalse(new File(parent, "outside").exists());
            assertNoTemporaryFiles();
        }
        assertFailedThenRetry();
    }

    @Test public void archiveCannotSupplyTheReadyMarkerAndRetryWorks() throws Exception {
        Map<String, byte[]> entries = requiredEntries();
        entries.put(".ready", new byte[0]);
        expectIo(() -> install(zip(entries)));
        assertFailedThenRetry();
    }

    @Test public void archiveEntryLimitCleansStagingAndAllowsRetry() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < 129; i++) entries.put("directory-" + i + "/", new byte[0]);
        entries.putAll(requiredEntries());
        IOException failure = assertThrows(IOException.class, () -> install(zip(entries)));
        assertEquals("ENTRY_LIMIT", failure.getMessage());
        assertFailedThenRetry();
    }

    @Test public void failedStagingCreationCleansDownloadAndAllowsRetry() throws Exception {
        expectIo(() -> LanguageModelPack.downloadAndInstall(parent, hash(goodZip), archive -> {
            downloadGood(archive);
            assertTrue(new File(parent, "language-models-v2.partial").mkdir());
        }, File::renameTo));
        assertFailedThenRetry();
    }

    @Test public void failedActivationRestoresReachableIncompleteRootAndRetryWorks() throws Exception {
        File marker = incompleteRoot();
        expectIo(() -> LanguageModelPack.downloadAndInstall(parent, hash(goodZip), this::downloadGood,
                (from, to) -> !from.getName().endsWith(".partial") && from.renameTo(to)));
        assertTrue(marker.isFile());
        assertFalse(previous.exists());
        assertFailedThenRetry();
    }

    @Test public void firstInstallRenameFailureCleansStagingAndRetryWorks() throws Exception {
        expectIo(() -> LanguageModelPack.downloadAndInstall(parent, hash(goodZip), this::downloadGood, (from, to) -> false));
        assertFalse(root.exists());
        assertFailedThenRetry();
    }

    @Test public void failedParkingPreservesIncompleteRootAndRetryWorks() throws Exception {
        File marker = incompleteRoot();
        expectIo(() -> LanguageModelPack.downloadAndInstall(parent, hash(goodZip), this::downloadGood,
                (from, to) -> !from.equals(root) && from.renameTo(to)));
        assertTrue(marker.isFile());
        assertFalse(previous.exists());
        assertFailedThenRetry();
    }

    @Test public void failedRollbackRetainsPreviousRootAndNextAttemptRecoversIt() throws Exception {
        incompleteRoot();
        expectIo(() -> LanguageModelPack.downloadAndInstall(parent, hash(goodZip), this::downloadGood,
                (from, to) -> from.equals(root) && from.renameTo(to)));
        assertFalse(root.exists());
        assertTrue(new File(previous, "retained").isFile());
        assertNoTemporaryFiles();
        // A failed recovery move must retain the only surviving root for another retry.
        expectIo(() -> LanguageModelPack.downloadAndInstall(parent, hash(goodZip), this::downloadGood,
                (from, to) -> false));
        assertTrue(new File(previous, "retained").isFile());
        assertNoTemporaryFiles();
        AtomicInteger recovery = new AtomicInteger();
        LanguageModelPack.downloadAndInstall(parent, hash(goodZip), this::downloadGood, (from, to) -> {
            if (from.equals(previous)) recovery.incrementAndGet();
            return from.renameTo(to);
        });
        assertTrue(recovery.get() > 0);
        assertTrue(LanguageModelPack.isReady());
        assertFalse(previous.exists());
        assertNoTemporaryFiles();
    }

    @Test public void interruptionAfterParkingRollsBackAndAllowsRetry() throws Exception {
        File marker = incompleteRoot();
        expectIo(() -> LanguageModelPack.downloadAndInstall(parent, hash(goodZip), this::downloadGood, (from, to) -> {
            boolean moved = from.renameTo(to);
            if (from.equals(root)) Thread.currentThread().interrupt();
            return moved;
        }));
        assertTrue(Thread.currentThread().isInterrupted());
        assertTrue(marker.isFile());
        assertFalse(previous.exists());
        Thread.interrupted();
        assertFailedThenRetry();
    }

    @Test public void staleInterruptedInstallIsRecoveredBeforeSubsequentDownload() throws Exception {
        incompleteRoot();
        assertTrue(root.renameTo(previous));
        File stale = new File(parent, "language-models-v2.partial");
        assertTrue(stale.mkdir());
        Files.write(new File(stale, "old").toPath(), new byte[]{3});
        Files.write(new File(parent, "language-models.v2.zip.part").toPath(), new byte[]{4});
        LanguageModelPack.downloadAndInstall(parent, hash(goodZip), archive -> {
            assertTrue(new File(root, "retained").isFile());
            assertFalse(stale.exists());
            assertFalse(archive.exists());
            downloadGood(archive);
        }, File::renameTo);
        assertTrue(LanguageModelPack.isReady());
        assertFalse(previous.exists());
        assertNoTemporaryFiles();
    }

    @Test public void readyRootThatAppearsDuringDownloadIsRetained() throws Exception {
        LanguageModelPack.downloadAndInstall(parent, hash(goodZip), archive -> {
            downloadGood(archive);
            for (String name : REQUIRED) {
                File file = new File(root, name);
                assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
                Files.write(file.toPath(), new byte[]{9});
            }
            assertTrue(new File(root, ".ready").createNewFile());
        }, (from, to) -> { fail("ready install must not be replaced"); return false; });
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(new File(root, REQUIRED[0]).toPath()));
        assertTrue(LanguageModelPack.isReady());
        assertNoTemporaryFiles();
    }

    @Test public void singleFlightSurvivesTransientResetAndReleasesOwnershipForRetry() throws Exception {
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        AtomicInteger attempts = new AtomicInteger();
        LanguageModelPack.Installation install = () -> {
            attempts.incrementAndGet();
            LanguageModelPack.downloadAndInstall(parent, hash(goodZip), archive -> {
                Files.write(archive.toPath(), new byte[]{1});
                throw new IOException("injected download failure");
            }, File::renameTo);
        };
        LanguageModelPack.prefetch(queue::add, install);
        LanguageModelPack.clearTransientState();
        LanguageModelPack.prefetch(queue::add, install);
        assertEquals(1, queue.size());
        queue.remove().run();
        assertEquals(1, attempts.get());
        assertEquals(LanguageModelPack.Phase.ERROR, LanguageModelPack.status().phase);
        assertNoTemporaryFiles();
        AtomicInteger notified = new AtomicInteger();
        LanguageModelPack.setReadyListener(notified::incrementAndGet);
        LanguageModelPack.prefetch(queue::add, () -> install(goodZip));
        assertEquals(1, queue.size());
        queue.remove().run();
        assertTrue(LanguageModelPack.isReady());
        assertEquals(1, notified.get());
        assertNoTemporaryFiles();
    }

    private File incompleteRoot() throws Exception {
        assertTrue(root.mkdir());
        File marker = new File(root, "retained");
        Files.write(marker.toPath(), new byte[]{7});
        assertFalse(LanguageModelPack.isReady());
        return marker;
    }

    private void assertFailedThenRetry() throws Exception {
        assertFalse(LanguageModelPack.isReady());
        assertNoTemporaryFiles();
        install(goodZip);
        assertTrue(LanguageModelPack.isReady());
        assertFalse(previous.exists());
        assertNoTemporaryFiles();
    }

    private void assertNoTemporaryFiles() {
        assertFalse(new File(parent, "language-models.v2.zip.part").exists());
        assertFalse(new File(parent, "language-models-v2.partial").exists());
    }

    @Test public void installRemovesRetiredV1Root() throws Exception {
        File retired = new File(parent, "language-models-v1");
        assertTrue(new File(retired, "jmdict").mkdirs());
        Files.write(new File(retired, "jmdict/JmdictFurigana.txt.gz").toPath(), new byte[]{1});
        install(goodZip);
        assertTrue(LanguageModelPack.isReady());
        assertFalse(retired.exists());
    }

    @Test public void onlyAReadyRetiredPackCountsForTheUpgrade() throws Exception {
        assertFalse(LanguageModelPack.hasRetiredPack(parent));
        File retired = new File(parent, "language-models-v1");
        assertTrue(retired.mkdirs());
        assertFalse(LanguageModelPack.hasRetiredPack(parent));
        assertTrue(new File(retired, ".ready").createNewFile());
        assertTrue(LanguageModelPack.hasRetiredPack(parent));
        assertFalse(LanguageModelPack.hasRetiredPack(null));
    }

    @Test public void packWithoutMandarinFilesIsNotReady() throws Exception {
        Map<String, byte[]> entries = requiredEntries();
        entries.remove("mandarin-g2pm/manifest.json");
        byte[] bytes = zip(entries);
        expectIo(() -> install(bytes));
        assertFalse(LanguageModelPack.isReady());
    }

    private static String[] withMandarin(String... japaneseAndDetector) {
        java.util.List<String> all = new java.util.ArrayList<>(java.util.Arrays.asList(japaneseAndDetector));
        all.addAll(com.eza.spicyex.lyrics.language.mandarin.MandarinReader.packPaths());
        return all.toArray(new String[0]);
    }

    private void downloadGood(File archive) throws IOException { Files.write(archive.toPath(), goodZip); }

    private void install(byte[] bytes) throws Exception {
        LanguageModelPack.downloadAndInstall(parent, hash(bytes), archive -> Files.write(archive.toPath(), bytes), File::renameTo);
    }

    private Map<String, byte[]> requiredEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String name : REQUIRED) entries.put(name, "model".getBytes(StandardCharsets.UTF_8));
        return entries;
    }

    private byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(item.getKey()));
                out.write(item.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private String hash(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x", b));
        return result.toString();
    }
}
