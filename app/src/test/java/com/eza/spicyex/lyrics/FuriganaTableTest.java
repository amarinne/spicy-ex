package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The packed {@link FuriganaTable} replaced a {@code HashMap<String, List<FuriganaSegment>>} whose
 * object graph was large enough to exhaust Spotify's heap. These tests reload the real
 * JmdictFurigana resource through the original HashMap logic, kept below as a reference
 * implementation, and require the packed table to answer identically for every one of its 234k
 * keys - including the duplicate-key and malformed-line cases the old loader silently handled.
 */
public class FuriganaTableTest {
    private static Map<String, List<String>> reference;
    private static FuriganaTable packed;

    @BeforeClass
    public static void loadBoth() {
        reference = loadReference();
        packed = JapaneseReadingEngine.shared().jmdictFurigana();
    }

    @Test
    public void resourceActuallyLoaded() {
        // Guards against the whole suite passing vacuously if the resource ever stops shipping.
        assertTrue("reference loader found no entries", reference.size() > 200000);
        assertEquals(reference.size(), packed.size());
    }

    @Test
    public void everyReferenceKeyResolvesIdentically() {
        int checked = 0;
        for (Map.Entry<String, List<String>> entry : reference.entrySet()) {
            List<String> actual = flatten(packed.lookup(entry.getKey()));
            assertNotNull("missing key: " + entry.getKey(), actual);
            assertEquals("segments differ for key: " + entry.getKey(), entry.getValue(), actual);
            checked++;
        }
        assertEquals(reference.size(), checked);
    }

    @Test
    public void footprintStaysWellUnderTheHeapItUsedToExhaust() {
        long bytes = packed.retainedBytes();
        // The HashMap this replaced held ~2.1M objects for ~88MB. Anything approaching that again
        // means someone reintroduced per-entry objects and the OOM comes back with them.
        assertTrue("furigana table grew to " + bytes / (1024 * 1024) + "MB",
                bytes < 24L * 1024 * 1024);
        assertTrue("furigana table suspiciously small: " + bytes, bytes > 8L * 1024 * 1024);
        System.out.println("FuriganaTable: " + packed.size() + " entries in "
                + bytes / (1024 * 1024) + "MB across 8 arrays");
    }

    @Test
    public void missesReturnNullRatherThanEmpty() {
        assertNull(packed.lookup("こんな鍵はない|ない"));
        assertNull(packed.lookup(""));
        assertNull(packed.lookup(null));
    }

    @Test
    public void lookupReturnsAnIndependentCopy() {
        String key = reference.keySet().iterator().next();
        List<SpicyJapaneseChineseProcessor.FuriganaSegment> first = packed.lookup(key);
        assertNotNull(first);
        first.clear();
        assertEquals(reference.get(key), flatten(packed.lookup(key)));
    }

    @Test
    public void builderKeepsTheFirstOfDuplicateKeys() {
        FuriganaTable.Builder builder = new FuriganaTable.Builder();
        builder.add("k", segments("first", 0, 1));
        builder.add("k", segments("second", 0, 1));
        FuriganaTable table = builder.build();

        assertEquals(1, table.size());
        assertEquals(Arrays.asList("0-1:first"), flatten(table.lookup("k")));
    }

    @Test
    public void builderGrowsPastItsInitialCapacity() {
        // The index starts at 4096 slots and the blobs at 65536 chars; push well past both so a
        // rehash and several array doublings are exercised, then verify nothing was lost.
        FuriganaTable.Builder builder = new FuriganaTable.Builder();
        int count = 20000;
        for (int i = 0; i < count; i++) {
            builder.add("key-" + i, segments("reading-" + i, i % 20, i % 20 + 1));
        }
        FuriganaTable table = builder.build();

        assertEquals(count, table.size());
        for (int i = 0; i < count; i++) {
            assertEquals("lost key-" + i,
                    Arrays.asList((i % 20) + "-" + (i % 20 + 1) + ":reading-" + i),
                    flatten(table.lookup("key-" + i)));
        }
        assertNull(table.lookup("key-" + count));
    }

    @Test
    public void emptyBuilderIsUsableRatherThanNull() {
        FuriganaTable table = new FuriganaTable.Builder().build();
        assertTrue(table.isEmpty());
        assertEquals(0, table.size());
        assertNull(table.lookup("anything"));
    }

    @Test
    public void spansTooLargeForTheShortArraysAreDroppedNotTruncated() {
        FuriganaTable.Builder builder = new FuriganaTable.Builder();
        builder.add("huge", segments("r", 0, Short.MAX_VALUE + 1));
        builder.add("fine", segments("r", 0, 1));
        FuriganaTable table = builder.build();

        assertNull(table.lookup("huge"));
        assertEquals(Arrays.asList("0-1:r"), flatten(table.lookup("fine")));
    }

    private static List<SpicyJapaneseChineseProcessor.FuriganaSegment> segments(
            String reading, int start, int end) {
        List<SpicyJapaneseChineseProcessor.FuriganaSegment> out = new ArrayList<>();
        out.add(new SpicyJapaneseChineseProcessor.FuriganaSegment(start, end, reading));
        return out;
    }

    private static List<String> flatten(
            List<SpicyJapaneseChineseProcessor.FuriganaSegment> segments) {
        if (segments == null) return null;
        List<String> out = new ArrayList<>(segments.size());
        for (SpicyJapaneseChineseProcessor.FuriganaSegment segment : segments) {
            out.add(segment.start + "-" + segment.end + ":" + segment.reading);
        }
        return out;
    }

    /** The exact HashMap loader the packed table replaced, flattened to comparable strings. */
    private static Map<String, List<String>> loadReference() {
        HashMap<String, List<String>> out = new HashMap<>();
        try (InputStream in = JapaneseReadingEngine.class
                .getResourceAsStream("/jmdict/JmdictFurigana.txt.gz")) {
            if (in == null) return out;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new java.util.zip.GZIPInputStream(in), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isEmpty() && line.charAt(0) == '﻿') line = line.substring(1);
                    int first = line.indexOf('|');
                    int second = first < 0 ? -1 : line.indexOf('|', first + 1);
                    if (first <= 0 || second <= first + 1 || second >= line.length() - 1) continue;
                    List<SpicyJapaneseChineseProcessor.FuriganaSegment> segments =
                            JapaneseReadingEngine.parseJmdictSpanSpec(line.substring(second + 1));
                    if (segments.isEmpty()) continue;
                    String key = hira(line.substring(0, first)) + "|"
                            + hira(line.substring(first + 1, second));
                    if (out.containsKey(key)) continue;
                    List<String> flattened = new ArrayList<>(segments.size());
                    for (SpicyJapaneseChineseProcessor.FuriganaSegment segment : segments) {
                        flattened.add(segment.start + "-" + segment.end + ":" + segment.reading);
                    }
                    out.put(key, flattened);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String hira(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c >= 'ァ' && c <= 'ヶ' ? (char) (c - 0x60) : c);
        }
        return out.toString();
    }
}
