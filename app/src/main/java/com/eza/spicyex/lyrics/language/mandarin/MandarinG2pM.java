package com.eza.spicyex.lyrics.language.mandarin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pinned g2pM Mandarin reading engine (cjk-labs {@code mandarin-g2pm-model-first-v1}).
 *
 * <p>Policy, matching the pinned TypeScript port exactly:
 * <ol>
 *   <li>A 1:1 simplified shadow is used for phrase lookup and for model context when the pinned
 *       model vocabulary does not know the original code point.</li>
 *   <li>An exact whole-input phrase entry (entire normalized input is one Han run and an
 *       unambiguous phrase key) is authoritative.</li>
 *   <li>Otherwise g2pM decides characters that are polyphonic in the digest and present in the
 *       model vocabulary. Digest eligibility is keyed by the source character only: the shadow is
 *       a model-input and phrase-lookup convenience, never a source of polyphone coverage.</li>
 *   <li>The longest unambiguous phrase covering the position supplies the reading only for
 *       positions the model did not decide.</li>
 *   <li>Otherwise the character default from the pinyin-data table is used.</li>
 * </ol>
 *
 * <p>No candidate mask is applied to the model logits. Neutral selected readings stay tone 5.
 * The engine performs no I/O; the pack loader passes verified bytes. Standard Pinyin does not use
 * this engine: it stays on the plain pinyin4j package path.
 */
public final class MandarinG2pM {
    public static final String MANIFEST_SCHEMA = "cjk-labs.zh.mandarin-g2pm.manifest.v1";
    public static final String MANIFEST_FORMAT = "mandarin-g2pm-f32-v1";
    public static final String POLICY_ID = "mandarin-g2pm-model-first-v1";

    private static final Pattern NUMBERED = Pattern.compile("^([a-züê]+)([1-5])$");

    public enum Status { READING, PASSTHROUGH, UNAVAILABLE }

    /** One code-point-aligned syllable or passthrough result. */
    public static final class Syllable {
        public final Status status;
        public final int index;
        public final String character;
        public final String normalized;
        /** Canonical numbered reading with neutral 5, or {@code null}. */
        public final String raw;
        /** {@code phrase}, {@code model}, or {@code character}, or {@code null}. */
        public final String basis;
        public final String reason;
        public final String[] candidates;
        public final int contextStart;
        public final int contextEnd;
        /** Dense 876-class logits when the model decided this position, otherwise {@code null}. */
        public final float[] logits;

        Syllable(Status status, int index, String character, String normalized, String raw,
                 String basis, String reason, String[] candidates, int contextStart, int contextEnd,
                 float[] logits) {
            this.status = status;
            this.index = index;
            this.character = character;
            this.normalized = normalized;
            this.raw = raw;
            this.basis = basis;
            this.reason = reason;
            this.candidates = candidates;
            this.contextStart = contextStart;
            this.contextEnd = contextEnd;
            this.logits = logits;
        }

        public boolean hasContext() {
            return contextStart >= 0 && contextEnd > contextStart;
        }
    }

    /** Counts for one reading line. */
    public static final class Counts {
        public int total;
        public int model;
        public int phrase;
        public int character;
        public int passthrough;
        public int unavailable;
    }

    /** One reading result, code-point aligned to {@link #input}. */
    public static final class Line {
        public final String input;
        public final String normalized;
        public final List<Syllable> syllables;
        public final Counts counts;
        public final int modelTokens;
        public final int modelPositions;

        Line(String input, String normalized, List<Syllable> syllables, Counts counts,
             int modelTokens, int modelPositions) {
            this.input = input;
            this.normalized = normalized;
            this.syllables = syllables;
            this.counts = counts;
            this.modelTokens = modelTokens;
            this.modelPositions = modelPositions;
        }
    }

    /** One pinyin-data character row. */
    public static final class CharacterEntry {
        public final String defaultReading;
        public final String[] candidates;
        public final String defaultSource;

        CharacterEntry(String defaultReading, String[] candidates, String defaultSource) {
            this.defaultReading = defaultReading;
            this.candidates = candidates;
            this.defaultSource = defaultSource;
        }
    }

    private static final class PhraseSelection {
        final String text;
        final String[] readings;
        final int start;
        final int end;

        PhraseSelection(String text, String[] readings, int start, int end) {
            this.text = text;
            this.readings = readings;
            this.start = start;
            this.end = end;
        }
    }

