package com.eza.spicyex.lyrics;

import com.atilika.kuromoji.unidic.Token;
import com.atilika.kuromoji.unidic.Tokenizer;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/**
 * Android dependency-backed JP/CN processing.
 *
 * JP architecture (see docs/JAPANESE_NLP_AUDIT_AND_PLAN.md):
 * - Kuromoji UniDic lattice owns segmentation, readings, and conjugation;
 *   we do not hand-write grammar rules on top of it.
 * - One reading-of-record (orthographic kana) per token drives BOTH romaji
 *   and furigana, so the two can never disagree.
 * - The lexical override layer must stay tiny and every entry must cite a
 *   reason the dictionary cannot supply the reading (see applyProductiveOverrides).
     * - Furigana resolution is dictionary/rule first. Per-kanji splits are used
     *   only when reading decomposition is unique; otherwise broad ruby wins.
     *   See docs/JAPANESE_FURIGANA_RULESET.md.
 */
public final class SpicyJapaneseChineseProcessor {
    public static final class FuriganaSegment {
        public final int start;
        public final int end;
        public final String reading;

        public FuriganaSegment(int start, int end, String reading) {
            this.start = start;
            this.end = end;
            this.reading = reading == null ? "" : reading;
        }
    }

    /** One finalized-analysis romaji unit spanning [start, end) of sourceText. */
    public static final class ReadingGroup {
        public final int start;
        public final int end;
        public final String romaji;

        public ReadingGroup(int start, int end, String romaji) {
            this.start = start;
            this.end = end;
            this.romaji = romaji == null ? "" : romaji;
        }
    }

    public static final class JapaneseReading {
        public final String sourceText;
        public final String romaji;
        public final List<FuriganaSegment> furigana;
        /** Finalized analysis groups; timing projection consumes these instead of retokenizing. */
        public final List<ReadingGroup> groups;
        public final JapaneseReadingPolicyModels.ReadingContext readingContext;
        public final List<JapaneseReadingPolicyModels.ReadingDecision> readingDecisions;
        public final List<String> diagnostics;

        public JapaneseReading(String sourceText, String romaji, List<FuriganaSegment> furigana) {
            this(sourceText, romaji, furigana, null);
        }

        public JapaneseReading(String sourceText, String romaji, List<FuriganaSegment> furigana,
                               List<ReadingGroup> groups) {
            this(sourceText, romaji, furigana, groups, null, null, null);
        }

        public JapaneseReading(String sourceText, String romaji, List<FuriganaSegment> furigana,
                               List<ReadingGroup> groups,
                               JapaneseReadingPolicyModels.ReadingContext readingContext,
                               List<JapaneseReadingPolicyModels.ReadingDecision> readingDecisions,
                               List<String> diagnostics) {
            this.sourceText = sourceText == null ? "" : sourceText;
            this.romaji = romaji == null ? "" : romaji;
            this.furigana = immutableCopy(furigana);
            this.groups = immutableCopy(groups);
            this.readingContext = readingContext == null
                    ? JapaneseReadingPolicyModels.ReadingContext.empty(this.sourceText) : readingContext;
            this.readingDecisions = immutableCopy(readingDecisions);
            this.diagnostics = immutableCopy(diagnostics);
        }
    }

    private static final class Entry {
        AnalysisText analysisText;
        int tokenIndex;
        int analysisStart;
        int analysisEnd;
        int start;
        int end;
        String sourceText;
        String surface;
        String readingKana; // hiragana reading-of-record; null when token has no reading
        String dictionaryReadingKana;
        String dictionaryReadingSource;
        String readingReason;
        String ruleId; // stable reading-policy rule ID (fixtures/reading-policy) when a rule decided
        Integer ruleVersion;
        boolean boundaryBefore; // removed provider/authored whitespace preceded this token
        boolean hardBoundaryBefore; // explicit hard boundary; blocks phonetic/projection attachment
        String readingGroupId;
        String projectionGroupId;
        String diagnosticId;
        boolean providerMatched;
        boolean providerValidated;
        String providerReadingKana;
        final List<String> providerEvidenceIds = new ArrayList<>();
        final List<JapaneseReadingPolicyModels.ReadingDecision> decisions = new ArrayList<>();
        String romaji;
        Token token;
    }

    static final class JapaneseDebugToken {
        final String surface;
        final int analysisStart, analysisEnd, displayStart, displayEnd;
        final String partOfSpeech1, partOfSpeech2, pronunciation, lemma, lemmaReading;
        final String dictionaryReading, selectedReading, readingReason, ruleId, romaji, readingGroupId;
        final Integer ruleVersion;
        final boolean boundaryBefore;

        JapaneseDebugToken(Entry entry) {
            surface = entry.surface;
            analysisStart = entry.analysisStart;
            analysisEnd = entry.analysisEnd;
            displayStart = entry.start;
            displayEnd = entry.end;
            Token token = entry.token;
            partOfSpeech1 = token == null ? "" : safe(token.getPartOfSpeechLevel1());
            partOfSpeech2 = token == null ? "" : safe(token.getPartOfSpeechLevel2());
            pronunciation = token == null ? "" : safe(token.getPronunciation());
            lemma = token == null ? "" : safe(token.getLemma());
            lemmaReading = token == null ? "" : safe(token.getLemmaReadingForm());
            dictionaryReading = safe(entry.dictionaryReadingKana);
            selectedReading = safe(entry.readingKana);
            readingReason = safe(entry.readingReason);
            ruleId = safe(entry.ruleId);
            ruleVersion = entry.ruleVersion;
            boundaryBefore = entry.boundaryBefore;
            readingGroupId = safe(entry.readingGroupId);
            romaji = safe(entry.romaji);
        }
    }

    static final class JapaneseDebugSnapshot {
        final String displayText, analysisText, romaji;
        final int[] analysisToDisplayUtf16;
        final List<JapaneseDebugToken> tokens;
        final List<FuriganaSegment> furigana;
        final List<ReadingGroup> groups;
        final JapaneseReadingPolicyModels.ReadingContext readingContext;
        final List<JapaneseReadingPolicyModels.ReadingDecision> readingDecisions;
        final List<String> diagnostics;

        JapaneseDebugSnapshot(String displayText, AnalysisText analysis, List<Entry> entries,
                              String romaji, List<FuriganaSegment> furigana,
                              JapaneseReadingPolicyModels.ReadingContext readingContext,
                              List<JapaneseReadingPolicyModels.ReadingDecision> readingDecisions,
                              List<String> diagnostics) {
            this.displayText = displayText;
            this.analysisText = analysis.text;
            this.analysisToDisplayUtf16 = analysis.originalOffsets.clone();
            this.tokens = new ArrayList<>();
            for (Entry entry : entries) this.tokens.add(new JapaneseDebugToken(entry));
            this.romaji = safe(romaji);
            this.furigana = immutableCopy(furigana);
            this.groups = immutableCopy(readingGroups(entries));
            this.readingContext = readingContext;
            this.readingDecisions = immutableCopy(readingDecisions);
            this.diagnostics = immutableCopy(diagnostics);
        }
    }

    private static final class NormalizedText {
        final String rawText;
        final String text;
        final int[] rawToCanonicalUtf16;

        NormalizedText(String rawText, String text, int[] rawToCanonicalUtf16) {
            this.rawText = rawText == null ? "" : rawText;
            this.text = text == null ? "" : text;
            this.rawToCanonicalUtf16 = rawToCanonicalUtf16 == null ? new int[]{0} : rawToCanonicalUtf16;
        }

        int canonicalOffset(int rawOffset) {
            int safe = Math.max(0, Math.min(rawOffset, rawToCanonicalUtf16.length - 1));
            return rawToCanonicalUtf16[safe];
        }
    }

    private static final class AnalysisText {
        final String text;
        final int[] originalOffsets;
        /** Analysis UTF-16 offsets directly after removed whitespace (boundary evidence for rules). */
        final java.util.Set<Integer> boundaryOffsets;

        AnalysisText(String text, int[] originalOffsets) {
            this(text, originalOffsets, null);
        }

        AnalysisText(String text, int[] originalOffsets, java.util.Set<Integer> boundaryOffsets) {
            this.text = text == null ? "" : text;
            this.originalOffsets = originalOffsets == null ? new int[0] : originalOffsets;
            this.boundaryOffsets = boundaryOffsets == null ? java.util.Collections.emptySet() : boundaryOffsets;
        }

        int originalStart(int analysisStart) {
            if (originalOffsets.length == 0) return analysisStart;
            int safe = Math.max(0, Math.min(analysisStart, originalOffsets.length - 1));
            return originalOffsets[safe];
        }

        int originalEnd(int analysisEnd) {
            if (originalOffsets.length == 0) return analysisEnd;
            int safe = Math.max(0, Math.min(analysisEnd - 1, originalOffsets.length - 1));
            return originalOffsets[safe] + 1;
        }
    }

    private static final class FinalizedAnalysis {
        final String sourceText;
        final AnalysisText analysisText;
        final List<Entry> entries;
        final JapaneseReadingPolicyModels.ReadingContext readingContext;
        final List<JapaneseReadingPolicyModels.ReadingDecision> readingDecisions;
        final List<String> diagnostics;

        FinalizedAnalysis(String sourceText, AnalysisText analysisText, List<Entry> entries,
                          JapaneseReadingPolicyModels.ReadingContext readingContext,
                          List<JapaneseReadingPolicyModels.ReadingDecision> readingDecisions,
                          List<String> diagnostics) {
            this.sourceText = sourceText;
            this.analysisText = analysisText;
            this.entries = entries;
            this.readingContext = readingContext;
            this.readingDecisions = immutableCopy(readingDecisions);
            this.diagnostics = immutableCopy(diagnostics);
        }
    }

    private static final class TokenFuriganaReading {
        final String text;
        final int targetStart;
        final int targetEnd;

        TokenFuriganaReading(String text, int targetStart, int targetEnd) {
            this.text = text == null ? "" : text;
            this.targetStart = targetStart;
            this.targetEnd = targetEnd;
        }
    }

    private static final class RomajiGroup {
        final int start;
        final int end;
        final String romaji;

        RomajiGroup(int start, int end, String romaji) {
            this.start = start;
            this.end = end;
            this.romaji = romaji == null ? "" : romaji;
        }
    }

    private static final class ReadingOfRecord {
        final String kana;
        final String source;

        ReadingOfRecord(String kana, String source) {
            this.kana = kana;
            this.source = source;
        }
    }

    private static final class PinyinTrieNode {
        final Map<Integer, PinyinTrieNode> children = new HashMap<>();
        String reading;
    }

    private static final class PinyinPhraseMatch {
        final int endIndex;
        final String reading;

        PinyinPhraseMatch(int endIndex, String reading) {
            this.endIndex = endIndex;
            this.reading = reading;
        }
    }

    private static volatile Tokenizer tokenizer;
    private static volatile PinyinTrieNode pinyinPhraseTrie;
    private static volatile Map<String, List<TokenFuriganaReading>> jmdictFurigana;
    private static volatile Map<String, String> jmdictPreferredReadings;
    private static final Map<String, String> KANA = new HashMap<>();
    private static final HanyuPinyinOutputFormat PINYIN_FORMAT = new HanyuPinyinOutputFormat();
    private static final HanyuPinyinOutputFormat PINYIN_FORMAT_TONED = new HanyuPinyinOutputFormat();

