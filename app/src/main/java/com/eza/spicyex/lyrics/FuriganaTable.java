package com.eza.spicyex.lyrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JmdictFurigana as flat primitive arrays instead of a
 * {@code HashMap<String, List<FuriganaSegment>>}.
 *
 * <p>The dictionary holds about 234,000 entries over about 586,000 reading segments. As an object
 * graph that is roughly 2.1 million live objects and ~88MB - inside Spotify's process, whose heap
 * stops growing at 512MB, so the tail of that allocation is what produced "Failed to allocate a
 * 1048592 byte allocation" while fullscreen lyrics were loading. The same data as eight arrays is
 * ~14MB, and costs the GC eight objects to trace rather than millions, which is why collections
 * were freeing only ~1MB a pass before.
 *
 * <p>Keys are concatenated into one char blob and found through an open-addressed index, so a
 * lookup allocates nothing until it hits, and a miss allocates nothing at all.
 */
final class FuriganaTable {
    private final char[] keyBlob;
    private final int[] keyOffset;      // entryCount + 1
    private final char[] readingBlob;
    private final int[] readingOffset;  // segmentCount + 1
    private final short[] spanStart;    // segmentCount
    private final short[] spanEnd;      // segmentCount
    private final int[] entryOffset;    // entryCount + 1, indexes the segment arrays
    private final int[] index;          // power-of-two slots, entry number + 1, 0 = empty
    private final int entryCount;

    private FuriganaTable(char[] keyBlob, int[] keyOffset, char[] readingBlob,
                          int[] readingOffset, short[] spanStart, short[] spanEnd,
                          int[] entryOffset, int[] index, int entryCount) {
        this.keyBlob = keyBlob;
        this.keyOffset = keyOffset;
        this.readingBlob = readingBlob;
        this.readingOffset = readingOffset;
        this.spanStart = spanStart;
        this.spanEnd = spanEnd;
        this.entryOffset = entryOffset;
        this.index = index;
        this.entryCount = entryCount;
    }

    boolean isEmpty() {
        return entryCount == 0;
    }

    int size() {
        return entryCount;
    }

    /** Exact retained bytes, since the table is nothing but these eight arrays. */
    long retainedBytes() {
        return (long) keyBlob.length * 2 + (long) readingBlob.length * 2
                + (long) spanStart.length * 2 + (long) spanEnd.length * 2
                + (long) keyOffset.length * 4 + (long) readingOffset.length * 4
                + (long) entryOffset.length * 4 + (long) index.length * 4;
    }