    private static final class Prepared {
        final String character;
        final String normalized;
        final String modelCharacter;
        final boolean han;
        final CharacterEntry entry;
        final String[] digestReadings;
        final boolean inModelVocabulary;

        Prepared(String character, String normalized, String modelCharacter, boolean han,
                 CharacterEntry entry, String[] digestReadings, boolean inModelVocabulary) {
            this.character = character;
            this.normalized = normalized;
            this.modelCharacter = modelCharacter;
            this.han = han;
            this.entry = entry;
            this.digestReadings = digestReadings;
            this.inModelVocabulary = inModelVocabulary;
        }
    }

    private final Map<String, CharacterEntry> characters;
    private final Map<String, String[]> digest;
    private final Map<String, String[][]> phrases;
    private final Map<String, String> shadow;
    private final String[] tokens;
    private final Map<String, Integer> tokenToIndex;
    private final int padIndex;
    private final int unkIndex;
    private final int bosIndex;
    private final int eosIndex;
    private final String[] classes;
    private final G2pMModel model;
    private final int maxPhraseLength;

    private MandarinG2pM(Map<String, CharacterEntry> characters, Map<String, String[]> digest,
                         Map<String, String[][]> phrases, Map<String, String> shadow,
                         String[] tokens, Map<String, Integer> tokenToIndex,
                         int padIndex, int unkIndex, int bosIndex, int eosIndex,
                         String[] classes, G2pMModel model) {
        this.characters = characters;
        this.digest = digest;
        this.phrases = phrases;
        this.shadow = shadow;
        this.tokens = tokens;
        this.tokenToIndex = tokenToIndex;
        this.padIndex = padIndex;
        this.unkIndex = unkIndex;
        this.bosIndex = bosIndex;
        this.eosIndex = eosIndex;
        this.classes = classes;
        this.model = model;
        this.maxPhraseLength = maxPhraseLength(phrases);
    }

    /** Parsed counts and construction facts for pack receipts and tests. */
    public int characterCount() {
        return characters.size();
    }

    public int phraseCount() {
        int rows = 0;
        for (String[][] values : phrases.values()) rows += values.length;
        return rows;
    }

    public int digestCount() {
        return digest.size();
    }

    public int shadowCount() {
        return shadow.size();
    }

    public int classCount() {
        return classes.length;
    }

    public int vocabularySize() {
        return tokens.length;
    }

    public int maxPhraseLength() {
        return maxPhraseLength;
    }

    public String[] classes() {
        return classes.clone();
    }

    public float[][] modelLogits(int[] inputIds, int[] positions) {
        return model.logits(inputIds, positions);
    }

    /** 1:1 simplified shadow for one code point, used by the tokenizer. */
    public String normalize(String character) {
        return shadow.getOrDefault(character, character);
    }

    /** The exact g2pM token sequence the reader would build: BOS, line, EOS. */
    public int[] modelInputIds(String text) {
        String[] source = points(text == null ? "" : text);
        int[] ids = new int[source.length + 2];
        ids[0] = bosIndex;
        for (int index = 0; index < source.length; index++) {
            String character = source[index];
            String normalized = normalize(character);
            String modelCharacter = isHan(character) && tokenToIndex.containsKey(character)
                    ? character : normalized;
            Integer id = tokenToIndex.get(modelCharacter);
            ids[index + 1] = id == null ? unkIndex : id;
        }
        ids[ids.length - 1] = eosIndex;
        return ids;
    }

    /** Dense logits for explicit positions in {@link #modelInputIds(String)}, for parity checks. */
    public float[][] logits(String text, int[] positions) {
        return model.logits(modelInputIds(text), positions);
    }

