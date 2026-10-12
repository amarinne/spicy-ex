package com.eza.spicyex.lyrics.language;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Phrase-aware display projection using the desktop OpenCC s2tw and t2s dictionaries.
 * Matching, segmentation, and IDS handling are adapted from opencc-js (MIT).
 * See the bundled opencc licenses and provenance for the Apache-2.0 dictionary data. */
public final class ChineseCharacterConverter {
    private ChineseCharacterConverter() {}

    public static String convert(String text, ChineseCharacterForm form) {
        return project(text, form).text;
    }

    /** Convert once with full phrase context, then project canonical code-point spans. */
    public static Projection project(String text, ChineseCharacterForm form) {
        Projection source = Projection.identity(text == null ? "" : text);
        if (form == null || form == ChineseCharacterForm.ORIGINAL || source.text.isEmpty()) return source;
        Projection normalized = source.through(Common.NORMALIZATION);
        if (form == ChineseCharacterForm.SIMPLIFIED) return normalized.through(Simplified.CONVERSION);
        int[] input = codePoints(normalized.text);
        int[] boundaries = new int[input.length + 1];
        StringBuilder output = new StringBuilder(normalized.text.length());
        int outputCp = 0;
        for (int start = 0; start < input.length;) {
            Match phrase = Traditional.CONVERSION.match(input, start, input.length, true);
            int end;
            if (phrase != null) {
                end = phrase.end;
            } else {
                // OpenCC keeps each contiguous unmatched region as one segment.
                end = unmatchedEnd(input, start, input.length);
                while (end < input.length
                        && Traditional.CONVERSION.match(input, end, input.length, true) == null) {
                    end = unmatchedEnd(input, end, input.length);
                }
            }
            Projection segment = Projection.identity(new String(input, start, end - start))
                    .through(Traditional.CONVERSION).through(Traditional.VARIANTS);
            output.append(segment.text);
            for (int i = start; i <= end; i++) boundaries[i] = outputCp + segment.mapBoundary(i - start);
            outputCp += segment.targetOffsets.length - 1;
            start = end;
        }
        return normalized.then(output.toString(), boundaries);
    }

    /** Immutable source-to-display map. All arguments and results use Unicode code points. */
    public static final class Projection {
        public final String text;
        private final int[] sourceBoundaries;
        private final int[] targetOffsets;

        private Projection(String text, int[] sourceBoundaries) {
            this.text = text;
            this.sourceBoundaries = sourceBoundaries;
            this.targetOffsets = utf16Offsets(text);
        }

        public int sourceCodePointCount() { return sourceBoundaries.length - 1; }

        public int mapBoundary(int sourceCp) {
            if (sourceCp < 0 || sourceCp >= sourceBoundaries.length) {
                throw new IndexOutOfBoundsException("Invalid source code-point boundary: " + sourceCp);
            }
            return sourceBoundaries[sourceCp];
        }

        public String slice(int startCp, int endCp) {
            if (endCp < startCp) throw new IndexOutOfBoundsException("Reversed source range");
            return text.substring(targetOffsets[mapBoundary(startCp)], targetOffsets[mapBoundary(endCp)]);
        }

        static Projection identity(String text) {
            int count = text.codePointCount(0, text.length());
            int[] boundaries = new int[count + 1];
            for (int i = 0; i <= count; i++) boundaries[i] = i;
            return new Projection(text, boundaries);
        }

        Projection then(String target, int[] nextBoundaries) {
            int[] composed = new int[sourceBoundaries.length];
            for (int i = 0; i < composed.length; i++) composed[i] = nextBoundaries[sourceBoundaries[i]];
            return new Projection(target, composed);
        }

        Projection through(Trie dictionary) {
            int[] input = codePoints(text);
            int[] boundaries = new int[input.length + 1];
            StringBuilder output = new StringBuilder(text.length());
            int outputCp = 0;
            for (int start = 0; start < input.length;) {
                Match match = dictionary.match(input, start, input.length, false);
                int end = match == null ? unmatchedEnd(input, start, input.length) : match.end;
                String replacement = match == null ? new String(input, start, end - start) : match.value;
                int replacementCp = replacement.codePointCount(0, replacement.length());
                for (int i = start; i <= end; i++) {
                    // Equal-length substitutions retain each timed character's ownership.
                    boundaries[i] = outputCp
                            + (int) ((long) (i - start) * replacementCp / (end - start));
                }
                output.append(replacement);
                outputCp += replacementCp;
                start = end;
            }
            return then(output.toString(), boundaries);
        }
    }

    private static final class Common {
        static final Trie NORMALIZATION = readGroup(0, "CJK_Compatibility_Ideographs");
    }

    private static final class Simplified {
        static final Trie CONVERSION = readGroup(0, "TSPhrases", "TSCharacters");
    }

    private static final class Traditional {
        // The phrase terminals also form the segmentation trie, without duplicating its storage.
        static final Trie CONVERSION = readGroup(2,
                "STPhrases", "STPhrases_GeneratedFromRegionalPhrases", "STCharacters");
        static final Trie VARIANTS = readGroup(0, "TWVariantsPhrases", "TWVariants");
    }