    static {
        initKana();
        PINYIN_FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        PINYIN_FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        PINYIN_FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);
        // Tone-mark variant needs ü as a real unicode char (WITH_V is invalid with tone marks).
        PINYIN_FORMAT_TONED.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        PINYIN_FORMAT_TONED.setToneType(HanyuPinyinToneType.WITH_TONE_MARK);
        PINYIN_FORMAT_TONED.setVCharType(HanyuPinyinVCharType.WITH_U_UNICODE);
    }

    private SpicyJapaneseChineseProcessor() {
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return Collections.unmodifiableList(values == null ? new ArrayList<>() : new ArrayList<>(values));
    }

    private static NormalizedText normalizeWithOffsets(String rawText) {
        String raw = safe(rawText);
        int[] map = new int[raw.length() + 1];
        map[0] = 0;
        for (int i = 0; i < raw.length();) {
            int cp = raw.codePointAt(i);
            int next = i + Character.charCount(cp);
            int before = map[i];
            int normalizedLength = Normalizer.normalize(raw.substring(0, next), Normalizer.Form.NFKC).length();
            for (int j = i + 1; j < next; j++) map[j] = before;
            map[next] = normalizedLength;
            i = next;
        }
        return new NormalizedText(raw, Normalizer.normalize(raw, Normalizer.Form.NFKC), map);
    }

    private static List<FuriganaSegment> mapProviderFurigana(
            List<FuriganaSegment> furigana, NormalizedText normalized) {
        if (furigana == null) return null;
        ArrayList<FuriganaSegment> mapped = new ArrayList<>();
        for (FuriganaSegment segment : furigana) {
            if (segment == null) continue;
            mapped.add(new FuriganaSegment(normalized.canonicalOffset(segment.start),
                    normalized.canonicalOffset(segment.end), segment.reading));
        }
        return mapped;
    }

    public static boolean canRomanizeJapanese(String text) {
        return SpicyTextDetection.itemJapaneseTest(text);
    }

    public static boolean canRomanizeChinese(String text) {
        return SpicyTextDetection.itemChineseTest(text);
    }

    public static JapaneseReading analyzeJapaneseLine(String text, String fullSpacedRomaji) {
        return analyzeJapaneseLine(text, fullSpacedRomaji, null);
    }

    static JapaneseReading analyzeJapaneseLine(
            String text, String fullSpacedRomaji,
            List<JapaneseReadingPolicyModels.BoundaryEvidence> explicitBoundaries) {
        if (isBlank(text)) return null;
        NormalizedText normalized = normalizeWithOffsets(text);
        String sourceText = normalized.text;
        if (!SpicyTextDetection.itemJapaneseTest(sourceText)) return null;

        FinalizedAnalysis finalized = finalizeJapaneseAnalysis(
                normalized.rawText, sourceText, null, explicitBoundaries);
        String romaji = buildRomaji(finalized.entries);
        if (isBlank(romaji) && !isBlank(fullSpacedRomaji)) romaji = fullSpacedRomaji;
        List<FuriganaSegment> furigana = buildFurigana(sourceText, finalized.entries);
        return new JapaneseReading(sourceText, romaji, furigana, readingGroups(finalized.entries),
                finalized.readingContext, finalized.readingDecisions, finalized.diagnostics);
    }

    public static String romanizeJapaneseLine(String text) {
        JapaneseReading reading = analyzeJapaneseLine(text, null);
        if (reading == null) return text;
        return isBlank(reading.romaji) ? null : reading.romaji;
    }

    /**
     * Derive romaji from provider-supplied furigana while keeping local token
     * boundaries, particle rules, and spacing. Used when provider ruby is kept so
     * romaji cannot disagree with the displayed reading.
     */
    public static String romanizeJapaneseLineFromFurigana(String text, List<FuriganaSegment> furigana) {
        JapaneseReading reading = analyzeJapaneseLineWithProviderFurigana(text, furigana);
        return reading == null ? "" : reading.romaji;
    }

    public static JapaneseReading analyzeJapaneseLineWithProviderFurigana(String text, List<FuriganaSegment> furigana) {
        return analyzeJapaneseLineWithProviderFurigana(text, furigana, null);
    }

    static JapaneseReading analyzeJapaneseLineWithProviderFurigana(
            String text, List<FuriganaSegment> furigana,
            List<JapaneseReadingPolicyModels.BoundaryEvidence> explicitBoundaries) {
        if (isBlank(text) || furigana == null || furigana.isEmpty()) return null;
        NormalizedText normalized = normalizeWithOffsets(text);
        String sourceText = normalized.text;
        if (!SpicyTextDetection.itemJapaneseTest(sourceText)) return null;

        List<FuriganaSegment> mappedFurigana = mapProviderFurigana(furigana, normalized);
        FinalizedAnalysis finalized = finalizeJapaneseAnalysis(
                normalized.rawText, sourceText, mappedFurigana, explicitBoundaries);
        return new JapaneseReading(sourceText, buildRomaji(finalized.entries),
                buildFurigana(sourceText, finalized.entries, mappedFurigana), readingGroups(finalized.entries),
                finalized.readingContext, finalized.readingDecisions, finalized.diagnostics);
    }

    static JapaneseDebugSnapshot debugJapaneseSnapshot(String text, List<FuriganaSegment> providerFurigana) {
        return debugJapaneseSnapshot(text, providerFurigana, null);
    }

    static JapaneseDebugSnapshot debugJapaneseSnapshot(
            String text, List<FuriganaSegment> providerFurigana,
            List<JapaneseReadingPolicyModels.BoundaryEvidence> explicitBoundaries) {
        NormalizedText normalized = normalizeWithOffsets(safe(text));
        List<FuriganaSegment> mappedFurigana = mapProviderFurigana(providerFurigana, normalized);
        FinalizedAnalysis finalized = finalizeJapaneseAnalysis(
                normalized.rawText, normalized.text, mappedFurigana, explicitBoundaries);
        String romaji = buildRomaji(finalized.entries);
        return new JapaneseDebugSnapshot(normalized.text, finalized.analysisText, finalized.entries, romaji,
                buildFurigana(normalized.text, finalized.entries, mappedFurigana), finalized.readingContext,
                finalized.readingDecisions, finalized.diagnostics);
    }

    static JapaneseReading debugJapaneseProjectionForTest(
            String text, List<String> tokenParts,
            List<JapaneseReadingPolicyModels.BoundaryEvidence> explicitBoundaries) {
        String sourceText = Normalizer.normalize(safe(text), Normalizer.Form.NFKC);
        ArrayList<Entry> entries = new ArrayList<>();
        int offset = 0;
        for (String rawPart : tokenParts == null ? Collections.singletonList(sourceText) : tokenParts) {
            String part = Normalizer.normalize(safe(rawPart), Normalizer.Form.NFKC);
            if (part.isEmpty() || !sourceText.regionMatches(offset, part, 0, part.length())) {
                throw new IllegalArgumentException("projection token parts must exactly cover input");
            }
            Entry entry = new Entry();
            entry.tokenIndex = entries.size();
            entry.analysisStart = offset;
            entry.analysisEnd = offset + part.length();
            entry.start = entry.analysisStart;
            entry.end = entry.analysisEnd;
            entry.sourceText = sourceText;
            entry.surface = part;
            entry.readingKana = kataToHira(part);
            entry.dictionaryReadingKana = entry.readingKana;
            entry.dictionaryReadingSource = "authored-kana";
            entry.readingReason = "authored-kana";
            entry.boundaryBefore = offset == 0;
            entries.add(entry);
            offset += part.length();
        }
        if (offset != sourceText.length()) {
            throw new IllegalArgumentException("projection token parts must exactly cover input");
        }
        applyExplicitBoundaries(sourceText, entries, explicitBoundaries);
        finalizeCrossTokenKana(entries);
        for (Entry entry : entries) entry.romaji = entryRomaji(entry);
        applyRomajiSokuonProjection(entries);
        ArrayList<String> diagnostics = new ArrayList<>();
        for (Entry entry : entries) {
            if (!isBlank(entry.diagnosticId) && !diagnostics.contains(entry.diagnosticId)) {
                diagnostics.add(entry.diagnosticId);
            }
        }
        return new JapaneseReading(sourceText, buildRomaji(entries), Collections.emptyList(),
                readingGroups(entries), null, null, diagnostics);
    }

    static JapaneseDebugSnapshot debugJapaneseFallbackSnapshotForTest(String text, String diagnosticId) {
        String sourceText = Normalizer.normalize(safe(text), Normalizer.Form.NFKC);
        AnalysisText analysis = analysisTextForJapanese(sourceText);
        List<Entry> entries = fallbackEntries(sourceText, analysis, diagnosticId);
        for (Entry entry : entries) entry.romaji = entryRomaji(entry);
        ArrayList<String> diagnostics = new ArrayList<>();
        diagnostics.add(diagnosticId);
        return new JapaneseDebugSnapshot(sourceText, analysis, entries, buildRomaji(entries),
                buildFurigana(sourceText, entries),
                buildReadingContext(sourceText, sourceText, analysis, entries, null, null),
                Collections.emptyList(), diagnostics);
    }

    private static void applyProviderFuriganaOverrides(String sourceText, List<Entry> entries, List<FuriganaSegment> furigana) {
        ArrayList<FuriganaSegment> sorted = new ArrayList<>(furigana);
        sorted.sort((a, b) -> Integer.compare(a.start, b.start));

        int previousEnd = -1;
        for (FuriganaSegment segment : sorted) {
            if (segment == null || isBlank(segment.reading) || segment.start < 0
                    || segment.end <= segment.start || segment.end > sourceText.length()
                    || segment.start < previousEnd || !isKanjiRun(sourceText, segment.start, segment.end)) return;
            previousEnd = segment.end;
        }

        for (Entry entry : entries) {
            if (!SpicyTextDetection.itemJapaneseTest(entry.surface)) continue;
            String reading = readingFromProviderFurigana(sourceText, entry.start, entry.end, sorted);
            if (isBlank(reading) || !reading.equals(entry.dictionaryReadingKana)) continue;
            entry.providerMatched = true;
            entry.providerReadingKana = reading;
            for (int i = 0; i < sorted.size(); i++) {
                FuriganaSegment segment = sorted.get(i);
                if (segment.start >= entry.start && segment.end <= entry.end) {
                    entry.providerEvidenceIds.add("provider-" + i);
                }
            }
            if (!isBlank(entry.ruleId)) continue;
            String previousCandidateId = fallbackCandidateId(entry);
            entry.readingKana = reading;
            entry.readingReason = "providerRubyValidated";
            entry.providerValidated = true;
            ArrayList<String> evidenceIds = new ArrayList<>(entry.providerEvidenceIds);
            evidenceIds.add(tokenId(entry));
            entry.decisions.add(new JapaneseReadingPolicyModels.ReadingDecision(
                    "select", "resolved", "provider-ruby-validated", null, null,
                    codePointRange(entry, entry.start, entry.end), previousCandidateId,
                    tokenId(entry) + ":provider", fallbackCandidateId(entry), reading,
                    evidenceIds, null));
        }
    }

    private static boolean isKanjiRun(String text, int start, int end) {
        for (int i = start; i < end;) {
            int cp = text.codePointAt(i);
            if (!isCjkCodePoint(cp)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    private static String readingFromProviderFurigana(String sourceText, int start, int end, List<FuriganaSegment> furigana) {
        StringBuilder reading = new StringBuilder();
        boolean usedProvider = false;
        int pos = start;
        while (pos < end) {
            int cp = sourceText.codePointAt(pos);
            int cpLen = Character.charCount(cp);
            String ch = sourceText.substring(pos, pos + cpLen);
            FuriganaSegment segment = furiganaSegmentAt(furigana, pos);
            if (isKanjiChar(ch) && segment != null && segment.start >= start && segment.end <= end
                    && segment.start <= pos && segment.end > pos) {
                if (pos == segment.start) {
                    reading.append(kataToHira(segment.reading));
                    usedProvider = true;
                }
                pos = Math.min(end, segment.end);
                continue;
            }
            if (isKanjiChar(ch)) return null;
            if (isKanaChar(ch)) reading.append(kataToHira(ch));
            pos += cpLen;
        }
        return usedProvider ? reading.toString() : null;
    }

    private static FuriganaSegment furiganaSegmentAt(List<FuriganaSegment> furigana, int index) {
        for (FuriganaSegment segment : furigana) {
            if (segment == null || segment.end <= segment.start) continue;
            if (index >= segment.start && index < segment.end) return segment;
        }
        return null;
    }

    public static List<String> romanizeJapaneseSyllables(String lineText, List<String> syllableTexts) {
        ArrayList<String> out = new ArrayList<>();
        if (syllableTexts == null) return out;
        for (int i = 0; i < syllableTexts.size(); i++) out.add("");
        if (isBlank(lineText) || syllableTexts.isEmpty()) return out;

        String sourceText = Normalizer.normalize(lineText, Normalizer.Form.NFKC);
        if (!SpicyTextDetection.itemJapaneseTest(sourceText)) return out;

        List<Entry> entries = buildEntries(sourceText, analysisTextForJapanese(sourceText));
        if (entries.isEmpty()) return out;
        return mapGroupsToSyllables(sourceText, readingGroups(entries), syllableTexts);
    }

    /**
     * Finalized-analysis projection: consumes an already-analyzed reading's groups
     * instead of tokenizing the line a second time. Callers holding a JapaneseReading
     * (render planning, segment romanization) must use this overload.
     */
    public static List<String> romanizeJapaneseSyllables(JapaneseReading reading, List<String> syllableTexts) {
        ArrayList<String> out = new ArrayList<>();
        if (syllableTexts == null) return out;
        for (int i = 0; i < syllableTexts.size(); i++) out.add("");
        if (reading == null || reading.groups.isEmpty() || syllableTexts.isEmpty()) return out;
        return mapGroupsToSyllables(reading.sourceText, reading.groups, syllableTexts);
    }

    private static List<ReadingGroup> readingGroups(List<Entry> entries) {
        ArrayList<ReadingGroup> out = new ArrayList<>();
        for (RomajiGroup group : romajiGroups(entries)) {
            out.add(new ReadingGroup(group.start, group.end, group.romaji));
        }
        return out;
    }

    private static List<String> mapGroupsToSyllables(String sourceText, List<ReadingGroup> groups,
                                                     List<String> syllableTexts) {
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < syllableTexts.size(); i++) out.add("");
        int syllPos = 0;
        for (int si = 0; si < syllableTexts.size(); si++) {
            String syllableText = Normalizer.normalize(safe(syllableTexts.get(si)), Normalizer.Form.NFKC);
            while (syllPos < sourceText.length() && Character.isWhitespace(sourceText.charAt(syllPos))) syllPos++;
            int syllStart = syllPos;
            int syllEnd = Math.min(sourceText.length(), syllStart + syllableText.length());
            syllPos = syllEnd;

            StringBuilder romaji = new StringBuilder();
            for (ReadingGroup group : groups) {
                if (isBlank(group.romaji)) continue;
                if (group.end <= syllStart || group.start >= syllEnd) continue; // no overlap
                // Emit each full-line analysis group once, at the provider chunk where it begins.
                // Continuation chunks stay blank, so provider timing cannot split morphology
                // (殺/した -> koroshita, not satsu/shita or koroshi/ta).
                if (group.start >= syllStart) {
                    if (romaji.length() > 0) romaji.append(' ');
                    romaji.append(group.romaji);
                }
            }
            out.set(si, normalizeSpaces(romaji.toString()));
        }
        return out;
    }

    private static List<RomajiGroup> romajiGroups(List<Entry> entries) {
        ArrayList<RomajiGroup> groups = new ArrayList<>();
        if (entries == null || entries.isEmpty()) return groups;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry == null || isBlank(entry.romaji)) continue;
            StringBuilder romaji = new StringBuilder();
            int start = entry.start;
            int end = entry.end;
            Entry prev = i > 0 ? entries.get(i - 1) : null;
            appendToken(romaji, entry.romaji, shouldNoSpaceBefore(entry, prev));
            int j = i;
            while (j + 1 < entries.size()) {
                Entry next = entries.get(j + 1);
                if (next == null || !shouldNoSpaceBefore(next, entries.get(j))) break;
                end = Math.max(end, next.end);
                appendToken(romaji, next.romaji, true);
                j++;
            }
            groups.add(new RomajiGroup(start, end, normalizeSpaces(romaji.toString())));
            i = j;
        }
        return groups;
    }

    public static String romanizeChineseLine(String text, String mode) {
        return romanizeChineseLine(text, mode, false);
    }

    public static String romanizeChineseLine(String text, String mode, boolean tones) {
        if ("jyutping".equalsIgnoreCase(mode) || "cantonese".equalsIgnoreCase(mode)) {
            String jyutping = JyutpingRomanizer.romanize(text);
            // Strip the trailing tone digit on each jyutping syllable when tones are off
            // (only digits attached to a romanized letter, so Latin passthrough is safe).
            return tones ? jyutping : (jyutping == null ? null : jyutping.replaceAll("(?<=[a-zA-Z])[1-6]", ""));
        }
        return romanizeChinesePinyinLine(text, tones);
    }

    private static List<Entry> buildEntries(String sourceText) {
        return finalizeJapaneseAnalysis(sourceText, sourceText, null, null).entries;
    }

    private static List<Entry> buildEntries(String sourceText, AnalysisText analysisText) {
        return finalizeJapaneseAnalysis(sourceText, sourceText, analysisText, null, null).entries;
    }

    private static FinalizedAnalysis finalizeJapaneseAnalysis(
            String rawSourceText, String sourceText, List<FuriganaSegment> providerFurigana,
            List<JapaneseReadingPolicyModels.BoundaryEvidence> explicitBoundaries) {
        return finalizeJapaneseAnalysis(rawSourceText, sourceText,
                analysisTextForJapanese(sourceText, explicitBoundaries),
                providerFurigana, explicitBoundaries);
    }

    private static FinalizedAnalysis finalizeJapaneseAnalysis(
            String rawSourceText, String sourceText, AnalysisText analysisText,
            List<FuriganaSegment> providerFurigana,
            List<JapaneseReadingPolicyModels.BoundaryEvidence> explicitBoundaries) {
        List<Entry> entries = buildRawEntries(sourceText, analysisText);
        applyExplicitBoundaries(sourceText, entries, explicitBoundaries);
        applyAnalyzerParityCorrections(entries);
        applyProductiveOverrides(entries);
        if (providerFurigana != null && !providerFurigana.isEmpty()) {
            applyProviderFuriganaOverrides(sourceText, entries, providerFurigana);
        }
        applyReadingDefaults(entries);
        finalizeCrossTokenKana(entries);
        for (Entry entry : entries) entry.romaji = entryRomaji(entry);
        applyRomajiSokuonProjection(entries);
        for (Entry entry : entries) {
            if (entry.providerValidated && !safe(entry.readingKana).equals(safe(entry.providerReadingKana))) {
                entry.providerValidated = false;
            }
        }
        ArrayList<JapaneseReadingPolicyModels.ReadingDecision> decisions = new ArrayList<>();
        ArrayList<String> diagnostics = new ArrayList<>();
        for (Entry entry : entries) {
            decisions.addAll(entry.decisions);
            if (!isBlank(entry.diagnosticId) && !diagnostics.contains(entry.diagnosticId)) {
                diagnostics.add(entry.diagnosticId);
            }
        }
        JapaneseReadingPolicyModels.ReadingContext context = buildReadingContext(
                rawSourceText, sourceText, analysisText, entries, providerFurigana, explicitBoundaries);
        return new FinalizedAnalysis(sourceText, analysisText, entries, context, decisions, diagnostics);
    }

    /**
     * Normalize a known Java/TypeScript UniDic lattice split without promoting tokenizer internals
     * into language policy. Browser UniDic emits 上目/遣い; kuromoji-unidic 0.9 emits 上/目遣い.
     * Both project to the reviewed product reading 上目=うわめ + 遣い=つかい.
     */
    private static void applyAnalyzerParityCorrections(List<Entry> entries) {
        for (int i = 0; i + 1 < entries.size(); i++) {
            Entry first = entries.get(i);
            Entry second = entries.get(i + 1);
            if (!"上".equals(first.surface) || !"目遣い".equals(second.surface)
                    || !adjacentWithoutHardBoundary(first, second)) continue;
            first.readingKana = "うわめ";
            second.readingKana = "つかい";
            first.readingReason = second.readingReason = "analyzer-parity:unidic-browser-tokenization";
        }
    }

    private static void applyExplicitBoundaries(
            String sourceText, List<Entry> entries,
            List<JapaneseReadingPolicyModels.BoundaryEvidence> boundaries) {
        if (boundaries == null || boundaries.isEmpty()) return;
        int codePointLength = sourceText.codePointCount(0, sourceText.length());
        for (JapaneseReadingPolicyModels.BoundaryEvidence boundary : boundaries) {
            if (boundary == null || !("authored-whitespace".equals(boundary.kind)
                    || "provider-fragment".equals(boundary.kind))) continue;
            int cpOffset = Math.max(0, Math.min(boundary.offset, codePointLength));
            int utf16Offset = sourceText.offsetByCodePoints(0, cpOffset);
            for (Entry entry : entries) {
                if (entry.start != utf16Offset) continue;
                entry.boundaryBefore = true;
                if ("hard".equals(boundary.strength)) entry.hardBoundaryBefore = true;
            }
        }
    }

    private static List<Entry> buildRawEntries(String sourceText, AnalysisText analysisText) {
        String analysisSource = analysisText == null ? sourceText : analysisText.text;
        List<Token> tokens;
        try {
            tokens = tokenizer().tokenize(analysisSource);
        } catch (Throwable t) {
            return fallbackEntries(sourceText, analysisText, "tokenizer.exception");
        }
        List<Entry> entries = new ArrayList<>();
        int charPos = 0;
        boolean pendingWhitespace = false;
        for (Token token : tokens) {
            String surface = safe(token.getSurface());
            boolean skippedWhitespace = pendingWhitespace;
            pendingWhitespace = false;
            if (!surface.isEmpty() && (charPos + surface.length() > analysisSource.length()
                    || !analysisSource.regionMatches(charPos, surface, 0, surface.length()))) {
                while (charPos < analysisSource.length()
                        && Character.isWhitespace(analysisSource.codePointAt(charPos))) {
                    skippedWhitespace = true;
                    charPos += Character.charCount(analysisSource.codePointAt(charPos));
                }
            }
            int end = charPos + surface.length();
            if (surface.isEmpty() || end > analysisSource.length()
                    || !analysisSource.regionMatches(charPos, surface, 0, surface.length())) {
                return fallbackEntries(sourceText, analysisText, "tokenizer.incomplete-coverage");
            }
            if (surface.trim().isEmpty()) {
                charPos = end;
                pendingWhitespace = true;
                continue;
            }
            Entry entry = new Entry();
            entry.analysisText = analysisText;
            entry.tokenIndex = entries.size();
            entry.analysisStart = charPos;
            entry.analysisEnd = end;
            entry.start = analysisText == null ? charPos : analysisText.originalStart(charPos);
            entry.end = analysisText == null ? end : analysisText.originalEnd(end);
            entry.sourceText = sourceText;
            entry.surface = surface;
            entry.token = token;
            entry.boundaryBefore = charPos == 0 || skippedWhitespace
                    || (analysisText != null && analysisText.boundaryOffsets.contains(charPos));
            entry.hardBoundaryBefore = isPunctuationSurface(surface);
            ReadingOfRecord selected = selectReadingOfRecord(token, surface);
            entry.readingKana = selected.kana;
            entry.dictionaryReadingKana = entry.readingKana;
            entry.dictionaryReadingSource = selected.source;
            entry.readingReason = selected.source;
            entries.add(entry);
            charPos = end;
        }
        while (charPos < analysisSource.length()
                && Character.isWhitespace(analysisSource.codePointAt(charPos))) {
            charPos += Character.charCount(analysisSource.codePointAt(charPos));
        }
        if (charPos != analysisSource.length()) {
            return fallbackEntries(sourceText, analysisText, "tokenizer.incomplete-coverage");
        }
        return entries;
    }

    private static List<Entry> fallbackEntries(String sourceText, AnalysisText analysisText, String diagnosticId) {
        ArrayList<Entry> entries = new ArrayList<>();
        if (isBlank(sourceText)) return entries;
        Entry entry = new Entry();
        entry.analysisText = analysisText;
        entry.tokenIndex = 0;
        entry.analysisStart = 0;
        entry.analysisEnd = analysisText == null ? sourceText.length() : analysisText.text.length();
        entry.start = 0;
        entry.end = sourceText.length();
        entry.sourceText = sourceText;
        entry.surface = sourceText;
        entry.dictionaryReadingSource = "passthrough";
        entry.readingReason = "passthrough";
        entry.boundaryBefore = true;
        entry.diagnosticId = diagnosticId;
        entries.add(entry);
        return entries;
    }

    private static JapaneseReadingPolicyModels.ReadingContext buildReadingContext(
            String rawSourceText, String sourceText, AnalysisText analysisText, List<Entry> entries,
            List<FuriganaSegment> providerFurigana,
            List<JapaneseReadingPolicyModels.BoundaryEvidence> explicitBoundaries) {
        ArrayList<JapaneseReadingPolicyModels.ReadingTokenEvidence> tokens = new ArrayList<>();
        for (Entry entry : entries) {
            ArrayList<JapaneseReadingPolicyModels.ReadingCandidate> candidates = new ArrayList<>();
            if (!isBlank(entry.dictionaryReadingKana)) {
                candidates.add(new JapaneseReadingPolicyModels.ReadingCandidate(
                        tokenId(entry) + ":" + entry.dictionaryReadingSource, entry.dictionaryReadingKana,
                        entry.dictionaryReadingSource, "accepted"));
            } else {
                candidates.add(new JapaneseReadingPolicyModels.ReadingCandidate(
                        tokenId(entry) + ":passthrough", entry.surface, "passthrough", "accepted"));
            }
            if (entry.providerValidated && !isBlank(entry.readingKana)) {
                candidates.add(new JapaneseReadingPolicyModels.ReadingCandidate(
                        tokenId(entry) + ":provider", entry.readingKana, "provider", "accepted"));
            }
            if (!isBlank(entry.ruleId) && !isBlank(entry.readingKana)) {
                candidates.add(new JapaneseReadingPolicyModels.ReadingCandidate(
                        tokenId(entry) + ":rule:" + entry.ruleId, entry.readingKana,
                        "reviewed-policy", "accepted"));
            }
            Token token = entry.token;
            tokens.add(new JapaneseReadingPolicyModels.ReadingTokenEvidence(
                    tokenId(entry), entry.surface, codePointRange(entry, entry.start, entry.end),
                    token == null ? null : safe(token.getPartOfSpeechLevel1()),
                    token == null ? null : safe(token.getPartOfSpeechLevel2()),
                    token == null ? null : safe(token.getPartOfSpeechLevel3()),
                    token == null ? null : safe(token.getPartOfSpeechLevel4()),
                    token == null ? null : safe(token.getLemma()),
                    token == null ? null : safe(token.getWrittenBaseForm()),
                    null,
                    token == null ? null : kataToHira(safe(token.getLemmaReadingForm())),
                    token == null ? null : safe(token.getPronunciation()),
                    token == null ? null : safe(token.getConjugationType()),
                    token == null ? null : safe(token.getConjugationForm()), candidates));
        }

        ArrayList<JapaneseReadingPolicyModels.BoundaryEvidence> boundaries = new ArrayList<>();
        boundaries.add(new JapaneseReadingPolicyModels.BoundaryEvidence(0, "line", "hard", null));
        boundaries.add(new JapaneseReadingPolicyModels.BoundaryEvidence(
                sourceText.codePointCount(0, sourceText.length()), "line", "hard", null));
        if (analysisText != null) {
            for (Integer analysisOffset : analysisText.boundaryOffsets) {
                int displayOffset = analysisText.originalStart(analysisOffset);
                boundaries.add(new JapaneseReadingPolicyModels.BoundaryEvidence(
                        sourceText.codePointCount(0, Math.max(0, Math.min(displayOffset, sourceText.length()))),
                        "authored-whitespace", "soft", null));
            }
        }
        for (int i = 1; i < entries.size(); i++) {
            Entry previous = entries.get(i - 1);
            Entry current = entries.get(i);
            if (current.start > previous.end) {
                String gap = sourceText.substring(previous.end, current.start);
                if (gap.trim().isEmpty()) {
                    boundaries.add(new JapaneseReadingPolicyModels.BoundaryEvidence(
                            sourceText.codePointCount(0, current.start),
                            "authored-whitespace", "hard", null));
                }
            }
        }
        for (Entry entry : entries) {
            if (entry.surface != null && entry.surface.matches("^[。、？！…・「」『』（）().?!,]+$")) {
                boundaries.add(new JapaneseReadingPolicyModels.BoundaryEvidence(
                        sourceText.codePointCount(0, Math.max(0, Math.min(entry.start, sourceText.length()))),
                        "punctuation", "hard", null));
            }
        }

        ArrayList<JapaneseReadingPolicyModels.ProviderReadingEvidence> providerEvidence = new ArrayList<>();
        if (providerFurigana != null) {
            ArrayList<FuriganaSegment> sorted = new ArrayList<>(providerFurigana);
            sorted.sort((a, b) -> Integer.compare(a.start, b.start));
            for (int i = 0; i < sorted.size(); i++) {
                FuriganaSegment segment = sorted.get(i);
                Entry owner = null;
                for (Entry entry : entries) {
                    if (segment.start >= entry.start && segment.end <= entry.end) {
                        owner = entry;
                        break;
                    }
                }
                boolean accepted = owner != null && owner.providerValidated
                        && owner.providerEvidenceIds.contains("provider-" + i);
                boolean matched = owner != null && owner.providerMatched
                        && owner.providerEvidenceIds.contains("provider-" + i);
                int safeStart = Math.max(0, Math.min(segment.start, sourceText.length()));
                int safeEnd = Math.max(safeStart, Math.min(segment.end, sourceText.length()));
                providerEvidence.add(new JapaneseReadingPolicyModels.ProviderReadingEvidence(
                        "provider-" + i, "provider-ruby",
                        new JapaneseReadingPolicyModels.CodePointRange(
                                sourceText.codePointCount(0, safeStart), sourceText.codePointCount(0, safeEnd)),
                        accepted && owner != null ? tokenId(owner) + ":provider" : null,
                        kataToHira(segment.reading), accepted ? "accepted" : "rejected",
                        accepted ? "provider-ruby-validated"
                                : matched ? "higher-priority-policy-decision"
                                : "invalid-partial-or-reading-mismatch"));
            }
        }

        if (explicitBoundaries != null) {
            for (JapaneseReadingPolicyModels.BoundaryEvidence boundary : explicitBoundaries) {
                if (boundary != null) boundaries.add(boundary);
            }
        }
        java.util.TreeMap<String, JapaneseReadingPolicyModels.BoundaryEvidence> uniqueBoundaries = new java.util.TreeMap<>();
        for (JapaneseReadingPolicyModels.BoundaryEvidence boundary : boundaries) {
            String key = boundary.offset + ":" + boundary.kind;
            JapaneseReadingPolicyModels.BoundaryEvidence existing = uniqueBoundaries.get(key);
            if (existing == null || ("hard".equals(boundary.strength) && !"hard".equals(existing.strength))) {
                uniqueBoundaries.put(key, boundary);
            }
        }
        boundaries = new ArrayList<>(uniqueBoundaries.values());

        ArrayList<String> capabilities = new ArrayList<>();
        capabilities.add("source-display-map");
        if (entries.stream().anyMatch(entry -> entry.token != null
                && !isBlank(entry.token.getPartOfSpeechLevel4()))) capabilities.add("pos1-4");
        if (entries.stream().anyMatch(entry -> entry.token != null && !isBlank(entry.token.getLemma()))) {
            capabilities.add("lemma");
        }
        if (entries.stream().anyMatch(entry -> entry.token != null && !isBlank(entry.token.getPronunciation()))) {
            capabilities.add("pronunciation");
        }
        if (boundaries.stream().anyMatch(boundary -> "authored-whitespace".equals(boundary.kind))) {
            capabilities.add("authored-boundaries");
        }
        if (boundaries.stream().anyMatch(boundary -> "provider-fragment".equals(boundary.kind))) {
            capabilities.add("provider-boundaries");
        }
        if (providerFurigana != null && !providerFurigana.isEmpty()) capabilities.add("provider-readings");
        return new JapaneseReadingPolicyModels.ReadingContext(
                sourceText, rawSourceText, tokens, boundaries, providerEvidence,
                Collections.emptyList(), capabilities);
    }

    private static AnalysisText analysisTextForJapanese(String sourceText) {
        return analysisTextForJapanese(sourceText, null);
    }

    private static AnalysisText analysisTextForJapanese(
            String sourceText,
            List<JapaneseReadingPolicyModels.BoundaryEvidence> explicitBoundaries) {
        if (isBlank(sourceText)) return new AnalysisText(sourceText, null);
        java.util.HashSet<Integer> softWhitespace = new java.util.HashSet<>();
        int codePointLength = sourceText.codePointCount(0, sourceText.length());
        if (explicitBoundaries != null) for (JapaneseReadingPolicyModels.BoundaryEvidence boundary : explicitBoundaries) {
            if (boundary == null || !"inferred-soft".equals(boundary.kind)
                    || !"soft".equals(boundary.strength)) continue;
            int codePointOffset = Math.max(0, Math.min(boundary.offset, codePointLength));
            int cursor = sourceText.offsetByCodePoints(0, codePointOffset);
            while (cursor > 0) {
                int previous = sourceText.codePointBefore(cursor);
                if (!Character.isWhitespace(previous)) break;
                cursor -= Character.charCount(previous);
                if (hasJapaneseBeforeAndAfter(sourceText, cursor, cursor + Character.charCount(previous))) {
                    softWhitespace.add(cursor);
                }
            }
        }
        return buildJapaneseAnalysisText(sourceText, softWhitespace);
    }

    /**
     * Package-compatible whitespace policy: canonical whitespace is authored and hard by default.
     * Only adapter-declared inferred-soft whitespace is removed before analysis.
     */
    private static AnalysisText buildJapaneseAnalysisText(
            String sourceText, java.util.Set<Integer> selectedWhitespace) {
        StringBuilder normalized = new StringBuilder();
        ArrayList<Integer> offsets = new ArrayList<>();
        java.util.HashSet<Integer> boundaries = new java.util.HashSet<>();
        for (int i = 0; i < sourceText.length(); ) {
            int cp = sourceText.codePointAt(i);
            int len = Character.charCount(cp);
            boolean internalWhitespace = Character.isWhitespace(cp)
                    && hasJapaneseBeforeAndAfter(sourceText, i, i + len);
            boolean remove = internalWhitespace
                    && selectedWhitespace != null && selectedWhitespace.contains(i);
            if (remove) {
                boundaries.add(normalized.length());
                i += len;
                continue;
            }
            normalized.appendCodePoint(cp);
            for (int j = 0; j < len; j++) offsets.add(i + j);
            i += len;
        }
        int[] map = new int[offsets.size()];
        for (int i = 0; i < offsets.size(); i++) map[i] = offsets.get(i);
        return new AnalysisText(normalized.toString(), map, boundaries);
    }

    private static boolean hasJapaneseBeforeAndAfter(String text, int whitespaceStart, int whitespaceEnd) {
        int before = previousCodePoint(text, whitespaceStart);
        int after = nextCodePoint(text, whitespaceEnd);
        return isJapaneseCodePoint(before) && isJapaneseCodePoint(after);
    }

    private static int previousCodePoint(String text, int index) {
        int i = index;
        while (i > 0) {
            int cp = text.codePointBefore(i);
            if (!Character.isWhitespace(cp)) return cp;
            i -= Character.charCount(cp);
        }
        return -1;
    }

    private static int nextCodePoint(String text, int index) {
        int i = index;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (!Character.isWhitespace(cp)) return cp;
            i += Character.charCount(cp);
        }
        return -1;
    }

    private static boolean isJapaneseCodePoint(int cp) {
        if (cp < 0) return false;
        return (cp >= 0x3040 && cp <= 0x30FF) || isCjkCodePoint(cp);
    }

    private static boolean isCjkCodePoint(int cp) {
        return (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x4E00 && cp <= 0x9FFF) || cp == 0x3005;
    }

    /**
     * Orthographic-kana reading-of-record for a token, in hiragana.
     *
     * UniDic pron is phonological (long vowels as ー: ホントー); lemma reading is
     * orthographic (ホントウ) but belongs to the dictionary form. We take pron and
     * resolve each ー to orthographic kana, trusting the lemma reading at the same
     * position when its prefix matches (handles おお words like オオキナ), defaulting
     * to spelling convention otherwise (お-row→う, え-row→い, others repeat the vowel).
     * Real loanword ー (スーパー) survives because the lemma reading keeps it.
     */
    private static ReadingOfRecord selectReadingOfRecord(Token token, String surface) {
        if (!SpicyTextDetection.itemJapaneseTest(surface)) return new ReadingOfRecord(null, "passthrough");
        String pron = safe(token.getPronunciation());
        String lemmaYomi = safe(token.getLemmaReadingForm());
        if (isKanaOnly(surface) && (!surface.contains("ー") || isBlank(pron) || "*".equals(pron))) {
            return new ReadingOfRecord(kataToHira(surface), "authored-kana");
        }
        if (isBlank(pron) || "*".equals(pron)) {
            return new ReadingOfRecord(null, "passthrough");
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pron.length(); i++) {
            char c = pron.charAt(i);
            if (c != 'ー') {
                out.append(c);
                continue;
            }
            char resolved = 0;
            if (i < lemmaYomi.length() && pron.substring(0, i).equals(lemmaYomi.substring(0, i))) {
                resolved = lemmaYomi.charAt(i);
            }
            if (resolved == 0) resolved = orthographicLongVowel(i > 0 ? pron.charAt(i - 1) : 0);
            out.append(resolved == 0 ? c : resolved);
        }
        return new ReadingOfRecord(kataToHira(out.toString()), "analyzer-pronunciation");
    }

    private static String readingOfRecord(Token token, String surface) {
        return selectReadingOfRecord(token, surface).kana;
    }

    private static char orthographicLongVowel(char prevKatakana) {
        char hira = prevKatakana >= 'ァ' && prevKatakana <= 'ヶ' ? (char) (prevKatakana - 0x60) : prevKatakana;
        String mapped = KANA.get(String.valueOf(hira));
        if (mapped == null || mapped.isEmpty()) return 0;
        switch (mapped.charAt(mapped.length() - 1)) {
            case 'a': return 'ア';
            case 'i': return 'イ';
            case 'u': return 'ウ';
            case 'e': return 'イ'; // え-row long vowels are conventionally spelled えい
            case 'o': return 'ウ'; // お-row long vowels are conventionally spelled おう
            default: return 0;
        }
    }

    /**
     * The ONLY hand-maintained reading layer, implementing the shared reading policy
     * (nihongo-grammar-lab japanese-reading-policy; vendored conformance corpus under
     * app/src/test/resources/japanese-reading-policy). The dictionary owns grammar and
     * context; a rule fires here solely when the dictionary cannot know the answer.
     * Every decision carries a stable cross-product ruleId; the desktop TypeScript
     * processor implements the same rules natively. Kana decisions all complete before
     * any romaji is derived. Verified against UniDic 2.1.2 output
     * (docs/JAPANESE_NLP_AUDIT_AND_PLAN.md §2.4).
     *
     * Song-specific aesthetic readings (gikun: 運命→さだめ etc.) are undecidable
     * from text and belong in a future per-track override store, not here.
     *
     * Known low-frequency reading edges are documented in
     * docs/ROMANIZATION_AUDIT_BACKLOG.md JP-3.
     */
    private static void applyProductiveOverrides(List<Entry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            Token token = entry.token;
            if (token == null) continue;
            Entry prevEntry = i > 0 ? entries.get(i - 1) : null;
            Entry nextEntry = i + 1 < entries.size() ? entries.get(i + 1) : null;

            boolean afterNochi = prevEntry != null && "のち".equals(prevEntry.surface)
                    && adjacentWithoutHardBoundary(prevEntry, entry);
            if (!afterNochi && prevEntry != null && "ち".equals(prevEntry.surface) && i > 1) {
                Entry beforeNochi = entries.get(i - 2);
                afterNochi = "の".equals(beforeNochi.surface)
                        && adjacentWithoutHardBoundary(beforeNochi, prevEntry)
                        && adjacentWithoutHardBoundary(prevEntry, entry);
            }
            if ("雨".equals(entry.surface) && afterNochi) {
                decide(entry, "あめ", "ja.reading.context.ame-after-nochi",
                        "rule:ja.reading.context.ame-after-nochi");
                continue;
            }

            // UniDic 2.1.2 lacks the kanji orthography 響めく for どよめく (its own
            // lemma for どよめき is 響動めき; JMdict marks the word usually-kana), so
            // the tokenizer falls back to 響(ヒビキ)+めく suffix. 響 directly before
            // the めく suffix can only read どよ; standalone 響/響く are untouched.
            if ("響".equals(entry.surface) && nextEntry != null && nextEntry.token != null
                    && entry.end == nextEntry.start && !nextEntry.boundaryBefore
                    && "接尾辞".equals(safe(nextEntry.token.getPartOfSpeechLevel1()))
                    && "めく".equals(safe(nextEntry.token.getLemma()))) {
                decide(entry, "どよ", "ja.reading.lexical.doyomeku", "lexicalOverride:doyomeku");
                continue;
            }

            // UniDic prefers the formal ワタクシ for bare 私; sung Japanese is
            // essentially always わたし. POS-guarded so 私立 etc. are untouched.
            if ("私".equals(entry.surface) && "代名詞".equals(token.getPartOfSpeechLevel1())) {
                decide(entry, "わたし", "ja.reading.register.watashi", "lexicalOverride:watashiPronoun");
                continue;
            }

            // UniDic can tag bare 君 as the honorific suffix reading クン. As an independent
            // pronoun in lyrics it should read きみ; suffix use remains "kun" in 田中君.
            if ("君".equals(entry.surface) && "代名詞".equals(token.getPartOfSpeechLevel1())) {
                decide(entry, "きみ", "ja.reading.context.kimi-kun", "lexicalOverride:kimiPronoun");
                continue;
            }

            // Boundary-separated 君 (provider/authored whitespace removed before analysis) is
            // the lyric pronoun; the honorific くん needs an attached person name (時 君 ≠ 時君).
            if ("君".equals(entry.surface) && "接尾辞".equals(safe(token.getPartOfSpeechLevel1()))
                    && entry.boundaryBefore) {
                decide(entry, "きみ", "ja.reading.context.kimi-kun", "rule:ja.reading.context.kimi-kun");
                continue;
            }

            // Rendaku: 〜方 as a plural-person suffix directly after a pronoun reads
            // がた (あなた方/君方). kuromoji-unidic 2.1.2 emits unvoiced カタ, tagged
            // 接尾辞 or 名詞 depending on context. Demonstratives (この方) are 連体詞,
            // so "kono kata" is untouched.
            if ("方".equals(entry.surface) && "かた".equals(entry.readingKana) && prevEntry != null) {
                String pos1 = safe(token.getPartOfSpeechLevel1());
                Token prev = prevEntry.token;
                if (("接尾辞".equals(pos1) || "名詞".equals(pos1))
                        && prev != null && "代名詞".equals(prev.getPartOfSpeechLevel1())) {
                    decide(entry, "がた", "ja.reading.context.gata-after-pronoun", "lexicalOverride:gataAfterPronoun");
                    continue;
                }
            }

            // 何 as an independent pronoun before a case particle reads なに; lexicalized
            // なん constructions (何でも/何です/counters) keep the analyzer reading.
            if ("何".equals(entry.surface) && "なん".equals(entry.readingKana) && nextEntry != null
                    && nextEntry.token != null && !nextEntry.boundaryBefore
                    && "助詞".equals(safe(nextEntry.token.getPartOfSpeechLevel1()))
                    && "格助詞".equals(safe(nextEntry.token.getPartOfSpeechLevel2()))
                    && ("が".equals(nextEntry.surface) || "を".equals(nextEntry.surface)
                        || "に".equals(nextEntry.surface) || "から".equals(nextEntry.surface))) {
                decide(entry, "なに", "ja.reading.context.nan-nani", "rule:ja.reading.context.nan-nani");
                continue;
            }

            // 時 after a finite clause or determiner is the temporal noun とき, not the
            // clock counter じ. Compounds (時計/時代/lexical 一時) stay analyzer-owned.
            if ("時".equals(entry.surface) && prevEntry != null
                    && prevEntry.token != null && !isNumericEntry(prevEntry) && !entry.boundaryBefore) {
                String prevPos1 = safe(prevEntry.token.getPartOfSpeechLevel1());
                if ("動詞".equals(prevPos1) || "助動詞".equals(prevPos1)
                        || "形容詞".equals(prevPos1)) {
                    decide(entry, "とき", "ja.reading.context.toki-ji", "rule:ja.reading.context.toki-ji");
                    continue;
                }
            }

            // Bounded clock-hour readings 0-24: 4→よ, 7→しち, 9→く, 0→れい. Arabic digits
            // are unreadable to UniDic; Kanji numerals get the generic reading (四→よん).
            if ("時".equals(entry.surface) && prevEntry != null && isNumericEntry(prevEntry)) {
                int runStart = numericRunStart(entries, i);
                Integer value = numeralValue(concatSurfaces(entries, runStart, i));
                if (value != null && value >= 0 && value <= 24) {
                    applyHourReading(entries, runStart, i, value);
                    decide(entry, "じ", "ja.reading.number.hour", "rule:ja.reading.number.hour");
                    continue;
                }
            }

            // Native one/two-person counts: 1人→ひとり, 2人→ふたり. Kanji 一人/二人 are
            // already lexical UniDic tokens; three and above stay Sino-Japanese.
            if ("人".equals(entry.surface) && "にん".equals(entry.readingKana)
                    && "接尾辞".equals(safe(token.getPartOfSpeechLevel1()))
                    && prevEntry != null && isArabicDigitEntry(prevEntry)
                    && ("1".equals(prevEntry.surface) || "2".equals(prevEntry.surface))) {
                String nativeReading = "1".equals(prevEntry.surface) ? "ひとり" : "ふたり";
                prevEntry.readingKana = nativeReading;
                entry.readingKana = "";
                recordRangeDecision(prevEntry, entry, "ja.reading.number.person-native",
                        "person-native", nativeReading);
                continue;
            }

        }
    }

    private static void applyReadingDefaults(List<Entry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            Entry previous = i > 0 ? entries.get(i - 1) : null;
            boolean compoundLeft = adjacentWithoutHardBoundary(previous, entry)
                    && previous.token != null
                    && ("名詞".equals(safe(previous.token.getPartOfSpeechLevel1()))
                        || "接頭辞".equals(safe(previous.token.getPartOfSpeechLevel1())));
            String preferred = isBlank(entry.ruleId) && !entry.providerValidated
                    && entry.token != null && isAllKanjiCommonNoun(entry)
                    && !compoundLeft ? jmdictPreferredReadings().get(kataToHira(entry.surface)) : null;
            if (!isBlank(preferred) && !preferred.equals(entry.readingKana)
                    && jmdictFuriganaSegments(entry.surface, preferred) != null) {
                decide(entry, preferred, "ja.reading.policy.preferred-lexical-reading",
                        "rule:ja.reading.policy.preferred-lexical-reading");
            } else if ("明日".equals(entry.surface) && !entry.providerValidated
                    && ("あす".equals(entry.readingKana) || "みょうにち".equals(entry.readingKana))) {
                decide(entry, "あした", "ja.reading.policy.ashita-default", "rule:ja.reading.policy.ashita-default");
            }
        }
    }

    private static boolean isAllKanjiCommonNoun(Entry entry) {
        if (entry == null || entry.token == null || isBlank(entry.surface)
                || entry.surface.codePointCount(0, entry.surface.length()) < 2
                || !"名詞".equals(safe(entry.token.getPartOfSpeechLevel1()))
                || !"普通名詞".equals(safe(entry.token.getPartOfSpeechLevel2()))) return false;
        for (int i = 0; i < entry.surface.length();) {
            int cp = entry.surface.codePointAt(i);
            if (!((cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x4E00 && cp <= 0x9FFF) || cp == 0x3005)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    private static void decide(Entry entry, String kana, String ruleId, String reason) {
        String previousCandidateId = isBlank(entry.readingKana) ? null : fallbackCandidateId(entry);
        entry.readingKana = kana;
        entry.ruleId = ruleId;
        entry.ruleVersion = ruleVersion(ruleId);
        entry.readingReason = reason;
        if (isBlank(kana)) return;
        entry.decisions.add(new JapaneseReadingPolicyModels.ReadingDecision(
                "select", "resolved", reasonId(reason), ruleId, entry.ruleVersion,
                codePointRange(entry, entry.start, entry.end), previousCandidateId,
                tokenId(entry) + ":rule:" + ruleId, fallbackCandidateId(entry), kana,
                Collections.singletonList(tokenId(entry)), null));
    }

    private static int ruleVersion(String ruleId) {
        if ("ja.reading.phonetic.cross-token-sokuon".equals(ruleId)) return 2;
        if ("ja.reading.context.ame-after-nochi".equals(ruleId)
                || "ja.reading.phonetic.ichi-following-allomorph".equals(ruleId)) return 1;
        return 1;
    }

    private static String reasonId(String reason) {
        if (reason == null) return "";
        int colon = reason.indexOf(':');
        return colon >= 0 && colon + 1 < reason.length() ? reason.substring(colon + 1) : reason;
    }

    private static String tokenId(Entry entry) {
        return "token-" + Math.max(0, entry.tokenIndex);
    }

    private static String fallbackCandidateId(Entry entry) {
        return tokenId(entry) + ":" + (isBlank(entry.dictionaryReadingSource)
                ? "passthrough" : entry.dictionaryReadingSource);
    }

    private static JapaneseReadingPolicyModels.CodePointRange codePointRange(Entry entry, int start, int end) {
        String source = safe(entry.sourceText);
        int safeStart = Math.max(0, Math.min(start, source.length()));
        int safeEnd = Math.max(safeStart, Math.min(end, source.length()));
        return new JapaneseReadingPolicyModels.CodePointRange(
                source.codePointCount(0, safeStart), source.codePointCount(0, safeEnd));
    }

    private static boolean isArabicDigitEntry(Entry entry) {
        if (entry == null || isBlank(entry.surface)) return false;
        for (int i = 0; i < entry.surface.length(); i++) {
            char c = entry.surface.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static boolean isKanjiNumeralEntry(Entry entry) {
        if (entry == null || entry.token == null || isBlank(entry.surface)) return false;
        if (!"数詞".equals(safe(entry.token.getPartOfSpeechLevel2()))) return false;
        for (int i = 0; i < entry.surface.length(); i++) {
            if ("〇零一二三四五六七八九十".indexOf(entry.surface.charAt(i)) < 0) return false;
        }
        return true;
    }

    private static boolean isNumericEntry(Entry entry) {
        return isArabicDigitEntry(entry) || isKanjiNumeralEntry(entry);
    }

    /** First index of the contiguous numeric run ending directly before counterIndex. */
    private static int numericRunStart(List<Entry> entries, int counterIndex) {
        int start = counterIndex;
        while (start > 0 && isNumericEntry(entries.get(start - 1))
                && !entries.get(start).boundaryBefore) {
            start--;
        }
        return start;
    }

    private static String concatSurfaces(List<Entry> entries, int start, int end) {
        StringBuilder out = new StringBuilder();
        for (int i = start; i < end; i++) out.append(entries.get(i).surface);
        return out.toString();
    }

    /** Conservative integer numeral parse: Arabic, or positional Kanji up to 99. */
    private static Integer numeralValue(String surface) {
        if (isBlank(surface)) return null;
        boolean digits = true;
        for (int i = 0; i < surface.length(); i++) {
            char c = surface.charAt(i);
            if (c < '0' || c > '9') { digits = false; break; }
        }
        if (digits) return surface.length() <= 2 ? Integer.valueOf(surface) : null;
        int tenIndex = surface.indexOf('十');
        if (tenIndex < 0) {
            return surface.length() == 1 ? digitValueBoxed(surface.charAt(0)) : null;
        }
        String tensPart = surface.substring(0, tenIndex);
        String onesPart = surface.substring(tenIndex + 1);
        if (tensPart.length() > 1 || onesPart.length() > 1 || onesPart.indexOf('十') >= 0) return null;
        Integer tens = tensPart.isEmpty() ? Integer.valueOf(1) : digitValueBoxed(tensPart.charAt(0));
        Integer ones = onesPart.isEmpty() ? Integer.valueOf(0) : digitValueBoxed(onesPart.charAt(0));
        if (tens == null || ones == null || tens == 0) return null;
        return tens * 10 + ones;
    }

    private static Integer digitValueBoxed(char kanji) {
        switch (kanji) {
            case '〇': case '零': return 0;
            case '一': return 1;
            case '二': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            case '七': return 7;
            case '八': return 8;
            case '九': return 9;
            default: return null;
        }
    }

    private static final String[] HOUR_ONES = {
            "", "いち", "に", "さん", "よ", "ご", "ろく", "しち", "はち", "く"
    };

    private static void applyHourReading(List<Entry> entries, int runStart, int counterIndex, int value) {
        String tens = value >= 20 ? "にじゅう" : value >= 10 ? "じゅう" : "";
        String ones = value == 0 ? "れい" : HOUR_ONES[value % 10];
        if (counterIndex - runStart == 1) {
            decide(entries.get(runStart), tens + ones, "ja.reading.number.hour", "rule:ja.reading.number.hour");
            return;
        }
        decide(entries.get(runStart), tens, "ja.reading.number.hour", "rule:ja.reading.number.hour");
        for (int i = runStart + 1; i < counterIndex - 1; i++) {
            decide(entries.get(i), "", "ja.reading.number.hour", "rule:ja.reading.number.hour");
        }
        decide(entries.get(counterIndex - 1), ones, "ja.reading.number.hour", "rule:ja.reading.number.hour");
    }

    private static String entryRomaji(Entry entry) {
        Token token = entry.token;
        // Particle renderings: は→wa, へ→e follow pronunciation; を stays "wo" by
        // project convention (matches desktop Spicy output and existing golden tests).
        if (token != null && "助詞".equals(token.getPartOfSpeechLevel1())) {
            if ("は".equals(entry.surface)) return "wa";
            if ("へ".equals(entry.surface)) return "e";
            if ("を".equals(entry.surface)) return "wo";
        }
        if (entry.readingKana == null) return entry.surface;
        if ("っ".equals(entry.readingKana)) return "";
        // Rule-decided empty kana marks a merged numeric/counter member: no romaji of its own.
        if (entry.readingKana.isEmpty() && entry.ruleId != null) return "";
        String projectionKana = isKatakanaOnly(entry.surface) ? entry.surface : entry.readingKana;
        String romaji = romanizeKana(projectionKana, allowsForeignKanaContraction(entry.surface));
        return isBlank(romaji) ? entry.surface : romaji;
    }

    /** Finalizes cross-token kana and records decisions before any romaji projection. */
    private static void finalizeCrossTokenKana(List<Entry> entries) {
        for (int i = 0; i + 1 < entries.size(); i++) {
            Entry entry = entries.get(i);
            Entry next = entries.get(i + 1);
            if (!adjacentWithoutHardBoundary(entry, next)) continue;
            if ("一".equals(entry.surface) && "いち".equals(entry.readingKana)
                    && "歩".equals(next.surface) && "ほ".equals(next.readingKana)) {
                entry.readingKana = "いっ";
                next.readingKana = "ぽ";
                recordRangeDecision(entry, next, "ja.reading.phonetic.ippo", "ippo-step", "いっぽ");
                continue;
            }
            boolean numericalIchi = "一".equals(entry.surface) && "いち".equals(entry.readingKana)
                    && entry.token != null
                    && "名詞".equals(safe(entry.token.getPartOfSpeechLevel1()))
                    && "数詞".equals(safe(entry.token.getPartOfSpeechLevel2()));
            if (numericalIchi
                    && startsWithKRow(next.readingKana)) {
                entry.readingKana = "いっ";
                recordRangeDecision(entry, next, "ja.reading.phonetic.ichi-k-row",
                        "ichi-k-row", safe(entry.readingKana) + safe(next.readingKana));
                continue;
            }
            IchiFollowingAllomorph allomorph = numericalIchi ? ichiFollowingAllomorph(next) : null;
            if (allomorph != null) {
                entry.readingKana = "いっ";
                next.readingKana = allomorph.outputKana;
                recordRangeDecision(entry, next, "ja.reading.phonetic.ichi-following-allomorph",
                        "ichi-following-allomorph", entry.readingKana + next.readingKana);
                continue;
            }
            if (isSmallKanaOnly(next.readingKana)
                    && isLicensedForeignKatakanaPair(safe(entry.surface) + safe(next.surface))) {
                String groupId = "projection-group-" + i;
                entry.projectionGroupId = groupId;
                next.projectionGroupId = groupId;
                continue;
            }
            if (entry.readingKana != null && entry.readingKana.endsWith("っ")
                    && startsWithConsonant(entryRomaji(next))) {
                recordRangeDecision(entry, next, "ja.reading.phonetic.cross-token-sokuon",
                        "cross-token-sokuon", safe(entry.readingKana) + safe(next.readingKana));
            }
        }
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.readingKana == null || !entry.readingKana.endsWith("っ")) continue;
            Entry next = i + 1 < entries.size() ? entries.get(i + 1) : null;
            boolean resolved = next != null && !isBlank(entry.readingGroupId)
                    && entry.readingGroupId.equals(next.readingGroupId);
            if (!resolved && (next == null || adjacentWithoutHardBoundary(entry, next))) {
                entry.diagnosticId = "ja.romaji.sokuon.unresolved";
            }
        }
    }

    private static void recordRangeDecision(Entry first, Entry last, String ruleId,
                                            String reasonId, String selectedKana) {
        String groupId = "reading-group-" + first.tokenIndex + "-" + last.tokenIndex + "-" + first.decisions.size();
        first.ruleId = ruleId;
        last.ruleId = ruleId;
        first.ruleVersion = ruleVersion(ruleId);
        last.ruleVersion = first.ruleVersion;
        first.readingGroupId = groupId;
        last.readingGroupId = groupId;
        first.readingReason = "rule:" + ruleId;
        last.readingReason = "rule:" + ruleId;
        ArrayList<String> evidenceIds = new ArrayList<>();
        evidenceIds.add(tokenId(first));
        evidenceIds.add(tokenId(last));
        first.decisions.add(new JapaneseReadingPolicyModels.ReadingDecision(
                "select", "resolved", reasonId, ruleId, first.ruleVersion,
                codePointRange(first, first.start, last.end), fallbackCandidateId(first),
                tokenId(first) + ":rule:" + ruleId, fallbackCandidateId(first), selectedKana,
                evidenceIds, null));
    }

    /** Romaji projection of finalized kana; trailing っ doubles the next consonant. */
    private static void applyRomajiSokuonProjection(List<Entry> entries) {
        for (int i = 0; i + 1 < entries.size(); i++) {
            Entry entry = entries.get(i);
            Entry next = entries.get(i + 1);
            if (entry.end != next.start) continue;
            if (!isBlank(entry.projectionGroupId)
                    && entry.projectionGroupId.equals(next.projectionGroupId)) {
                entry.romaji = romanizeKana(safe(entry.readingKana) + safe(next.readingKana), true);
                next.romaji = "";
                continue;
            }
            if (!isBlank(entry.readingGroupId) && entry.readingGroupId.equals(next.readingGroupId)
                    && entry.readingKana != null && entry.readingKana.endsWith("っ")
                    && startsWithConsonant(next.romaji)) {
                next.romaji = next.romaji.charAt(0) + next.romaji;
            }
        }
    }

    private static boolean adjacentWithoutHardBoundary(Entry left, Entry right) {
        return left != null && right != null && left.end == right.start && !right.hardBoundaryBefore;
    }

    private static boolean isPunctuationSurface(String surface) {
        return surface != null && surface.matches("^[。、？！…・「」『』（）().?!,]+$");
    }

    private static final class IchiFollowingAllomorph {
        final String surface;
        final String[] inputKana;
        final String outputKana;
        final String[] pos1;

        IchiFollowingAllomorph(String surface, String[] inputKana, String outputKana, String[] pos1) {
            this.surface = surface;
            this.inputKana = inputKana;
            this.outputKana = outputKana;
            this.pos1 = pos1;
        }
    }

    private static final IchiFollowingAllomorph[] ICHI_FOLLOWING_ALLOMORPHS = {
            new IchiFollowingAllomorph("冊", new String[]{"さつ"}, "さつ", new String[]{"接尾辞"}),
            new IchiFollowingAllomorph("等", new String[]{"とう"}, "とう", new String[]{"名詞", "接尾辞"}),
            new IchiFollowingAllomorph("着", new String[]{"ちゃく"}, "ちゃく", new String[]{"名詞", "接尾辞"}),
            new IchiFollowingAllomorph("本", new String[]{"ほん", "ぽん"}, "ぽん", new String[]{"接尾辞"}),
            new IchiFollowingAllomorph("杯", new String[]{"はい", "ばい", "ぱい"}, "ぱい", new String[]{"名詞", "接尾辞"}),
            new IchiFollowingAllomorph("匹", new String[]{"ひき", "びき", "ぴき"}, "ぴき", new String[]{"接尾辞"})
    };

    private static IchiFollowingAllomorph ichiFollowingAllomorph(Entry entry) {
        if (entry == null || entry.token == null) return null;
        String pos1 = safe(entry.token.getPartOfSpeechLevel1());
        for (IchiFollowingAllomorph item : ICHI_FOLLOWING_ALLOMORPHS) {
            if (!item.surface.equals(entry.surface) || !contains(item.inputKana, entry.readingKana)
                    || !contains(item.pos1, pos1)) continue;
            return item;
        }
        return null;
    }

    private static boolean contains(String[] values, String value) {
        if (values == null) return false;
        for (String item : values) if (item.equals(value)) return true;
        return false;
    }

    private static boolean isSmallKanaOnly(String kana) {
        return kana != null && kana.length() == 1 && "ぁぃぅぇぉ".indexOf(kana.charAt(0)) >= 0;
    }

    private static boolean startsWithKRow(String kana) {
        return !isBlank(kana)
                && ("かきくけこカキクケコ".indexOf(kana.charAt(0)) >= 0);
    }

    public static String romanizeChinesePinyinLine(String text) {
        return romanizeChinesePinyinLine(text, false);
    }

    public static String romanizeChinesePinyinLine(String text, boolean tones) {
        if (isBlank(text)) return text;
        HanyuPinyinOutputFormat format = tones ? PINYIN_FORMAT_TONED : PINYIN_FORMAT;
        PinyinTrieNode phrases = pinyinPhraseTrie();
        StringBuilder out = new StringBuilder();
        boolean lastWasPinyin = false;
        for (int i = 0; i < text.length(); ) {
            PinyinPhraseMatch phrase = matchPinyinPhrase(phrases, text, i);
            if (phrase != null) {
                if (out.length() > 0 && lastWasPinyin) out.append(' ');
                out.append(formatNumberedPinyinPhrase(phrase.reading, tones));
                lastWasPinyin = true;
                i = phrase.endIndex;
                continue;
            }

            int cp = text.codePointAt(i);
            String part = null;
            if (Character.charCount(cp) == 1) {
                try {
                    String[] values = PinyinHelper.toHanyuPinyinStringArray((char) cp, format);
                    if (values != null && values.length > 0) part = values[0];
                } catch (Throwable ignored) {
                }
            }
            if (part != null) {
                if (out.length() > 0 && lastWasPinyin) out.append(' ');
                out.append(part);
                lastWasPinyin = true;
            } else {
                out.appendCodePoint(cp);
                lastWasPinyin = false;
            }
            i += Character.charCount(cp);
        }
        return normalizeSpaces(out.toString());
    }

    /** Longest-match phrase ranges for display wrapping. These ranges never change timing owners. */
    public static List<int[]> chineseLayoutRanges(String text) {
        ArrayList<int[]> out = new ArrayList<>();
        if (isBlank(text)) return out;
        PinyinTrieNode phrases = pinyinPhraseTrie();
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            if (Character.isWhitespace(cp)) {
                i += Character.charCount(cp);
                continue;
            }
            PinyinPhraseMatch phrase = matchPinyinPhrase(phrases, text, i);
            int end = phrase == null ? i + Character.charCount(cp) : phrase.endIndex;
            out.add(new int[]{i, end});
            i = end;
        }
        return out;
    }

    private static PinyinTrieNode pinyinPhraseTrie() {
        PinyinTrieNode local = pinyinPhraseTrie;
        if (local != null) return local;
        synchronized (SpicyJapaneseChineseProcessor.class) {
            if (pinyinPhraseTrie == null) pinyinPhraseTrie = buildPinyinPhraseTrie();
            return pinyinPhraseTrie;
        }
    }

    private static PinyinTrieNode buildPinyinPhraseTrie() {
        PinyinTrieNode root = new PinyinTrieNode();
        String[] rows = PinyinPhraseData.data().split("\\n");
        for (String row : rows) {
            if (row == null || row.trim().isEmpty()) continue;
            int eq = row.indexOf('=');
            if (eq <= 0 || eq >= row.length() - 1) continue;
            String phrase = row.substring(0, eq);
            String reading = row.substring(eq + 1).trim();
            if (reading.isEmpty()) continue;
            PinyinTrieNode node = root;
            for (int i = 0; i < phrase.length();) {
                int cp = phrase.codePointAt(i);
                PinyinTrieNode next = node.children.get(cp);
                if (next == null) {
                    next = new PinyinTrieNode();
                    node.children.put(cp, next);
                }
                node = next;
                i += Character.charCount(cp);
            }
            node.reading = reading;
        }
        return root;
    }

    private static PinyinPhraseMatch matchPinyinPhrase(PinyinTrieNode root, String text, int startIndex) {
        PinyinTrieNode node = root;
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
        return reading == null ? null : new PinyinPhraseMatch(readingEnd, reading);
    }

    private static String formatNumberedPinyinPhrase(String reading, boolean tones) {
        if (isBlank(reading)) return "";
        StringBuilder out = new StringBuilder();
        String[] syllables = reading.trim().split("\\s+");
        for (String syllable : syllables) {
            if (isBlank(syllable)) continue;
            if (out.length() > 0) out.append(' ');
            out.append(tones ? numberedPinyinToToneMark(syllable) : stripPinyinToneNumber(syllable));
        }
        return out.toString();
    }

    private static String stripPinyinToneNumber(String syllable) {
        return syllable.replaceAll("[1-5]$", "");
    }

    private static String numberedPinyinToToneMark(String syllable) {
        if (isBlank(syllable)) return "";
        int tone = 5;
        int last = syllable.length() - 1;
        if (last >= 0) {
            char c = syllable.charAt(last);
            if (c >= '1' && c <= '5') {
                tone = c - '0';
                syllable = syllable.substring(0, last);
            }
        }
        syllable = syllable.replace('v', 'ü').replace('V', 'Ü');
        if (tone <= 0 || tone >= 5) return syllable;

        int markIndex = pinyinToneMarkIndex(syllable);
        if (markIndex < 0) return syllable;
        char marked = toneMarkedVowel(syllable.charAt(markIndex), tone);
        if (marked == 0) return syllable;
        return syllable.substring(0, markIndex) + marked + syllable.substring(markIndex + 1);
    }

    private static int pinyinToneMarkIndex(String syllable) {
        int a = indexOfAny(syllable, "aA");
        if (a >= 0) return a;
        int e = indexOfAny(syllable, "eE");
        if (e >= 0) return e;
        int ou = syllable.indexOf("ou");
        if (ou >= 0) return ou;
        int oU = syllable.indexOf("Ou");
        if (oU >= 0) return oU;
        for (int i = syllable.length() - 1; i >= 0; i--) {
            char c = syllable.charAt(i);
            if ("iIuUüÜoO".indexOf(c) >= 0) return i;
        }
        return -1;
    }

    private static int indexOfAny(String value, String chars) {
        for (int i = 0; i < value.length(); i++) {
            if (chars.indexOf(value.charAt(i)) >= 0) return i;
        }
        return -1;
    }

    private static char toneMarkedVowel(char vowel, int tone) {
        switch (vowel) {
            case 'a': return "āáǎà".charAt(tone - 1);
            case 'e': return "ēéěè".charAt(tone - 1);
            case 'i': return "īíǐì".charAt(tone - 1);
            case 'o': return "ōóǒò".charAt(tone - 1);
            case 'u': return "ūúǔù".charAt(tone - 1);
            case 'ü': return "ǖǘǚǜ".charAt(tone - 1);
            case 'A': return "ĀÁǍÀ".charAt(tone - 1);
            case 'E': return "ĒÉĚÈ".charAt(tone - 1);
            case 'I': return "ĪÍǏÌ".charAt(tone - 1);
            case 'O': return "ŌÓǑÒ".charAt(tone - 1);
            case 'U': return "ŪÚǓÙ".charAt(tone - 1);
            case 'Ü': return "ǕǗǙǛ".charAt(tone - 1);
            default: return 0;
        }
    }

    private static String buildRomaji(List<Entry> entries) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (isBlank(entry.romaji)) continue;
            boolean noSpaceBefore = shouldNoSpaceBefore(entry, i > 0 ? entries.get(i - 1) : null);
            appendToken(out, entry.romaji, noSpaceBefore);
        }
        return normalizeSpaces(out.toString());
    }

    private static List<FuriganaSegment> buildFurigana(String lineText, List<Entry> entries) {
        return buildFurigana(lineText, entries, null);
    }

    private static List<FuriganaSegment> buildFurigana(String lineText, List<Entry> entries, List<FuriganaSegment> provider) {
        List<FuriganaSegment> out = new ArrayList<>();
        for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
            Entry entry = entries.get(entryIndex);
            if (isBlank(entry.readingKana)) continue;
            if (entryIndex + 1 < entries.size()) {
                Entry next = entries.get(entryIndex + 1);
                if ("上".equals(entry.surface) && "目遣い".equals(next.surface)
                        && "うわめ".equals(entry.readingKana) && "つかい".equals(next.readingKana)
                        && adjacentWithoutHardBoundary(entry, next)) {
                    out.add(new FuriganaSegment(entry.start, entry.start + 1, "うわ"));
                    out.add(new FuriganaSegment(entry.start + 1, entry.start + 2, "め"));
                    out.add(new FuriganaSegment(entry.start + 2, entry.start + 3, "つか"));
                    entryIndex += 1;
                    continue;
                }
            }
            int groupEnd = readingGroupEnd(entries, entryIndex);
            if (groupEnd > entryIndex && shouldRenderGroupedNumericRuby(entries, entryIndex, groupEnd)) {
                StringBuilder groupedKana = new StringBuilder();
                for (int i = entryIndex; i <= groupEnd; i++) groupedKana.append(safe(entries.get(i).readingKana));
                out.add(new FuriganaSegment(entry.start, entries.get(groupEnd).end, groupedKana.toString()));
                entryIndex = groupEnd;
                continue;
            }
            if (entry.providerValidated && provider != null) {
                for (FuriganaSegment segment : provider) {
                    if (segment != null && segment.start >= entry.start && segment.end <= entry.end) {
                        out.add(new FuriganaSegment(segment.start, segment.end, kataToHira(segment.reading)));
                    }
                }
                continue;
            }
            List<TokenFuriganaReading> tokenSegments = kanaReadingSegments(entry.surface, entry.readingKana);
            for (TokenFuriganaReading segment : tokenSegments) {
                if (isBlank(segment.text)) continue;
                int start = Math.max(0, Math.min(lineText.length(), displayStart(entry, segment.targetStart)));
                int end = Math.max(start + 1, Math.min(lineText.length(), displayEnd(entry, segment.targetEnd)));
                out.add(new FuriganaSegment(start, end, segment.text));
            }
        }
        return out;
    }

    private static int readingGroupEnd(List<Entry> entries, int start) {
        if (entries == null || start < 0 || start >= entries.size()) return start;
        String groupId = entries.get(start).readingGroupId;
        if (isBlank(groupId)) return start;
        int end = start;
        while (end + 1 < entries.size() && groupId.equals(entries.get(end + 1).readingGroupId)) end++;
        return end;
    }

    private static boolean shouldRenderGroupedNumericRuby(List<Entry> entries, int start, int end) {
        boolean hasDigit = false;
        boolean hasKanji = false;
        for (int i = start; i <= end; i++) {
            String surface = safe(entries.get(i).surface);
            for (int offset = 0; offset < surface.length();) {
                int cp = surface.codePointAt(offset);
                hasDigit |= Character.isDigit(cp);
                hasKanji |= (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x4E00 && cp <= 0x9FFF) || cp == 0x3005;
                offset += Character.charCount(cp);
            }
        }
        return hasDigit && hasKanji;
    }

    private static int displayStart(Entry entry, int tokenOffset) {
        if (entry == null || entry.analysisText == null) return entry == null ? 0 : entry.start + tokenOffset;
        int analysisOffset = Math.max(entry.analysisStart, Math.min(entry.analysisEnd, entry.analysisStart + tokenOffset));
        return entry.analysisText.originalStart(analysisOffset);
    }

    private static int displayEnd(Entry entry, int tokenOffset) {
        if (entry == null || entry.analysisText == null) return entry == null ? 0 : entry.start + tokenOffset;
        int analysisOffset = Math.max(entry.analysisStart, Math.min(entry.analysisEnd, entry.analysisStart + tokenOffset));
        return entry.analysisText.originalEnd(analysisOffset);
    }

    /**
     * Furigana alignment: anchor the kana characters of the surface (okurigana)
     * against the reading, and give each contiguous kanji run ONE segment spanning
     * the whole run (jukugo ruby) — the kuroshiro approach. Never split a reading
     * across the kanji of a compound by guesswork.
     */
    private static List<TokenFuriganaReading> kanaReadingSegments(String surface, String kana) {
        ArrayList<TokenFuriganaReading> segments = new ArrayList<>();
        if (isBlank(kana) || "*".equals(kana)) return segments;

        List<TokenFuriganaReading> dictionary = jmdictFuriganaSegments(surface, kana);
        if (dictionary != null) return dictionary;

        String normalizedSurface = kataToHira(surface);
        List<String> chars = codePoints(normalizedSurface);

        int kanaCursor = 0;
        int charIndex = 0;
        while (charIndex < chars.size()) {
            String ch = chars.get(charIndex);
            if (isKanaChar(ch)) {
                if (kanaCursor < kana.length() && kana.substring(kanaCursor).startsWith(ch)) kanaCursor += ch.length();
                charIndex++;
                continue;
            }
            if (!isKanjiChar(ch)) {
                charIndex++;
                continue;
            }

            int start = charIndex;
            while (charIndex < chars.size() && isKanjiChar(chars.get(charIndex))) charIndex++;
            int end = charIndex;
            StringBuilder followingKana = new StringBuilder();
            for (int i = charIndex; i < chars.size(); i++) {
                String following = chars.get(i);
                if (isKanaChar(following)) followingKana.append(following);
                else break;
            }
            int readingStart = kanaCursor;
            if (followingKana.length() > 0) {
                int nextIndex = okuriganaAnchorIndex(kana, kanaCursor, followingKana.toString());
                kanaCursor = nextIndex >= 0 ? nextIndex : kana.length();
            } else {
                kanaCursor = kana.length();
            }
            String part = kana.substring(Math.min(readingStart, kana.length()), Math.min(kanaCursor, kana.length()));
            if (isBlank(part)) continue;
            segments.add(new TokenFuriganaReading(part, start, end));
        }
        return segments;
    }

    private static List<TokenFuriganaReading> jmdictFuriganaSegments(String surface, String kana) {
        Map<String, List<TokenFuriganaReading>> data = jmdictFurigana();
        if (data.isEmpty()) return null;
        List<TokenFuriganaReading> segments = data.get(kataToHira(surface) + "|" + kataToHira(kana));
        return segments == null ? null : new ArrayList<>(segments);
    }

    private static Map<String, List<TokenFuriganaReading>> jmdictFurigana() {
        Map<String, List<TokenFuriganaReading>> local = jmdictFurigana;
        if (local != null) return local;
        synchronized (SpicyJapaneseChineseProcessor.class) {
            if (jmdictFurigana == null) jmdictFurigana = loadJmdictFurigana();
            return jmdictFurigana;
        }
    }

    private static Map<String, List<TokenFuriganaReading>> loadJmdictFurigana() {
        HashMap<String, List<TokenFuriganaReading>> out = new HashMap<>();
        try (InputStream in = SpicyJapaneseChineseProcessor.class.getResourceAsStream("JmdictFurigana.txt.gz")) {
            if (in == null) return out;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new java.util.zip.GZIPInputStream(in), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1);
                    int first = line.indexOf('|');
                    int second = first < 0 ? -1 : line.indexOf('|', first + 1);
                    if (first <= 0 || second <= first + 1 || second >= line.length() - 1) continue;
                    String surface = line.substring(0, first);
                    String reading = line.substring(first + 1, second);
                    List<TokenFuriganaReading> segments = parseJmdictSpanSpec(line.substring(second + 1));
                    if (segments.isEmpty()) continue;
                    String key = kataToHira(surface) + "|" + kataToHira(reading);
                    if (!out.containsKey(key)) out.put(key, segments);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static Map<String, String> jmdictPreferredReadings() {
        Map<String, String> local = jmdictPreferredReadings;
        if (local != null) return local;
        synchronized (SpicyJapaneseChineseProcessor.class) {
            if (jmdictPreferredReadings == null) jmdictPreferredReadings = loadJmdictPreferredReadings();
            return jmdictPreferredReadings;
        }
    }

    private static Map<String, String> loadJmdictPreferredReadings() {
        HashMap<String, String> out = new HashMap<>();
        try (InputStream in = SpicyJapaneseChineseProcessor.class.getResourceAsStream("JmdictPreferredReadings.txt.gz")) {
            if (in == null) return out;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new java.util.zip.GZIPInputStream(in), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1);
                    int separator = line.indexOf('|');
                    if (separator <= 0 || separator >= line.length() - 1) continue;
                    out.put(kataToHira(line.substring(0, separator)), kataToHira(line.substring(separator + 1)));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    static List<FuriganaSegment> kanaReadingSegmentsForTest(String surface, String kana) {
        return testSegments(kanaReadingSegments(surface, kana));
    }

    static List<FuriganaSegment> parseJmdictSpanSpecForTest(String spec) {
        return testSegments(parseJmdictSpanSpec(spec));
    }

    private static List<FuriganaSegment> testSegments(List<TokenFuriganaReading> segments) {
        ArrayList<FuriganaSegment> out = new ArrayList<>();
        for (TokenFuriganaReading segment : segments) {
            out.add(new FuriganaSegment(segment.targetStart, segment.targetEnd, segment.text));
        }
        return out;
    }

    private static List<TokenFuriganaReading> parseJmdictSpanSpec(String spec) {
        ArrayList<TokenFuriganaReading> segments = new ArrayList<>();
        if (isBlank(spec)) return segments;
        String[] parts = spec.split(";");
        for (String part : parts) {
            if (isBlank(part)) continue;
            int colon = part.indexOf(':');
            if (colon <= 0 || colon >= part.length() - 1) continue;
            String range = part.substring(0, colon);
            String reading = kataToHira(part.substring(colon + 1));
            int dash = range.indexOf('-');
            try {
                int start;
                int end;
                if (dash > 0) {
                    start = Integer.parseInt(range.substring(0, dash));
                    end = Integer.parseInt(range.substring(dash + 1)) + 1;
                } else {
                    start = Integer.parseInt(range);
                    end = start + 1;
                }
                if (start >= 0 && end > start && !isBlank(reading)) {
                    segments.add(new TokenFuriganaReading(reading, start, end));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return segments;
    }

    private static int okuriganaAnchorIndex(String kana, int kanaCursor, String okurigana) {
        if (isBlank(kana) || isBlank(okurigana)) return -1;
        int safeCursor = Math.max(0, Math.min(kanaCursor, kana.length()));
        String remaining = kana.substring(safeCursor);
        if (remaining.endsWith(okurigana)) return kana.length() - okurigana.length();
        int fallback = kana.lastIndexOf(okurigana, kana.length() - okurigana.length());
        return fallback >= safeCursor ? fallback : -1;
    }

    /**
     * Approximate romaji for a syllable-boundary slice through the middle of a
     * token. Distributes the kanji-run reading evenly across its kanji — an
     * approximation used ONLY for karaoke syllable mapping, never for displayed
     * furigana.
     */
    private static String romanizeEntrySlice(Entry entry, int start, int end) {
        if (entry == null || isBlank(entry.surface) || isBlank(entry.readingKana)) return "";
        List<String> chars = codePoints(entry.surface);
        int safeStart = Math.max(0, Math.min(start, chars.size()));
        int safeEnd = Math.max(safeStart, Math.min(end, chars.size()));
        if (safeStart >= safeEnd) return "";

        List<String> perChar = perCharKanaApproximation(entry.surface, entry.readingKana);
        StringBuilder out = new StringBuilder();
        for (int i = safeStart; i < safeEnd && i < perChar.size(); i++) {
            String reading = perChar.get(i);
            if (isBlank(reading)) continue;
            String romaji = romanizeKana(reading);
            if (!isBlank(romaji)) out.append(romaji);
        }
        return normalizeSpaces(out.toString());
    }

    private static List<String> perCharKanaApproximation(String surface, String kana) {
        List<String> chars = codePoints(kataToHira(surface));
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < chars.size(); i++) out.add("");
        List<TokenFuriganaReading> segments = kanaReadingSegments(surface, kana);
        int segIndex = 0;
        for (int i = 0; i < chars.size(); i++) {
            String ch = chars.get(i);
            if (isKanaChar(ch)) {
                out.set(i, ch);
                continue;
            }
            while (segIndex < segments.size() && segments.get(segIndex).targetEnd <= i) segIndex++;
            if (segIndex < segments.size()) {
                TokenFuriganaReading seg = segments.get(segIndex);
                if (seg.targetStart <= i && i < seg.targetEnd) {
                    List<String> split = splitKanaEvenly(seg.text, seg.targetEnd - seg.targetStart);
                    int offset = i - seg.targetStart;
                    if (offset < split.size()) out.set(i, split.get(offset));
                }
            }
        }
        return out;
    }

    private static void appendToken(StringBuilder out, String romaji, boolean noSpaceBefore) {
        if (isBlank(romaji)) return;
        if (out.length() > 0 && !noSpaceBefore && needsSpace(out, romaji)) out.append(' ');
        out.append(romaji);
    }

    private static boolean shouldNoSpaceBefore(Entry entry, Entry prevEntry) {
        if (prevEntry != null && entry.hardBoundaryBefore) return false;
        if (prevEntry != null && !isBlank(entry.readingGroupId)
                && entry.readingGroupId.equals(prevEntry.readingGroupId)) return true;
        if (prevEntry != null && !isBlank(entry.projectionGroupId)
                && entry.projectionGroupId.equals(prevEntry.projectionGroupId)) return true;
        if (entry.surface.matches("^[。、？！…・「」『』（）().?!,\\s]+$")) return true;
        if (shouldMergeNonJapaneseAscii(entry, prevEntry)) return true;
        if (shouldMergeJapaneseVerbContinuation(entry, prevEntry)) return true;
        if (shouldMergeMekuSuffix(entry, prevEntry)) return true;
        if (shouldMergeFillerContinuation(entry, prevEntry)) return true;
        return entry.romaji != null && entry.romaji.length() == 1 && !Character.isLetterOrDigit(entry.romaji.charAt(0));
    }

    private static boolean shouldMergeFillerContinuation(Entry entry, Entry prevEntry) {
        if (entry == null || prevEntry == null || entry.token == null || prevEntry.token == null) return false;
        if (prevEntry.end != entry.start) return false;
        return "感動詞".equals(safe(entry.token.getPartOfSpeechLevel1()))
                && "フィラー".equals(safe(entry.token.getPartOfSpeechLevel2()))
                && "感動詞".equals(safe(prevEntry.token.getPartOfSpeechLevel1()))
                && "フィラー".equals(safe(prevEntry.token.getPartOfSpeechLevel2()));
    }

    /**
     * The derivational suffix めく (謎めく, 春めく, 響めく) never stands alone;
     * bind it to its adjacent noun stem so split tokenizations read as one word
     * (doyomeki), matching single-token forms like 煌めき (kirameki).
     */
    private static boolean shouldMergeMekuSuffix(Entry entry, Entry prevEntry) {
        if (entry.token == null || prevEntry == null || prevEntry.token == null) return false;
        if (prevEntry.end != entry.start) return false;
        return "接尾辞".equals(safe(entry.token.getPartOfSpeechLevel1()))
                && "めく".equals(safe(entry.token.getLemma()))
                && "名詞".equals(safe(prevEntry.token.getPartOfSpeechLevel1()));
    }

    private static boolean shouldMergeNonJapaneseAscii(Entry entry, Entry prevEntry) {
        if (entry == null || prevEntry == null) return false;
        if (prevEntry.end != entry.start) return false;
        return isNonJapaneseAscii(entry.surface) && isNonJapaneseAscii(prevEntry.surface);
    }

    private static boolean isNonJapaneseAscii(String value) {
        if (isBlank(value) || SpicyTextDetection.itemJapaneseTest(value)) return false;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F || Character.isWhitespace(value.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Word-joining: verb stems bind their auxiliaries and conjunctive particles
     * (生き+て+いく → ikiteiku) — POS-sequence checks in the cutlet style, not
     * conjugation rules; the dictionary already did the conjugating.
     */
    private static boolean shouldMergeJapaneseVerbContinuation(Entry entry, Entry prevEntry) {
        if (entry.token == null || prevEntry == null || prevEntry.token == null) return false;
        String prevPos1 = safe(prevEntry.token.getPartOfSpeechLevel1());
        String prevPos2 = safe(prevEntry.token.getPartOfSpeechLevel2());
        String pos1 = safe(entry.token.getPartOfSpeechLevel1());
        String pos2 = safe(entry.token.getPartOfSpeechLevel2());
        boolean prevVerbLike = "動詞".equals(prevPos1) || "助動詞".equals(prevPos1) || "接続助詞".equals(prevPos2);
        if (!prevVerbLike) return false;
        if ("動詞".equals(pos1) && "非自立可能".equals(pos2)) return true;
        if ("助詞".equals(pos1) && "接続助詞".equals(pos2)) return true;
        if ("助動詞".equals(pos1)) {
            // です/でしょう/だろう read as standalone words in romaji (fork rule).
            String surface = entry.surface;
            if (surface.startsWith("でしょ") || surface.startsWith("です") || surface.startsWith("だろ")) return false;
            return true;
        }
        return false;
    }

    private static boolean needsSpace(StringBuilder out, String romaji) {
        char last = out.charAt(out.length() - 1);
        char first = romaji.charAt(0);
        return Character.isLetterOrDigit(last) && Character.isLetterOrDigit(first);
    }

    private static boolean startsWithConsonant(String value) {
        if (isBlank(value)) return false;
        char c = Character.toLowerCase(value.charAt(0));
        return c >= 'a' && c <= 'z' && "aeioun".indexOf(c) < 0;
    }

    private static boolean isLicensedForeignKatakanaPair(String kana) {
        switch (safe(kana)) {
            case "ファ": case "フィ": case "フェ": case "フォ":
            case "ティ": case "ディ": case "トゥ": case "ドゥ":
            case "ウィ": case "ウェ": case "ウォ":
            case "ツァ": case "ツィ": case "ツェ": case "ツォ":
            case "シェ": case "ジェ": case "チェ":
            case "ヴァ": case "ヴィ": case "ヴェ": case "ヴォ":
                return true;
            default:
                return false;
        }
    }

    private static boolean isLicensedForeignHiraganaPair(String kana) {
        switch (safe(kana)) {
            case "ふぁ": case "ふぃ": case "ふぇ": case "ふぉ":
            case "てぃ": case "でぃ": case "とぅ": case "どぅ":
            case "うぃ": case "うぇ": case "うぉ":
            case "つぁ": case "つぃ": case "つぇ": case "つぉ":
            case "しぇ": case "じぇ": case "ちぇ":
            case "ゔぁ": case "ゔぃ": case "ゔぇ": case "ゔぉ":
                return true;
            default:
                return false;
        }
    }

    private static boolean allowsForeignKanaContraction(String surface) {
        if (isBlank(surface)) return true;
        for (int i = 0; i < surface.length(); i++) {
            char c = surface.charAt(i);
            if (c >= 'ァ' && c <= 'ヶ') return true;
        }
        return false;
    }

    private static boolean isKanaOnly(String value) {
        if (isBlank(value)) return false;
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            if (!((cp >= 0x3040 && cp <= 0x30FF) || cp == 'ー')) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    private static boolean isKatakanaOnly(String value) {
        if (isBlank(value)) return false;
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            if (!((cp >= 0x30A0 && cp <= 0x30FF) || cp == 'ー')) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    private static boolean isKanjiChar(String value) {
        if (isBlank(value)) return false;
        int cp = value.codePointAt(0);
        return (cp >= 0x3400 && cp <= 0x4DBF) || (cp >= 0x4E00 && cp <= 0x9FFF) || cp == 0x3005;
    }

    private static boolean isKanaChar(String value) {
        if (isBlank(value)) return false;
        int cp = value.codePointAt(0);
        return (cp >= 0x3040 && cp <= 0x309F) || cp == 'ー';
    }

    private static Tokenizer tokenizer() {
        Tokenizer local = tokenizer;
        if (local != null) return local;
        synchronized (SpicyJapaneseChineseProcessor.class) {
            if (tokenizer == null) tokenizer = new Tokenizer();
            return tokenizer;
        }
    }

    private static String kataToHira(String text) {
        String input = safe(text);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c >= 'ァ' && c <= 'ヶ') out.append((char) (c - 0x60));
            else out.append(c);
        }
        return out.toString();
    }

    private static List<String> splitKanaEvenly(String kana, int count) {
        List<String> morae = splitKanaMorae(kana);
        ArrayList<String> chunks = new ArrayList<>();
        int cursor = 0;
        for (int i = 0; i < count; i++) {
            int remainingMorae = morae.size() - cursor;
            int remainingSlots = count - i;
            int take = Math.max(1, (int) Math.ceil((double) remainingMorae / (double) remainingSlots));
            StringBuilder part = new StringBuilder();
            for (int j = 0; j < take && cursor + j < morae.size(); j++) part.append(morae.get(cursor + j));
            chunks.add(part.toString());
            cursor += take;
        }
        return chunks;
    }

    private static List<String> splitKanaMorae(String kana) {
        ArrayList<String> morae = new ArrayList<>();
        for (String ch : codePoints(kana)) {
            if (!morae.isEmpty() && ch.matches("[ゃゅょぁぃぅぇぉ]")) {
                int last = morae.size() - 1;
                morae.set(last, morae.get(last) + ch);
            } else {
                morae.add(ch);
            }
        }
        return morae;
    }

    private static List<String> codePoints(String value) {
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < safe(value).length(); ) {
            int cp = value.codePointAt(i);
            out.add(new String(Character.toChars(cp)));
            i += Character.charCount(cp);
        }
        return out;
    }

    private static String romanizeKana(String text) {
        return romanizeKana(text, true);
    }

    private static String romanizeKana(String text, boolean allowForeignKanaContraction) {
        if (text == null) return null;
        StringBuilder out = new StringBuilder();
        boolean doubleNext = false;
        for (int i = 0; i < text.length(); i++) {
            char c = normalizeKana(text.charAt(i));
            if (c == 'っ') {
                doubleNext = true;
                continue;
            }
            if (c == 'ー') {
                appendLongVowel(out);
                continue;
            }
            String mapped = null;
            if (i + 1 < text.length()) {
                char next = normalizeKana(text.charAt(i + 1));
                mapped = KANA.get("" + c + next);
                if (!allowForeignKanaContraction && isLicensedForeignHiraganaPair("" + c + next)) {
                    mapped = null;
                }
                if (mapped != null) i++;
            }
            if (mapped == null) mapped = KANA.get(String.valueOf(c));
            if (mapped != null) {
                if (doubleNext && startsWithConsonant(mapped)) out.append(mapped.charAt(0));
                out.append(mapped);
            } else {
                out.append(text.charAt(i));
            }
            doubleNext = false;
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    private static char normalizeKana(char c) {
        if (c >= 'ァ' && c <= 'ヶ') return (char) (c - 0x60);
        return c;
    }

    private static void appendLongVowel(StringBuilder out) {
        for (int i = out.length() - 1; i >= 0; i--) {
            char c = Character.toLowerCase(out.charAt(i));
            if ("aeiou".indexOf(c) >= 0) {
                out.append(c);
                return;
            }
        }
    }

    private static String normalizeSpaces(String value) {
        return safe(value).replaceAll("[ \\t]+", " ").trim();
    }

    private static void putKana(String kana, String romaji) {
        KANA.put(kana, romaji);
    }

    private static void initKana() {
        String[][] base = {
                {"あ", "a"}, {"い", "i"}, {"う", "u"}, {"え", "e"}, {"お", "o"},
                {"か", "ka"}, {"き", "ki"}, {"く", "ku"}, {"け", "ke"}, {"こ", "ko"},
                {"さ", "sa"}, {"し", "shi"}, {"す", "su"}, {"せ", "se"}, {"そ", "so"},
                {"た", "ta"}, {"ち", "chi"}, {"つ", "tsu"}, {"て", "te"}, {"と", "to"},
                {"な", "na"}, {"に", "ni"}, {"ぬ", "nu"}, {"ね", "ne"}, {"の", "no"},
                {"は", "ha"}, {"ひ", "hi"}, {"ふ", "fu"}, {"へ", "he"}, {"ほ", "ho"},
                {"ま", "ma"}, {"み", "mi"}, {"む", "mu"}, {"め", "me"}, {"も", "mo"},
                {"や", "ya"}, {"ゆ", "yu"}, {"よ", "yo"},
                {"ら", "ra"}, {"り", "ri"}, {"る", "ru"}, {"れ", "re"}, {"ろ", "ro"},
                {"わ", "wa"}, {"を", "wo"}, {"ん", "n"},
                {"が", "ga"}, {"ぎ", "gi"}, {"ぐ", "gu"}, {"げ", "ge"}, {"ご", "go"},
                {"ざ", "za"}, {"じ", "ji"}, {"ず", "zu"}, {"ぜ", "ze"}, {"ぞ", "zo"},
                {"だ", "da"}, {"ぢ", "ji"}, {"づ", "zu"}, {"で", "de"}, {"ど", "do"},
                {"ば", "ba"}, {"び", "bi"}, {"ぶ", "bu"}, {"べ", "be"}, {"ぼ", "bo"},
                {"ぱ", "pa"}, {"ぴ", "pi"}, {"ぷ", "pu"}, {"ぺ", "pe"}, {"ぽ", "po"},
                {"ゔ", "vu"}, {"ぁ", "a"}, {"ぃ", "i"}, {"ぅ", "u"}, {"ぇ", "e"}, {"ぉ", "o"},
                {"ゃ", "ya"}, {"ゅ", "yu"}, {"ょ", "yo"},
                {"きゃ", "kya"}, {"きゅ", "kyu"}, {"きょ", "kyo"},
                {"しゃ", "sha"}, {"しゅ", "shu"}, {"しょ", "sho"},
                {"ちゃ", "cha"}, {"ちゅ", "chu"}, {"ちょ", "cho"},
                {"にゃ", "nya"}, {"にゅ", "nyu"}, {"にょ", "nyo"},
                {"ひゃ", "hya"}, {"ひゅ", "hyu"}, {"ひょ", "hyo"},
                {"みゃ", "mya"}, {"みゅ", "myu"}, {"みょ", "myo"},
                {"りゃ", "rya"}, {"りゅ", "ryu"}, {"りょ", "ryo"},
                {"ぎゃ", "gya"}, {"ぎゅ", "gyu"}, {"ぎょ", "gyo"},
                {"じゃ", "ja"}, {"じゅ", "ju"}, {"じょ", "jo"},
                {"びゃ", "bya"}, {"びゅ", "byu"}, {"びょ", "byo"},
                {"ぴゃ", "pya"}, {"ぴゅ", "pyu"}, {"ぴょ", "pyo"},
                {"ふぁ", "fa"}, {"ふぃ", "fi"}, {"ふぇ", "fe"}, {"ふぉ", "fo"},
                {"てぃ", "ti"}, {"でぃ", "di"}, {"とぅ", "tu"}, {"どぅ", "du"},
                {"うぃ", "wi"}, {"うぇ", "we"}, {"うぉ", "wo"},
                {"つぁ", "tsa"}, {"つぃ", "tsi"}, {"つぇ", "tse"}, {"つぉ", "tso"},
                {"しぇ", "she"}, {"じぇ", "je"}, {"ちぇ", "che"},
                {"ゔぁ", "va"}, {"ゔぃ", "vi"}, {"ゔぇ", "ve"}, {"ゔぉ", "vo"}
        };
        for (String[] pair : base) putKana(pair[0], pair[1]);
    }

}