    /** Read with the full model-first policy, used by the eza式 lane. */
    public Line read(String text) {
        String input = text == null ? "" : text;
        String[] source = points(input);
        Prepared[] prepared = prepare(source);

        Map<Integer, PhraseSelection> exact = new HashMap<>();
        Map<Integer, PhraseSelection> fill = new HashMap<>();
        boolean wholeHan = prepared.length > 0;
        for (Prepared item : prepared) {
            if (!item.han) {
                wholeHan = false;
                break;
            }
        }
        if (wholeHan) {
            PhraseSelection whole = phraseForRun(join(normalized(prepared), 0, prepared.length));
            if (whole != null) {
                for (int index = 0; index < prepared.length; index++) {
                    exact.put(index, new PhraseSelection(whole.text, whole.readings, 0, prepared.length));
                }
            }
        }
        if (exact.isEmpty()) {
            for (int[] run : hanRuns(prepared)) fillFromPhrases(prepared, run[0], run[1], fill);
        }

        List<Integer> modelPositions = new ArrayList<>();
        for (int index = 0; index < prepared.length; index++) {
            Prepared item = prepared[index];
            if (!item.han || exact.containsKey(index)) continue;
            if (item.digestReadings.length > 1 && item.inModelVocabulary) modelPositions.add(index);
        }

        Map<Integer, float[]> logits = new HashMap<>();
        int modelTokens = 0;
        if (!modelPositions.isEmpty()) {
            int[] ids = new int[prepared.length + 2];
            ids[0] = bosIndex;
            for (int index = 0; index < prepared.length; index++) {
                Integer id = tokenToIndex.get(prepared[index].modelCharacter);
                ids[index + 1] = id == null ? unkIndex : id;
            }
            ids[ids.length - 1] = eosIndex;
            modelTokens = ids.length;
            int[] positions = new int[modelPositions.size()];
            for (int at = 0; at < positions.length; at++) positions[at] = modelPositions.get(at) + 1;
            float[][] raw = model.logits(ids, positions);
            for (int at = 0; at < raw.length; at++) {
                logits.put(modelPositions.get(at), raw[at]);
            }
        }

        Counts counts = new Counts();
        counts.total = source.length;
        List<Syllable> syllables = new ArrayList<>(source.length);
        String[] normalizedChars = normalized(prepared);

        for (int index = 0; index < prepared.length; index++) {
            Prepared item = prepared[index];
            if (!item.han) {
                syllables.add(new Syllable(Status.PASSTHROUGH, index, item.character,
                        item.normalized, null, null, null, null, -1, -1, null));
                counts.passthrough++;
                continue;
            }
            PhraseSelection phrase = exact.get(index);
            String exactReading = phrase == null ? null : phrase.readings[index - phrase.start];
            PhraseSelection fillPhrase = fill.get(index);
            String fillReading = fillPhrase == null ? null : fillPhrase.readings[0];
            float[] positionLogits = logits.get(index);
            String modelReading = positionLogits == null ? null : topReading(positionLogits);
            String[] candidates = item.entry != null ? item.entry.candidates : item.digestReadings;

            String raw;
            String basis;
            int contextStart = -1;
            int contextEnd = -1;
            if (exactReading != null) {
                raw = exactReading;
                basis = "phrase";
                contextStart = phrase.start;
                contextEnd = phrase.end;
                counts.phrase++;
            } else if (modelReading != null) {
                raw = modelReading;
                basis = "model";
                contextStart = 0;
                contextEnd = normalizedChars.length;
                counts.model++;
            } else if (fillReading != null) {
                raw = fillReading;
                basis = "phrase";
                contextStart = fillPhrase.start;
                contextEnd = fillPhrase.end;
                counts.phrase++;
            } else if (item.entry != null) {
                raw = item.entry.defaultReading;
                basis = "character";
                counts.character++;
            } else if (item.digestReadings.length == 1) {
                raw = item.digestReadings[0];
                basis = "character";
                counts.character++;
            } else {
                syllables.add(new Syllable(Status.UNAVAILABLE, index, item.character,
                        item.normalized, null, null,
                        item.entry != null ? "no-reading" : "no-character-data",
                        candidates, -1, -1, positionLogits));
                counts.unavailable++;
                continue;
            }
            syllables.add(new Syllable(Status.READING, index, item.character, item.normalized,
                    raw, basis, null, candidates, contextStart, contextEnd, positionLogits));
        }
        return new Line(input, join(normalizedChars, 0, normalizedChars.length), syllables, counts,
                modelTokens, modelPositions.size());
    }

    private Prepared[] prepare(String[] source) {
        Prepared[] prepared = new Prepared[source.length];
        for (int index = 0; index < source.length; index++) {
            String character = source[index];
            String normalized = shadow.getOrDefault(character, character);
            boolean han = isHan(character);
            CharacterEntry entry = null;
            if (han) {
                entry = characters.get(character);
                if (entry == null) entry = characters.get(normalized);
            }
            String modelCharacter = han && tokenToIndex.containsKey(character) ? character : normalized;
            String[] readings = new String[0];
            if (han) {
                String[] original = digest.get(character);
                readings = original == null ? new String[0] : original;
            }
            prepared[index] = new Prepared(character, normalized, modelCharacter, han, entry,
                    readings, han && tokenToIndex.containsKey(modelCharacter));
        }
        return prepared;
    }

