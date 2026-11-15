package com.eza.spicyex.lyrics.language.mandarin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Portable Mandarin reader over one shared cjk-labs g2pM pack.
 *
 * <p>The pack is the pinned TypeScript export: {@code g2pM} manifest and model tensors plus the
 * pinyin-data character table, the phrase table, the digest trigger set, and the OpenCC 1:1
 * traditional-to-simplified shadow. Jieba's dictionary and HMM are appended to the same manifest
 * for feet, tags, and search subwords only.
 *
 * <p>{@link #read(String)} returns the canonical eza式 reading line: g2pM model-first readings,
 * one syllable per Han code point, evidence ranges that may cross segmentation tokens. Standard
 * Pinyin does not come from this reader; it stays on the plain pinyin4j package path.
 *
 * <p>Construction verifies every file against the manifest. The files arrive in the downloaded
 * language model pack; there is no fallback dictionary and no second reading provider.
 */
public final class MandarinReader {
    /** Layer 1 identity of eza式 revision 3; it never applies sandhi or chooses display marks. */
    public static final String REVISION = "mandarin-g2pm-reader-2026-11-15.3";
    public static final String ASSET_VERSION_PREFIX = "mandarin-g2pm@";

    /** Pack directory that holds the manifest and every member file. */
    public static final String PACK_DIRECTORY = "mandarin-g2pm/";
    private static final String MANIFEST = "manifest.json";
    private static final String[] FILES = {
            "model.f32", "model-vocab.json", "classes.txt", "characters.tsv", "digest.json",
            "phrases.tsv", "t2s.tsv", "jieba.tsv", "hmm.json"
    };

    /** Pack-relative paths of the manifest and every member the reader loads. */
    public static List<String> packPaths() {
        List<String> paths = new ArrayList<>(FILES.length + 1);
        paths.add(PACK_DIRECTORY + MANIFEST);
        for (String name : FILES) paths.add(PACK_DIRECTORY + name);
        return paths;
    }

    /** A verified pack: the g2pM manifest and raw member bytes. */
    public interface PackSource {
        byte[] manifest() throws IOException;

        byte[] file(String fileName) throws IOException;
    }

    private final MandarinG2pM engine;
    private final MandarinSegmenter segmenter;
    private final String assetVersion;

    private MandarinReader(MandarinG2pM engine, MandarinSegmenter segmenter, String assetVersion) {
        this.engine = engine;
        this.segmenter = segmenter;
        this.assetVersion = assetVersion;
    }

    public String assetVersion() {
        return assetVersion;
    }

    public MandarinG2pM engine() {
        return engine;
    }

    /** Canonical eza式 reading line with model-first g2pM readings. */
    public MandarinReadingLine read(String text) {
        String input = text == null ? "" : text;
        MandarinG2pM.Line line = engine.read(input);
        return build(input, line);
    }

    private MandarinReadingLine build(String text, MandarinG2pM.Line line) {
        String[] source = MandarinG2pM.points(text);
        String[] shaped = new String[source.length];
        for (int index = 0; index < source.length; index++) {
            shaped[index] = engine.normalize(source[index]);
        }
        // Whole-token phrase readings decide alwaysNeutral: a word whose only reading is neutral
        // at a position keeps a preceding third tone (姐姐, 椅子, 耳朵); one that can also give a
        // full tone does not (想想).
        java.util.Map<String, List<String[]>> wordReadings = new HashMap<>();
        for (MandarinG2pM.PhraseMatch match : engine.lookupPhrases(text)) {
            if (match.end - match.start < 2) continue;
            String key = match.start + ":" + match.end;
            List<String[]> rows = wordReadings.get(key);
            if (rows == null) {
                rows = new ArrayList<>();
                wordReadings.put(key, rows);
            }
            for (String reading : match.readings) rows.add(reading.split(" "));
        }
        List<MandarinToken> tokens = new ArrayList<>();
        for (int start = 0; start < shaped.length; ) {
            String tokenKind = kind(shaped[start]);
            int end = start + 1;
            while (end < shaped.length && kind(shaped[end]).equals(tokenKind)) end++;
            if ("han".equals(tokenKind)) {
                List<MandarinSegmenter.Surface> surfaces = segmenter.segment(slice(shaped, start, end));
                for (MandarinSegmenter.Surface surface : surfaces) {
                    int tokenStart = start + surface.start;
                    int tokenEnd = start + surface.end;
                    List<String> subwords = new ArrayList<>(surface.subwords.size());
                    for (int[] range : surface.subwords) {
                        subwords.add(MandarinG2pM.join(source, tokenStart + range[0], tokenStart + range[1]));
                    }
                    List<String[]> rows = wordReadings.get(tokenStart + ":" + tokenEnd);
                    List<MandarinSyllable> syllables = new ArrayList<>(tokenEnd - tokenStart);
                    for (int index = tokenStart; index < tokenEnd; index++) {
                        syllables.add(syllable(line.syllables.get(index), source[index], rows,
                                index - tokenStart));
                    }
                    tokens.add(new MandarinToken(MandarinG2pM.join(source, tokenStart, tokenEnd),
                            tokenStart, tokenEnd, "han", surface.tag, subwords, syllables));
                }
            } else {
                tokens.add(new MandarinToken(MandarinG2pM.join(source, start, end),
                        start, end, tokenKind, "x", new ArrayList<>(), new ArrayList<>()));
            }
            start = end;
        }
        return new MandarinReadingLine(text, tokens);
    }

    /**
     * Layer 1 of eza式 revision 3: letters, the real tone and the N flag. 一 is always {@code yi1}
     * and 不 is always {@code bu4}, also when the source data stores a sandhi form. A neutral keeps
     * the citation tone the data has for the same letters; an intrinsically toneless syllable
     * keeps tone 5.
     */
    private static MandarinSyllable syllable(MandarinG2pM.Syllable syllable, String character,
                                             List<String[]> wordReadings, int at) {
        if (syllable.status == MandarinG2pM.Status.UNAVAILABLE) {
            return MandarinSyllable.unavailable(syllable.reason);
        }
        if (syllable.status != MandarinG2pM.Status.READING) {
            return MandarinSyllable.unavailable("No Mandarin reading is supplied for this character.");
        }
        String raw = syllable.raw;
        String letters = letters(raw);
        int selectedTone = tone(raw);
        boolean neutral = selectedTone == 5;
        String[] candidates = syllable.candidates == null ? new String[0] : syllable.candidates;
        // The real tone comes from an alternative with the same letters only; 了 keeps le5 because
        // liao3 is a different syllable.
        int citationTone = selectedTone;
        if (neutral) {
            for (String candidate : candidates) {
                if (tone(candidate) != 5 && letters(candidate).equals(letters)) {
                    citationTone = tone(candidate);
                    break;
                }
            }
        }
        if ("一".equals(character)) {
            letters = "yi";
            citationTone = 1;
            neutral = false;
        } else if ("不".equals(character)) {
            letters = "bu";
            citationTone = 4;
            neutral = false;
        }
        boolean alwaysNeutral = neutral && allNeutral(wordReadings, candidates, raw, at);
        List<String> alternatives = candidates.length > 1
                ? new ArrayList<>(Arrays.asList(candidates)) : null;
        return MandarinSyllable.fromData(letters, citationTone, neutral, alwaysNeutral,
                syllable.basis, syllable.contextStart, syllable.contextEnd, null, alternatives);
    }

    private static boolean allNeutral(List<String[]> wordReadings, String[] candidates,
                                      String raw, int at) {
        List<String[]> rows = wordReadings;
        if (rows == null || rows.isEmpty()) {
            rows = new ArrayList<>();
            if (candidates.length > 0) {
                rows.add(candidates);
            } else {
                rows.add(new String[]{raw});
            }
        }
        for (String[] row : rows) {
            if (at >= row.length || tone(row[at]) != 5) return false;
        }
        return true;
    }

    static String letters(String syllable) {
        return syllable.substring(0, syllable.length() - 1);
    }

    static int tone(String syllable) {
        char last = syllable.charAt(syllable.length() - 1);
        return last >= '1' && last <= '5' ? last - '0' : 5;
    }

    /** Unicode-kind classes used by the portable reader; punctuation and symbols stay separate. */
    static String kind(String value) {
        if (value == null || value.isEmpty()) return "other";
        int codePoint = value.codePointAt(0);
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        if (script == Character.UnicodeScript.HAN) return "han";
        if (script == Character.UnicodeScript.LATIN) return "latin";
        int type = Character.getType(codePoint);
        if (type == Character.DECIMAL_DIGIT_NUMBER || type == Character.LETTER_NUMBER
                || type == Character.OTHER_NUMBER) return "digit";
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) return "whitespace";
        if ((type >= Character.DASH_PUNCTUATION && type <= Character.OTHER_PUNCTUATION)
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION) return "punctuation";
        if (type >= Character.MATH_SYMBOL && type <= Character.OTHER_SYMBOL) return "symbol";
        return "other";
    }

    static String[] points(String text) {
        return MandarinG2pM.points(text);
    }

    static String join(String[] points, int start, int end) {
        return MandarinG2pM.join(points, start, end);
    }

    private static String[] slice(String[] values, int start, int end) {
        String[] out = new String[end - start];
        System.arraycopy(values, start, out, 0, out.length);
        return out;
    }

    /** Load and verify a pack. Throws on any missing, malformed, or modified file. */
    public static MandarinReader load(PackSource source) throws IOException {
        byte[] manifestBytes = source.manifest();
        String version = ASSET_VERSION_PREFIX + sha256(manifestBytes);
        JsonObject manifest = JsonParser.parseString(
                new String(manifestBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!MandarinG2pM.MANIFEST_SCHEMA.equals(string(manifest, "schema"))
                || !MandarinG2pM.MANIFEST_FORMAT.equals(string(manifest, "format"))) {
            throw new IOException("Unsupported Mandarin g2pM pack manifest.");
        }
        if (!MandarinG2pM.POLICY_ID.equals(string(manifest.getAsJsonObject("policy"), "id"))) {
            throw new IOException("Unsupported Mandarin g2pM reading policy.");
        }
        Set<String> sourceIds = new HashSet<>();
        JsonArray sources = manifest.getAsJsonArray("sources");
        if (sources == null || sources.size() == 0) {
            throw new IOException("Mandarin g2pM pack has no source records.");
        }
        for (JsonElement element : sources) {
            JsonObject item = element.getAsJsonObject();
            String id = string(item, "id");
            if (id != null) sourceIds.add(id);
            if (string(item, "license") == null) {
                throw new IOException("Mandarin g2pM source has no licence.");
            }
        }
        for (String required : new String[] {"g2pM", "phrase-pinyin-data", "pinyin-data",
                "opencc-js", "jieba-rs-dict", "jieba-rs-hmm"}) {
            if (!sourceIds.contains(required)) {
                throw new IOException("Mandarin g2pM pack is missing " + required + ".");
            }
        }
        JsonObject files = manifest.getAsJsonObject("files");
        if (files == null) throw new IOException("Mandarin g2pM pack has no file receipts.");
        Map<String, byte[]> data = new HashMap<>(FILES.length * 2);
        for (String name : FILES) {
            JsonObject entry = files.getAsJsonObject(name);
            if (entry == null) throw new IOException("Mandarin g2pM pack is missing " + name + ".");
            long expectedBytes = entry.get("bytes").getAsLong();
            String expectedSha = string(entry, "sha256");
            byte[] bytes = source.file(name);
            if (expectedSha == null || bytes.length != expectedBytes
                    || !sha256(bytes).equals(expectedSha)) {
                throw new IOException("Modified Mandarin g2pM pack member: " + name);
            }
            data.put(name, bytes);
        }
        MandarinG2pM engine = MandarinG2pM.load(manifest, data.get("model.f32"),
                data.get("model-vocab.json"), data.get("classes.txt"), data.get("characters.tsv"),
                data.get("digest.json"), data.get("phrases.tsv"), data.get("t2s.tsv"));
        MandarinSegmenter segmenter = MandarinSegmenter.load(data.get("jieba.tsv"), data.get("hmm.json"));
        return new MandarinReader(engine, segmenter, version);
    }

    private static String string(JsonObject object, String name) {
        if (object == null) return null;
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                out.append(Character.forDigit((item >> 4) & 0xf, 16));
                out.append(Character.forDigit(item & 0xf, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