    /** Segments for {@code key}, or null when the key is absent. */
    List<SpicyJapaneseChineseProcessor.FuriganaSegment> lookup(String key) {
        int entry = find(key);
        if (entry < 0) return null;
        int from = entryOffset[entry];
        int to = entryOffset[entry + 1];
        ArrayList<SpicyJapaneseChineseProcessor.FuriganaSegment> out = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) {
            out.add(new SpicyJapaneseChineseProcessor.FuriganaSegment(
                    spanStart[i], spanEnd[i],
                    new String(readingBlob, readingOffset[i],
                            readingOffset[i + 1] - readingOffset[i])));
        }
        return out;
    }

    private int find(String key) {
        if (key == null || entryCount == 0) return -1;
        int mask = index.length - 1;
        int slot = smear(hash(key)) & mask;
        // Load factor is capped below 1, so an empty slot always terminates the probe.
        while (true) {
            int stored = index[slot];
            if (stored == 0) return -1;
            int entry = stored - 1;
            if (keyEquals(entry, key)) return entry;
            slot = (slot + 1) & mask;
        }
    }

    private boolean keyEquals(int entry, String key) {
        int from = keyOffset[entry];
        int length = keyOffset[entry + 1] - from;
        if (length != key.length()) return false;
        for (int i = 0; i < length; i++) {
            if (keyBlob[from + i] != key.charAt(i)) return false;
        }
        return true;
    }

    private static int hash(CharSequence key) {
        int h = 0;
        for (int i = 0, n = key.length(); i < n; i++) h = 31 * h + key.charAt(i);
        return h;
    }

    // Kana keys share long prefixes, so the raw hash clusters badly in the low bits that linear
    // probing depends on. This is HashMap's spreader, for the same reason.
    private static int smear(int h) {
        return h ^ (h >>> 16);
    }

    static final class Builder {
        private char[] keys = new char[1 << 16];
        private int keyLength;
        private int[] keyOffset = new int[1 << 12];
        private char[] readings = new char[1 << 16];
        private int readingLength;
        private int[] readingOffset = new int[1 << 12];
        private short[] spanStart = new short[1 << 12];
        private short[] spanEnd = new short[1 << 12];
        private int segmentCount;
        private int[] entryOffset = new int[1 << 12];
        private int entryCount;
        private int[] index = new int[1 << 12];

        void add(String key, List<SpicyJapaneseChineseProcessor.FuriganaSegment> segments) {
            if (key == null || key.isEmpty() || segments == null || segments.isEmpty()) return;
            // Spans are character offsets inside one word, so short is ample; an entry that
            // somehow does not fit is dropped rather than silently truncated into a wrong span.
            for (int i = 0; i < segments.size(); i++) {
                SpicyJapaneseChineseProcessor.FuriganaSegment segment = segments.get(i);
                if (segment == null) return;
                if (segment.start < 0 || segment.start > Short.MAX_VALUE) return;
                if (segment.end < 0 || segment.end > Short.MAX_VALUE) return;
            }
            if (probe(key) >= 0) return;
            // Grow before the entry is recorded. Rehashing afterwards would reinsert it from
            // keyOffset and then insert() would add a second slot for the same entry, leaking a
            // slot per growth until the table has no empty slot left to terminate a probe.
            if ((entryCount + 1) * 2 >= index.length) rehash(index.length << 1);

            keys = ensure(keys, keyLength + key.length());
            key.getChars(0, key.length(), keys, keyLength);
            keyLength += key.length();

            for (int i = 0; i < segments.size(); i++) {
                SpicyJapaneseChineseProcessor.FuriganaSegment segment = segments.get(i);
                String text = segment.reading;
                readings = ensure(readings, readingLength + text.length());
                text.getChars(0, text.length(), readings, readingLength);
                readingOffset = ensure(readingOffset, segmentCount + 2);
                readingOffset[segmentCount] = readingLength;
                readingLength += text.length();
                spanStart = ensure(spanStart, segmentCount + 1);
                spanEnd = ensure(spanEnd, segmentCount + 1);
                spanStart[segmentCount] = (short) segment.start;
                spanEnd[segmentCount] = (short) segment.end;
                segmentCount++;
            }

            keyOffset = ensure(keyOffset, entryCount + 2);
            entryOffset = ensure(entryOffset, entryCount + 2);
            keyOffset[entryCount] = keyLength - key.length();
            entryOffset[entryCount] = segmentCount - segments.size();
            entryCount++;
            keyOffset[entryCount] = keyLength;
            entryOffset[entryCount] = segmentCount;
            readingOffset[segmentCount] = readingLength;

            insert(index, key, entryCount);
        }

        FuriganaTable build() {
            if (entryCount == 0) {
                return new FuriganaTable(new char[0], new int[1], new char[0], new int[1],
                        new short[0], new short[0], new int[1], new int[1], 0);
            }
            return new FuriganaTable(
                    Arrays.copyOf(keys, keyLength),
                    Arrays.copyOf(keyOffset, entryCount + 1),
                    Arrays.copyOf(readings, readingLength),
                    Arrays.copyOf(readingOffset, segmentCount + 1),
                    Arrays.copyOf(spanStart, segmentCount),
                    Arrays.copyOf(spanEnd, segmentCount),
                    Arrays.copyOf(entryOffset, entryCount + 1),
                    index, entryCount);
        }

        /** Entry number holding {@code key}, or -1. Mirrors {@link FuriganaTable#find}. */
        private int probe(String key) {
            int mask = index.length - 1;
            int slot = smear(hash(key)) & mask;
            while (true) {
                int stored = index[slot];
                if (stored == 0) return -1;
                int entry = stored - 1;
                if (sameKey(entry, key)) return entry;
                slot = (slot + 1) & mask;
            }
        }

        private boolean sameKey(int entry, String key) {
            int from = keyOffset[entry];
            int length = keyOffset[entry + 1] - from;
            if (length != key.length()) return false;
            for (int i = 0; i < length; i++) {
                if (keys[from + i] != key.charAt(i)) return false;
            }
            return true;
        }

        private void rehash(int capacity) {
            int[] grown = new int[capacity];
            int mask = capacity - 1;
            for (int entry = 0; entry < entryCount; entry++) {
                int from = keyOffset[entry];
                int to = keyOffset[entry + 1];
                int h = 0;
                for (int i = from; i < to; i++) h = 31 * h + keys[i];
                int slot = smear(h) & mask;
                while (grown[slot] != 0) slot = (slot + 1) & mask;
                grown[slot] = entry + 1;
            }
            index = grown;
        }

        private void insert(int[] table, String key, int entryNumber) {
            int mask = table.length - 1;
            int slot = smear(hash(key)) & mask;
            while (table[slot] != 0) slot = (slot + 1) & mask;
            table[slot] = entryNumber;
        }

        private static char[] ensure(char[] array, int needed) {
            if (needed <= array.length) return array;
            int capacity = array.length;
            while (capacity < needed) capacity <<= 1;
            return Arrays.copyOf(array, capacity);
        }

        private static int[] ensure(int[] array, int needed) {
            if (needed <= array.length) return array;
            int capacity = array.length;
            while (capacity < needed) capacity <<= 1;
            return Arrays.copyOf(array, capacity);
        }

        private static short[] ensure(short[] array, int needed) {
            if (needed <= array.length) return array;
            int capacity = array.length;
            while (capacity < needed) capacity <<= 1;
            return Arrays.copyOf(array, capacity);
        }
    }
}