    private static String[] normalized(Prepared[] prepared) {
        String[] out = new String[prepared.length];
        for (int index = 0; index < prepared.length; index++) out[index] = prepared[index].normalized;
        return out;
    }

    private PhraseSelection phraseForRun(String run) {
        String[][] rows = phrases.get(run);
        if (rows == null || rows.length == 0) return null;
        Map<String, String[]> distinct = new LinkedHashMap<>();
        for (String[] reading : rows) distinct.put(String.join(" ", reading), reading);
        if (distinct.size() != 1) return null;
        String[] readings = distinct.values().iterator().next();
        return new PhraseSelection(run, readings, 0, 0);
    }

    /**
     * Longest unambiguous phrase reading for every position the caller did not resolve. Equal
     * length conflicts with different readings leave the position at its character default.
     */
    private void fillFromPhrases(Prepared[] prepared, int start, int end,
                                 Map<Integer, PhraseSelection> target) {
        final class Best {
            final int length;
            final Set<String> readings = new LinkedHashSet<>();
            final String text;
            final int start;
            final int finish;

            Best(int length, String text, int start, int finish) {
                this.length = length;
                this.text = text;
                this.start = start;
                this.finish = finish;
            }
        }
        Map<Integer, Best> best = new HashMap<>();
        String[] chars = normalized(prepared);
        for (int offset = start; offset < end; offset++) {
            int limit = Math.min(maxPhraseLength, end - offset);
            for (int length = limit; length >= 2; length--) {
                String text = join(chars, offset, offset + length);
                String[][] rows = phrases.get(text);
                if (rows == null) continue;
                Set<String> distinct = new LinkedHashSet<>();
                for (String[] reading : rows) distinct.add(String.join(" ", reading));
                for (String phrase : distinct) {
                    String[] syllables = phrase.split(" ");
                    for (int offsetInPhrase = 0; offsetInPhrase < length; offsetInPhrase++) {
                        int index = offset + offsetInPhrase;
                        String reading = syllables[offsetInPhrase];
                        Best current = best.get(index);
                        if (current == null || length > current.length) {
                            Best next = new Best(length, text, offset, offset + length);
                            next.readings.add(reading);
                            best.put(index, next);
                        } else if (length == current.length) {
                            current.readings.add(reading);
                        }
                    }
                }
            }
        }
        for (Map.Entry<Integer, Best> entry : best.entrySet()) {
            Best item = entry.getValue();
            if (item.readings.size() != 1) continue;
            target.put(entry.getKey(), new PhraseSelection(item.text,
                    new String[] {item.readings.iterator().next()}, item.start, item.finish));
        }
    }

    /** One phrase-table entry that matches the normalized input. */
    public static final class PhraseMatch {
        public final String text;
        public final int start;
        public final int end;
        /** Distinct whole-phrase readings, one space-separated syllable per code point. */
        public final String[] readings;

        PhraseMatch(String text, int start, int end, String[] readings) {
            this.text = text;
            this.start = start;
            this.end = end;
            this.readings = readings;
        }
    }

    /**
     * Every phrase-table entry of length two or more that matches a Han run of the input.
     *
     * <p>Layer 1 uses this to decide {@code alwaysNeutral}: a token whose whole-token phrase
     * readings are all neutral at a position keeps a preceding third tone.
     */
    public List<PhraseMatch> lookupPhrases(String text) {
        String[] source = points(text == null ? "" : text);
        List<PhraseMatch> matches = new ArrayList<>();
        for (int start = 0; start < source.length; ) {
            if (!isHan(source[start])) {
                start++;
                continue;
            }
            int end = start;
            while (end < source.length && isHan(source[end])) end++;
            for (int offset = start; offset < end; offset++) {
                int limit = Math.min(maxPhraseLength, end - offset);
                for (int length = limit; length >= 2; length--) {
                    StringBuilder key = new StringBuilder();
                    for (int at = offset; at < offset + length; at++) key.append(normalize(source[at]));
                    String[][] rows = phrases.get(key.toString());
                    if (rows == null) continue;
                    Set<String> distinct = new LinkedHashSet<>();
                    for (String[] reading : rows) distinct.add(String.join(" ", reading));
                    matches.add(new PhraseMatch(key.toString(), offset, offset + length,
                            distinct.toArray(new String[0])));
                }
            }
            start = end;
        }
        return matches;
    }