    private static Trie readGroup(int segmentationDictionaries, String... dictionaries) {
        TrieBuilder builder = new TrieBuilder();
        // OpenCC loads groups in reverse; the first listed dictionary wins duplicate keys.
        for (int i = dictionaries.length - 1; i >= 0; i--) {
            String path = "/opencc/" + dictionaries[i] + ".txt";
            try (InputStream stream = ChineseCharacterConverter.class.getResourceAsStream(path)) {
                if (stream == null) throw new IOException("Missing bundled dictionary " + path);
                builder.read(new InputStreamReader(stream, StandardCharsets.UTF_8), i < segmentationDictionaries);
            } catch (IOException error) {
                throw new IllegalStateException("Cannot load Chinese character dictionary", error);
            }
        }
        return builder.build();
    }

    /** Compact immutable trie. Builder maps are discarded after loading a dictionary group. */
    static final class Trie {
        private final int[] firstEdge, edgeCounts, edgeCodePoints, edgeTargets;
        private final String[] values;
        private final boolean[] segmentationTerminals;

        Trie(int[] firstEdge, int[] edgeCounts, int[] edgeCodePoints, int[] edgeTargets,
                String[] values, boolean[] segmentationTerminals) {
            this.firstEdge = firstEdge;
            this.edgeCounts = edgeCounts;
            this.edgeCodePoints = edgeCodePoints;
            this.edgeTargets = edgeTargets;
            this.values = values;
            this.segmentationTerminals = segmentationTerminals;
        }

        Match match(int[] input, int start, int limit, boolean segmentationOnly) {
            int node = 0, matchedEnd = -1;
            String value = null;
            for (int i = start; i < limit; i++) {
                int edge = Arrays.binarySearch(edgeCodePoints,
                        firstEdge[node], firstEdge[node] + edgeCounts[node], input[i]);
                if (edge < 0) break;
                node = edgeTargets[edge];
                if (values[node] != null && (!segmentationOnly || segmentationTerminals[node])) {
                    matchedEnd = i + 1;
                    value = values[node];
                }
            }
            return matchedEnd < 0 ? null : new Match(matchedEnd, value);
        }
    }

    static final class TrieBuilder {
        private final ArrayList<Node> nodes = new ArrayList<>();
        TrieBuilder() { nodes.add(new Node(0)); }

        void read(Reader reader, boolean segmentation) throws IOException {
            BufferedReader lines = new BufferedReader(reader);
            for (String line; (line = lines.readLine()) != null;) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int tab = line.indexOf('\t');
                if (tab <= 0 || line.indexOf('\t', tab + 1) >= 0) {
                    throw new IOException("Invalid Chinese character dictionary entry");
                }
                Node node = nodes.get(0);
                for (int cp : codePoints(line.substring(0, tab))) {
                    if (node.children == null) node.children = new HashMap<>();
                    Node child = node.children.get(cp);
                    if (child == null) {
                        child = new Node(nodes.size());
                        nodes.add(child);
                        node.children.put(cp, child);
                    }
                    node = child;
                }
                node.value = line.substring(tab + 1);
                node.segmentation |= segmentation;
            }
        }

        Trie build() {
            int count = nodes.size();
            int[] first = new int[count], sizes = new int[count];
            int[] points = new int[count - 1], targets = new int[count - 1];
            String[] values = new String[count];
            boolean[] segmentation = new boolean[count];
            int edge = 0;
            for (Node node : nodes) {
                first[node.id] = edge;
                values[node.id] = node.value;
                segmentation[node.id] = node.segmentation;
                if (node.children == null) continue;
                int[] keys = new int[node.children.size()];
                int keyIndex = 0;
                for (int key : node.children.keySet()) keys[keyIndex++] = key;
                Arrays.sort(keys);
                sizes[node.id] = keys.length;
                for (int key : keys) {
                    points[edge] = key;
                    targets[edge++] = node.children.get(key).id;
                }
            }
            return new Trie(first, sizes, points, targets, values, segmentation);
        }
    }

    private static final class Node {
        final int id;
        Map<Integer, Node> children;
        String value;
        boolean segmentation;
        Node(int id) { this.id = id; }
    }

    private static final class Match {
        final int end;
        final String value;
        Match(int end, String value) { this.end = end; this.value = value; }
    }

    private static int[] codePoints(String text) {
        int[] points = new int[text.codePointCount(0, text.length())];
        for (int cp = 0, offset = 0; cp < points.length; cp++) {
            points[cp] = text.codePointAt(offset);
            offset += Character.charCount(points[cp]);
        }
        return points;
    }

    private static int[] utf16Offsets(String text) {
        int[] offsets = new int[text.codePointCount(0, text.length()) + 1];
        for (int cp = 0, offset = 0; cp < offsets.length - 1; cp++) {
            offsets[cp] = offset;
            offset += Character.charCount(text.codePointAt(offset));
            offsets[cp + 1] = offset;
        }
        return offsets;
    }

    private static int unmatchedEnd(int[] input, int start, int limit) {
        int idsEnd = ideographicDescriptionEnd(input, start, limit);
        return idsEnd > start ? idsEnd : start + 1;
    }

    private static int ideographicDescriptionEnd(int[] input, int start, int limit) {
        int cp = input[start];
        int arity = cp >= 0x2ff0 && cp <= 0x2ff1 ? 2
                : cp >= 0x2ff2 && cp <= 0x2ff3 ? 3
                : cp >= 0x2ff4 && cp <= 0x2fff ? 2 : 0;
        if (arity == 0) return 0;
        int end = start + 1;
        for (int child = 0; child < arity; child++) {
            if (end >= limit) return 0;
            int childEnd = ideographicDescriptionEnd(input, end, limit);
            end = childEnd > end ? childEnd : end + 1;
        }
        return end;
    }
}
