package com.eza.spicyex.lyrics.language.mandarin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Portable jieba DAG + HMM segmentation, kept from the existing verified reader.
 *
 * <p>The segmenter supplies feet (tokens), part-of-speech tags, and contained search subwords used
 * by the frozen eza式 sandhi and spelling layers. It has no reading authority: syllables come from
 * {@link MandarinG2pM} and may carry evidence ranges that cross these tokens.
 */
public final class MandarinSegmenter {
    /** One segmented surface inside a Han run. */
    public static final class Surface {
        public final String text;
        public final int start;
        public final int end;
        public final String tag;
        /** Contained search-subword ranges, relative to the surface. */
        public final List<int[]> subwords;

        Surface(String text, int start, int end, String tag, List<int[]> subwords) {
            this.text = text;
            this.start = start;
            this.end = end;
            this.tag = tag;
            this.subwords = subwords;
        }
    }

    private static final class Dict {
        final double frequency;
        final String tag;

        Dict(double frequency, String tag) {
            this.frequency = frequency;
            this.tag = tag;
        }
    }

    private final Map<String, Dict> dictionary = new HashMap<>(400_000);
    private final double[] hmmStart = new double[4];
    private final double[][] transition = new double[4][4];
    private final List<Map<String, Double>> emission = new ArrayList<>(4);
    private double total;
    private int maxWord = 1;

    private MandarinSegmenter(String jiebaText, String hmmText) {
        parseJieba(jiebaText);
        parseHmm(hmmText);
        if (!(total > 0) || dictionary.isEmpty()) {
            throw new IllegalArgumentException("Incomplete Mandarin segmentation data.");
        }
    }

    public int dictionarySize() {
        return dictionary.size();
    }

    /** Segment one Han run, using its normalized shadow code points. */
    public List<Surface> segment(String[] points) {
        double[] score = new double[points.length + 1];
        int[] next = new int[points.length];
        double log = Math.log(total);
        for (int start = points.length - 1; start >= 0; start--) {
            double best = Double.NEGATIVE_INFINITY;
            int stop = start + 1;
            boolean found = false;
            for (int end = start + 1; end <= Math.min(points.length, start + maxWord); end++) {
                Dict entry = dictionary.get(join(points, start, end));
                if (entry == null) continue;
                found = true;
                double candidate = Math.log(entry.frequency == 0 ? 1 : entry.frequency)
                        - log + score[end];
                if (candidate >= best) {
                    best = candidate;
                    stop = end;
                }
            }
            score[start] = found ? best : -log + score[start + 1];
            next[start] = stop;
        }
        List<String> raw = new ArrayList<>();
        for (int index = 0; index < points.length; index = next[index]) {
            raw.add(join(points, index, next[index]));
        }
        List<String> grouped = new ArrayList<>();
        List<String> single = new ArrayList<>();
        for (String word : raw) {
            if (MandarinG2pM.points(word).length == 1) {
                single.add(word);
            } else {
                flushSingles(grouped, single);
                grouped.add(word);
            }
        }
        flushSingles(grouped, single);

        List<Surface> out = new ArrayList<>(grouped.size());
        int offset = 0;
        for (String surface : grouped) {
            int size = MandarinG2pM.points(surface).length;
            Dict entry = dictionary.get(surface);
            List<int[]> subwords = new ArrayList<>();
            for (int width : new int[] {2, 3}) {
                if (size <= width) continue;
                for (int index = 0; index + width <= size; index++) {
                    String part = join(points, offset + index, offset + index + width);
                    Dict found = dictionary.get(part);
                    if (found != null && found.frequency > 0) {
                        subwords.add(new int[] {index, index + width});
                    }
                }
            }
            out.add(new Surface(surface, offset, offset + size,
                    entry == null ? "x" : entry.tag, subwords));
            offset += size;
        }
        return out;
    }

    private void flushSingles(List<String> grouped, List<String> single) {
        String word = String.join("", single);
        if (single.size() > 1 && !dictionary.containsKey(word)) {
            grouped.addAll(hmmCut(single.toArray(new String[0])));
        } else {
            grouped.addAll(single);
        }
        single.clear();
    }