    private String topReading(float[] rawLogits) {
        int best = 0;
        for (int index = 1; index < rawLogits.length; index++) {
            if (rawLogits[index] > rawLogits[best]) best = index;
        }
        return canonical(classes[best]);
    }

    private static List<int[]> hanRuns(Prepared[] prepared) {
        List<int[]> runs = new ArrayList<>();
        int start = -1;
        for (int index = 0; index < prepared.length; index++) {
            if (prepared[index].han) {
                if (start < 0) start = index;
            } else if (start >= 0) {
                runs.add(new int[] {start, index});
                start = -1;
            }
        }
        if (start >= 0) runs.add(new int[] {start, prepared.length});
        return runs;
    }

    private static int maxPhraseLength(Map<String, String[][]> phrases) {
        int maximum = 2;
        for (String key : phrases.keySet()) {
            int length = points(key).length;
            if (length > maximum) maximum = length;
        }
        return maximum;
    }

    /** Build the engine from verified pack bytes. */
    public static MandarinG2pM load(JsonObject manifest, byte[] modelBytes, byte[] vocabJson,
                                    byte[] classesText, byte[] charactersTsv, byte[] digestJson,
                                    byte[] phrasesTsv, byte[] t2sTsv) {
        if (!MANIFEST_SCHEMA.equals(string(manifest, "schema"))
                || !MANIFEST_FORMAT.equals(string(manifest, "format"))) {
            throw new IllegalArgumentException("Unsupported Mandarin g2pM manifest.");
        }
        JsonObject counts = manifest.getAsJsonObject("counts");
        if (counts == null) throw new IllegalArgumentException("Mandarin g2pM manifest has no counts.");
        JsonObject modelJson = manifest.getAsJsonObject("model");
        if (modelJson == null || !"float32".equals(string(modelJson, "dtype"))
                || !"little-endian".equals(string(modelJson, "byteOrder"))) {
            throw new IllegalArgumentException("Mandarin g2pM model layout is invalid.");
        }
        if (modelBytes.length != modelJson.get("byteLength").getAsInt()) {
            throw new IllegalArgumentException("Mandarin g2pM model length differs from its manifest.");
        }

        String[] vocabulary = parseVocabulary(vocabJson);
        int expectedVocab = counts.get("vocab").getAsInt();
        if (vocabulary.length != expectedVocab) {
            throw new IllegalArgumentException("Model vocabulary count differs from the manifest.");
        }
        String[] classes = parseClasses(text(classesText));
        if (classes.length != counts.get("classes").getAsInt()) {
            throw new IllegalArgumentException("Class count differs from the manifest.");
        }
        Map<String, CharacterEntry> characters = parseCharacters(text(charactersTsv));
        if (characters.size() != counts.get("characters").getAsInt()) {
            throw new IllegalArgumentException("Character count differs from the manifest.");
        }
        Map<String, String> shadow = parseShadow(text(t2sTsv));
        if (shadow.size() != counts.get("t2s").getAsInt()) {
            throw new IllegalArgumentException("T2S count differs from the manifest.");
        }
        Map<String, String[]> digest = parseDigest(text(digestJson));
        if (digest.size() != counts.get("digest").getAsInt()) {
            throw new IllegalArgumentException("Digest count differs from the manifest.");
        }
        ParsedPhrases parsedPhrases = parsePhrases(text(phrasesTsv));
        if (parsedPhrases.rows != counts.get("phrases").getAsInt()) {
            throw new IllegalArgumentException("Phrase count differs from the manifest.");
        }
        Map<String, String[][]> phrases = parsedPhrases.table;

        Map<String, Integer> tokenToIndex = new HashMap<>(vocabulary.length * 2);
        for (int index = 0; index < vocabulary.length; index++) {
            if (tokenToIndex.put(vocabulary[index], index) != null) {
                throw new IllegalArgumentException("Duplicate model vocabulary token.");
            }
        }
        int pad = integer(vocabJson, "padIndex");
        int unk = integer(vocabJson, "unkIndex");
        int bos = integer(vocabJson, "bosIndex");
        int eos = integer(vocabJson, "eosIndex");
        if (!"<PAD>".equals(vocabulary[pad]) || !"<UNK>".equals(vocabulary[unk])
                || !"시".equals(vocabulary[bos]) || !"끝".equals(vocabulary[eos])) {
            throw new IllegalArgumentException("Special tokens differ from the pinned g2pM vocabulary.");
        }

        G2pMModel model = new G2pMModel(parseTensors(modelJson, modelBytes),
                vocabulary.length, classes.length);
        return new MandarinG2pM(characters, digest, phrases, shadow, vocabulary, tokenToIndex,
                pad, unk, bos, eos, classes, model);
    }

