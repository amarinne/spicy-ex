package com.eza.spicyex.lyrics;

import java.util.HashMap;
import java.util.Map;

/** Android port/adaptation of Spicy to-jyutping path. Uses packaged to-jyutping-derived char map. */
final class JyutpingRomanizer {
    private static final class TrieNode {
        final Map<Integer, TrieNode> children = new HashMap<>();
        String reading;
    }

    private static volatile Map<Integer, String> map;
    private static volatile TrieNode trie;

    private JyutpingRomanizer() {}

    static String romanize(String text) {
        if (text == null || text.trim().isEmpty()) return text;
        Map<Integer, String> local = map();
        TrieNode localTrie = trie();
        StringBuilder out = new StringBuilder();
        boolean lastWasReading = false;
        for (int i = 0; i < text.length();) {
            PhraseMatch phrase = matchPhrase(localTrie, text, i);
            if (phrase != null) {
                if (out.length() > 0 && lastWasReading) out.append(' ');
                out.append(phrase.reading);
                lastWasReading = true;
                i = phrase.endIndex;
                continue;
            }
            int cp = text.codePointAt(i);
            String value = local.get(cp);
            if (value != null && !value.trim().isEmpty()) {
                if (out.length() > 0 && lastWasReading) out.append(' ');
                out.append(value);
                lastWasReading = true;
            } else {
                if (out.length() > 0 && lastWasReading && shouldSeparateFallback(cp)) out.append(' ');
                out.appendCodePoint(cp);
                lastWasReading = false;
            }
            i += Character.charCount(cp);
        }
        return out.toString().replaceAll("[ \\t]+", " ").trim();
    }

    private static boolean shouldSeparateFallback(int cp) {
        return cp < 128 && Character.isLetterOrDigit(cp);
    }

    private static final class PhraseMatch {
        final int endIndex;
        final String reading;

        PhraseMatch(int endIndex, String reading) {
            this.endIndex = endIndex;
            this.reading = reading;
        }
    }

    private static PhraseMatch matchPhrase(TrieNode root, String text, int startIndex) {
        TrieNode node = root;
        String reading = null;
        int readingEnd = startIndex;
        for (int i = startIndex; i < text.length();) {
            int cp = text.codePointAt(i);
            node = node.children.get(cp);
            if (node == null) break;
            i += Character.charCount(cp);
            if (node.reading != null) {
                reading = node.reading;
                readingEnd = i;
            }
        }
        return reading == null ? null : new PhraseMatch(readingEnd, reading);
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