    private List<String> hmmCut(String[] points) {
        if (points.length < 2) return Arrays.asList(points.clone());
        int[][] allowed = {{1, 3}, {0, 2}, {2, 0}, {3, 1}};
        double minimum = -3.14e100;
        double[][] scores = new double[points.length][4];
        int[][] previous = new int[points.length][4];
        for (int state = 0; state < 4; state++) {
            scores[0][state] = hmmStart[state] + emission.get(state).getOrDefault(points[0], minimum);
        }
        for (int at = 1; at < points.length; at++) {
            for (int state = 0; state < 4; state++) {
                double best = Double.NEGATIVE_INFINITY;
                int from = 0;
                for (int candidate : allowed[state]) {
                    double value = scores[at - 1][candidate] + transition[candidate][state]
                            + emission.get(state).getOrDefault(points[at], minimum);
                    if (value >= best) {
                        best = value;
                        from = candidate;
                    }
                }
                scores[at][state] = best;
                previous[at][state] = from;
            }
        }
        int[] path = new int[points.length];
        int state = scores[points.length - 1][1] > scores[points.length - 1][3] ? 1 : 3;
        for (int at = points.length - 1; at >= 0; at--) {
            path[at] = state;
            state = previous[at][state];
        }
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int at = 0; at < points.length; at++) {
            if (path[at] == 1 || path[at] == 3) {
                out.add(join(points, start, at + 1));
                start = at + 1;
            }
        }
        if (start < points.length) out.add(join(points, start, points.length));
        return out;
    }

    private static String join(String[] points, int start, int end) {
        if (start >= end) return "";
        if (end - start == 1) return points[start];
        StringBuilder out = new StringBuilder();
        for (int index = start; index < end; index++) out.append(points[index]);
        return out.toString();
    }

    private void parseJieba(String text) {
        Map<String, String> tags = new HashMap<>();
        for (String row : lines(text)) {
            int first = row.indexOf('\t');
            int second = first < 0 ? -1 : row.indexOf('\t', first + 1);
            if (first <= 0 || second <= first + 1 || second == row.length() - 1) {
                throw new IllegalArgumentException("Invalid jieba TSV.");
            }
            String word = row.substring(0, first);
            String frequency = row.substring(first + 1, second);
            String tag = row.substring(second + 1);
            double value;
            try {
                value = Double.parseDouble(frequency);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Invalid jieba frequency.", invalid);
            }
            String canonicalTag = tags.get(tag);
            if (canonicalTag == null) {
                tags.put(tag, tag);
                canonicalTag = tag;
            }
            Dict previous = dictionary.put(word, new Dict(value, canonicalTag));
            total += value - (previous == null ? 0 : previous.frequency);
            maxWord = Math.max(maxWord, MandarinG2pM.points(word).length);
        }
    }

    private void parseHmm(String text) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        JsonArray start = root.getAsJsonArray("start");
        JsonArray matrix = root.getAsJsonArray("transition");
        JsonArray emissions = root.getAsJsonArray("emission");
        if (start == null || matrix == null || emissions == null || start.size() != 4
                || matrix.size() != 4 || emissions.size() != 4) {
            throw new IllegalArgumentException("Invalid jieba HMM.");
        }
        for (int index = 0; index < 4; index++) {
            hmmStart[index] = start.get(index).getAsDouble();
            JsonArray row = matrix.get(index).getAsJsonArray();
            if (row.size() != 4) throw new IllegalArgumentException("Invalid jieba HMM.");
            for (int column = 0; column < 4; column++) {
                transition[index][column] = row.get(column).getAsDouble();
            }
            Map<String, Double> values = new HashMap<>(50_000);
            for (Map.Entry<String, JsonElement> entry : emissions.get(index).getAsJsonObject().entrySet()) {
                if (MandarinG2pM.points(entry.getKey()).length != 1) {
                    throw new IllegalArgumentException("Invalid HMM emission.");
                }
                values.put(entry.getKey(), entry.getValue().getAsDouble());
            }
            emission.add(values);
        }
    }

    private static String[] lines(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value != '\n') continue;
            int end = index;
            if (end > start && text.charAt(end - 1) == '\r') end--;
            if (end > start) out.add(text.substring(start, end));
            start = index + 1;
        }
        if (start < text.length()) {
            int end = text.length();
            if (end > start && text.charAt(end - 1) == '\r') end--;
            if (end > start) out.add(text.substring(start, end));
        }
        return out.toArray(new String[0]);
    }

    public static MandarinSegmenter load(byte[] jiebaTsv, byte[] hmmJson) {
        return new MandarinSegmenter(new String(jiebaTsv, java.nio.charset.StandardCharsets.UTF_8),
                new String(hmmJson, java.nio.charset.StandardCharsets.UTF_8));
    }
}