    private static Map<String, float[]> parseTensors(JsonObject modelJson, byte[] bytes) {
        JsonArray tensors = modelJson.getAsJsonArray("tensors");
        if (tensors == null || tensors.size() == 0) {
            throw new IllegalArgumentException("Mandarin g2pM manifest has no tensors.");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        Map<String, float[]> out = new HashMap<>();
        for (JsonElement element : tensors) {
            JsonObject spec = element.getAsJsonObject();
            String name = string(spec, "name");
            int offset = spec.get("offset").getAsInt();
            JsonArray shape = spec.getAsJsonArray("shape");
            int length = 1;
            for (JsonElement size : shape) length *= size.getAsInt();
            if (name == null || offset < 0 || offset % 4 != 0 || offset + length * 4 > bytes.length) {
                throw new IllegalArgumentException("Invalid g2pM tensor " + name + ".");
            }
            if (out.containsKey(name)) throw new IllegalArgumentException("Duplicate g2pM tensor " + name + ".");
            float[] values = new float[length];
            buffer.position(offset);
            for (int index = 0; index < length; index++) values[index] = buffer.getFloat();
            out.put(name, values);
        }
        return out;
    }

    private static String[] parseVocabulary(byte[] source) {
        JsonObject root = JsonParser.parseString(text(source)).getAsJsonObject();
        JsonArray tokens = root.getAsJsonArray("tokens");
        if (tokens == null || tokens.size() == 0) {
            throw new IllegalArgumentException("Model vocabulary is empty.");
        }
        String[] out = new String[tokens.size()];
        for (int index = 0; index < out.length; index++) {
            JsonElement token = tokens.get(index);
            if (token == null || !token.isJsonPrimitive() || !token.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Model vocabulary token is invalid.");
            }
            out[index] = token.getAsString();
        }
        return out;
    }

    private static String[] parseClasses(String source) {
        List<String> classes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : lines(source)) {
            if (line.isEmpty()) continue;
            String canonical = canonical(line);
            if (canonical == null) throw new IllegalArgumentException("Malformed g2pM class label.");
            if (!seen.add(canonical)) throw new IllegalArgumentException("Duplicate g2pM class label.");
            classes.add(canonical);
        }
        if (classes.isEmpty()) throw new IllegalArgumentException("Class table is empty.");
        return classes.toArray(new String[0]);
    }

    private static Map<String, CharacterEntry> parseCharacters(String source) {
        Map<String, CharacterEntry> out = new HashMap<>();
        for (String row : lines(source)) {
            if (row.isEmpty()) continue;
            String[] fields = row.split("\t", -1);
            if (fields.length != 4) throw new IllegalArgumentException("Malformed character row.");
            int codePoint;
            try {
                codePoint = Integer.parseInt(fields[0], 16);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Malformed character code point.", invalid);
            }
            String character = new String(Character.toChars(codePoint));
            String defaultReading = canonical(fields[1]);
            String[] candidates = unique(fields[2].split(","));
            String defaultSource = fields[3];
            if (defaultReading == null || candidates.length == 0
                    || !contains(candidates, defaultReading)
                    || !("8105".equals(defaultSource) || "pinyin".equals(defaultSource))) {
                throw new IllegalArgumentException("Character row has inconsistent readings.");
            }
            if (out.put(character, new CharacterEntry(defaultReading, candidates, defaultSource)) != null) {
                throw new IllegalArgumentException("Duplicate character row.");
            }
        }
        return out;
    }

    private static Map<String, String> parseShadow(String source) {
        Map<String, String> out = new HashMap<>();
        for (String row : lines(source)) {
            if (row.isEmpty()) continue;
            String[] fields = row.split("\t", -1);
            if (fields.length != 2 || points(fields[0]).length != 1
                    || points(fields[1]).length != 1 || fields[0].equals(fields[1])) {
                throw new IllegalArgumentException("Malformed traditional/simplified row.");
            }
            out.put(fields[0], fields[1]);
        }
        return out;
    }

