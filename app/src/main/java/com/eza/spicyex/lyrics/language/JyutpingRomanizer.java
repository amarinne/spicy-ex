package com.eza.spicyex.lyrics.language;

import java.util.HashMap;
import java.util.Map;

/** Android port/adaptation of Spicy to-jyutping path. Uses packaged to-jyutping-derived char map. */
final class JyutpingRomanizer {
    static final class Segment {
        final String reading;
        final int codePoint;
        /** Source code points covered: 1 for a character, the phrase length for a phrase. */
        final int length;
        Segment(String reading, int codePoint) { this(reading, codePoint, 1); }
        Segment(String reading, int codePoint, int length) {
            this.reading = reading; this.codePoint = codePoint; this.length = length;
        }
    }
    static final class TrieWalk {
        final java.util.List<Segment> segments;
        TrieWalk(java.util.List<Segment> segments) { this.segments = segments; }
    }
    private static final class TrieNode {
        final Map<Integer, TrieNode> children = new HashMap<>();
        String reading;
    }

    private static volatile Map<Integer, String> map;
    private static volatile TrieNode trie;

    /**
     * Cantonese revision 2 (cjk-labs {@code docs/eza-cantonese-revision-2.md}, policy
     * {@code eza-2026-11-15.3/adapter-2026-11-18.5}). Part of the romanization cache key.
     */
    static final String REVISION = "adapter-2026-11-18.5";
    /** R2a: interjections and sentence-final particles keep the bare vowel. */
    private static final String PARTICLES = "啊呀吖哦喔噢哎唉欸誒噯嗄";

    private JyutpingRomanizer() {}

    static String romanize(String text) {
        if (text == null || text.trim().isEmpty()) return text;
        StringBuilder out = new StringBuilder();
        boolean lastWasReading = false;
        for (Segment segment : walk(text).segments) {
            if (segment.reading != null) {
                if (out.length() > 0 && lastWasReading) out.append(' ');
                out.append(segment.reading);
                lastWasReading = true;
            } else {
                if (out.length() > 0 && lastWasReading && shouldSeparateFallback(segment.codePoint)) out.append(' ');
                out.appendCodePoint(segment.codePoint);
                lastWasReading = false;
            }
        }
        return out.toString().replaceAll("[ \\t]+", " ").trim();
    }

    /**
     * Reads the source text (R1): a passage without traditional-only characters is looked up
     * through its character-preserving traditional form, because the lexicon ranks traditional
     * readings (simplified 离 would read ci1, 離 reads lei4). Passthrough segments keep the
     * source code points; phrase segments carry {@code codePoint == -1}.
     */
    static TrieWalk walk(String text) {
        return walk(text, true);
    }

    /** {@code ngInitial == false} returns the lexicon readings without the R2 ng insertion. */
    static TrieWalk walk(String text, boolean ngInitial) {
        java.util.ArrayList<Segment> segments = new java.util.ArrayList<>();
        if (text == null) return new TrieWalk(segments);
        int[] source = text.codePoints().toArray();
        int[] lookup = ChineseCharacterConverter.cantoneseLookupForm(text).codePoints().toArray();
        if (lookup.length != source.length) lookup = source;
        TrieNode localTrie = trie();
        Map<Integer, String> local = map();
        for (int i = 0; i < lookup.length;) {
            int phraseEnd = matchPhrase(localTrie, lookup, i);
            if (phraseEnd > i) {
                String reading = phraseReading(localTrie, lookup, i, phraseEnd);
                segments.add(new Segment(ngInitial ? preferNgInitial(reading, source, i) : reading, -1,
                        phraseEnd - i));
                i = phraseEnd;
                continue;
            }
            String value = local.get(lookup[i]);
            segments.add(value == null || value.trim().isEmpty()
                    ? new Segment(null, source[i])
                    : new Segment(ngInitial ? preferNgInitial(value, source, i) : value, source[i]));
            i++;
        }
        return new TrieWalk(segments);
    }

    /**
     * R2: a null-initial syllable starting with a, o or u takes the initial ng (愛 ngoi3,
     * 屋 nguk1), except interjections and sentence-final particles (R2a). Syllables align with
     * the source characters from {@code start}; the lexicon gives one syllable per character.
     */
    private static String preferNgInitial(String reading, int[] source, int start) {
        String[] syllables = reading.trim().split("\\s+");
        StringBuilder out = new StringBuilder(reading.length() + 4);
        for (int k = 0; k < syllables.length; k++) {
            String syllable = syllables[k];
            int index = start + k;
            boolean particle = index < source.length && PARTICLES.indexOf(source[index]) >= 0;
            if (k > 0) out.append(' ');
            if (!particle && !syllable.isEmpty() && "aou".indexOf(syllable.charAt(0)) >= 0) out.append("ng");
            out.append(syllable);
        }
        return out.toString();
    }

    /** The context-free lexicon reading of one character, without R2; null when absent. */
    static String characterReading(int codePoint) {
        String value = map().get(codePoint);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static boolean shouldSeparateFallback(int cp) {
        return cp < 128 && Character.isLetterOrDigit(cp);
    }

    /** Longest phrase from {@code start}; returns its end index, or {@code start} when none. */
    private static int matchPhrase(TrieNode root, int[] text, int start) {
        TrieNode node = root;
        int end = start;
        for (int i = start; i < text.length; i++) {
            node = node.children.get(text[i]);
            if (node == null) break;
            if (node.reading != null) end = i + 1;
        }
        return end;
    }

    private static String phraseReading(TrieNode root, int[] text, int start, int end) {
        TrieNode node = root;
        for (int i = start; i < end; i++) node = node.children.get(text[i]);
        return node.reading;
    }

    private static Map<Integer, String> map() {
        Map<Integer, String> local = map;
        if (local != null) return local;
        synchronized (JyutpingRomanizer.class) {
            if (map == null) map = buildMap();
            return map;
        }
    }

    private static TrieNode trie() {
        TrieNode local = trie;
        if (local != null) return local;
        synchronized (JyutpingRomanizer.class) {
            if (trie == null) trie = buildTrie();
            return trie;
        }
    }

    private static Map<Integer, String> buildMap() {
        HashMap<Integer, String> out = new HashMap<>(30000);
        String data = JyutpingCharMapData.data();
        String[] rows = data.split("\\n");
        for (String row : rows) {
            if (row == null || row.length() < 3) continue;
            int eq = row.indexOf('=');
            if (eq <= 0 || eq >= row.length() - 1) continue;
            int cp = row.codePointAt(0);
            out.put(cp, row.substring(eq + 1));
        }
        return out;
    }

    private static TrieNode buildTrie() {
        TrieNode root = new TrieNode();
        String[] rows = JyutpingTrieData.data().split("\\n");
        for (String row : rows) {
            if (row == null || row.trim().isEmpty()) continue;
            int eq = row.indexOf('=');
            if (eq <= 0 || eq >= row.length() - 1) continue;
            String phrase = row.substring(0, eq);
            String reading = row.substring(eq + 1).trim();
            if (reading.isEmpty()) continue;
            TrieNode node = root;
            for (int i = 0; i < phrase.length();) {
                int cp = phrase.codePointAt(i);
                TrieNode next = node.children.get(cp);
                if (next == null) {
                    next = new TrieNode();
                    node.children.put(cp, next);
                }
                node = next;
                i += Character.charCount(cp);
            }
            node.reading = reading;
        }
        return root;
    }
}