    private static Map<String, String[]> parseDigest(String source) {
        JsonObject root = JsonParser.parseString(source).getAsJsonObject();
        Map<String, String[]> out = new HashMap<>(root.size() * 2);
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            String character = entry.getKey();
            if (points(character).length != 1 || !entry.getValue().isJsonArray()) {
                throw new IllegalArgumentException("Malformed digest entry.");
            }
            JsonArray readings = entry.getValue().getAsJsonArray();
            if (readings.size() == 0) throw new IllegalArgumentException("Empty digest entry.");
            String[] values = new String[readings.size()];
            for (int index = 0; index < values.length; index++) {
                values[index] = canonical(readings.get(index).getAsString());
                if (values[index] == null) throw new IllegalArgumentException("Malformed digest reading.");
            }
            out.put(character, values);
        }
        return out;
    }

    private static final class ParsedPhrases {
        final Map<String, String[][]> table;
        final int rows;

        ParsedPhrases(Map<String, String[][]> table, int rows) {
            this.table = table;
            this.rows = rows;
        }
    }

    private static ParsedPhrases parsePhrases(String source) {
        Map<String, List<String[]>> grouped = new HashMap<>(420_000);
        int rowCount = 0;
        for (String row : lines(source)) {
            if (row.isEmpty()) continue;
            int tab = row.indexOf('\t');
            if (tab <= 0) throw new IllegalArgumentException("Malformed phrase row.");
            String text = row.substring(0, tab);
            String[] raw = row.substring(tab + 1).split(" ");
            if (raw.length != points(text).length) {
                throw new IllegalArgumentException("Phrase reading does not align: " + text);
            }
            String[] readings = new String[raw.length];
            for (int index = 0; index < raw.length; index++) {
                readings[index] = canonical(raw[index]);
                if (readings[index] == null) {
                    throw new IllegalArgumentException("Phrase has a malformed reading: " + text);
                }
            }
            List<String[]> rows = grouped.get(text);
            if (rows == null) {
                rows = new ArrayList<>(1);
                grouped.put(text, rows);
            }
            rows.add(readings);
            rowCount++;
        }
        Map<String, String[][]> out = new HashMap<>(grouped.size() * 2);
        for (Map.Entry<String, List<String[]>> entry : grouped.entrySet()) {
            out.put(entry.getKey(), entry.getValue().toArray(new String[0][]));
        }
        return new ParsedPhrases(out, rowCount);
    }

    /** Canonical numbered reading: lowercase letters plus one tone digit, neutral 5. */
    public static String canonical(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT)
                .replace("u:", "ü").replace('v', 'ü');
        Matcher matcher = NUMBERED.matcher(value);
        return matcher.matches() ? value : null;
    }

    private static String[] unique(String[] values) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : values) {
            String canonical = canonical(value);
            if (canonical != null) out.add(canonical);
        }
        return out.toArray(new String[0]);
    }

    private static boolean contains(String[] values, String value) {
        for (String item : values) {
            if (item.equals(value)) return true;
        }
        return false;
    }

    public static boolean isHan(String value) {
        return value != null && value.length() > 0
                && Character.UnicodeScript.of(value.codePointAt(0)) == Character.UnicodeScript.HAN;
    }

    static String[] points(String text) {
        if (text == null || text.isEmpty()) return new String[0];
        int count = text.codePointCount(0, text.length());
        String[] out = new String[count];
        for (int index = 0, at = 0; index < count; index++) {
            int codePoint = text.codePointAt(at);
            out[index] = new String(Character.toChars(codePoint));
            at += Character.charCount(codePoint);
        }
        return out;
    }

    static String join(String[] points, int start, int end) {
        if (start >= end) return "";
        if (end - start == 1) return points[start];
        StringBuilder out = new StringBuilder();
        for (int index = start; index < end; index++) out.append(points[index]);
        return out.toString();
    }

    /** UTF-8 lines without a trailing empty row, as the TypeScript pack parsers read them. */
    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) != '\n') continue;
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
        return out;
    }

    private static String text(byte[] source) {
        return new String(source, StandardCharsets.UTF_8);
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static int integer(byte[] json, String name) {
        JsonObject root = JsonParser.parseString(text(json)).getAsJsonObject();
        return root.get(name).getAsInt();
    }
}
