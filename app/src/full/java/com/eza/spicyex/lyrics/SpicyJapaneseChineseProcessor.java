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
 *   reason the dictionary cannot supply the reading (see applyLexicalOverrides).
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

    public static final class JapaneseReading {
        public final String sourceText;
        public final String romaji;
        public final List<FuriganaSegment> furigana;

        public JapaneseReading(String sourceText, String romaji, List<FuriganaSegment> furigana) {
            this.sourceText = sourceText == null ? "" : sourceText;
            this.romaji = romaji == null ? "" : romaji;
            this.furigana = furigana == null ? new ArrayList<>() : furigana;
        }
    }

    private static final class Entry {
        AnalysisText analysisText;
        int analysisStart;
        int analysisEnd;
        int start;
        int end;
        String surface;
        String readingKana; // hiragana reading-of-record; null when token has no reading
        String dictionaryReadingKana;
        String readingReason;
        String romaji;
        Token token;
    }

    static final class JapaneseDebugToken {
        final String surface;
        final int analysisStart, analysisEnd, displayStart, displayEnd;
        final String partOfSpeech1, partOfSpeech2, pronunciation, lemma, lemmaReading;
        final String dictionaryReading, selectedReading, readingReason, romaji;

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
            romaji = safe(entry.romaji);
        }
    }

    static final class JapaneseDebugSnapshot {
        final String displayText, analysisText, romaji;
        final int[] analysisToDisplayUtf16;
        final List<JapaneseDebugToken> tokens;
        final List<FuriganaSegment> furigana;

        JapaneseDebugSnapshot(String displayText, AnalysisText analysis, List<Entry> entries,
                              String romaji, List<FuriganaSegment> furigana) {
            this.displayText = displayText;
            this.analysisText = analysis.text;
            this.analysisToDisplayUtf16 = analysis.originalOffsets.clone();
            this.tokens = new ArrayList<>();
            for (Entry entry : entries) this.tokens.add(new JapaneseDebugToken(entry));
            this.romaji = safe(romaji);
            this.furigana = new ArrayList<>(furigana);
        }
    }

    private static final class AnalysisText {
        final String text;
        final int[] originalOffsets;

        AnalysisText(String text, int[] originalOffsets) {
            this.text = text == null ? "" : text;
            this.originalOffsets = originalOffsets == null ? new int[0] : originalOffsets;
        }

        int originalStart(int analysisStart) {
            if (originalOffsets.length == 0) return analysisStart;
            int safe = Math.max(0, Math.min(analysisStart, originalOffsets.length - 1));
            return originalOffsets[safe];
        }

        int originalEnd(int analysisEnd) {
            if (originalOffsets.length == 0) return analysisEnd;
            int safe = Math.max(0, Math.min(analysisEnd - 1, originalOffsets.length - 1));
            int original = originalOffsets[safe];
            return original + Character.charCount(text.codePointBefore(Math.max(1, Math.min(analysisEnd, text.length()))));
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

    public static boolean canRomanizeJapanese(String text) {
        return SpicyTextDetection.itemJapaneseTest(text);
    }

    public static boolean canRomanizeChinese(String text) {
        return SpicyTextDetection.itemChineseTest(text);
    }

    public static JapaneseReading analyzeJapaneseLine(String text, String fullSpacedRomaji) {
        if (isBlank(text)) return null;
        String sourceText = Normalizer.normalize(text, Normalizer.Form.NFKC);
        if (!SpicyTextDetection.itemJapaneseTest(sourceText)) return null;

        List<Entry> entries = buildEntries(sourceText, analysisTextForJapanese(sourceText));
        if (entries.isEmpty()) return new JapaneseReading(sourceText, sourceText, new ArrayList<>());

        String romaji = buildRomaji(entries);
        if (isBlank(romaji) && !isBlank(fullSpacedRomaji)) romaji = fullSpacedRomaji;
        List<FuriganaSegment> furigana = buildFurigana(sourceText, entries);
        return new JapaneseReading(sourceText, romaji, furigana);
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
        if (isBlank(text) || furigana == null || furigana.isEmpty()) return null;
        String sourceText = Normalizer.normalize(text, Normalizer.Form.NFKC);
        if (!SpicyTextDetection.itemJapaneseTest(sourceText)) return null;

        List<Entry> entries = buildEntries(sourceText, analysisTextForJapanese(sourceText));
        if (entries.isEmpty()) return null;

        applyProviderFuriganaOverrides(sourceText, entries, furigana);
        applyLexicalOverrides(entries);
        for (Entry entry : entries) entry.romaji = entryRomaji(entry);
        applyCrossTokenSokuon(entries);
        return new JapaneseReading(sourceText, buildRomaji(entries), buildFurigana(sourceText, entries, furigana));
    }

    static JapaneseDebugSnapshot debugJapaneseSnapshot(String text, List<FuriganaSegment> providerFurigana) {
        String sourceText = Normalizer.normalize(safe(text), Normalizer.Form.NFKC);
        AnalysisText analysis = analysisTextForJapanese(sourceText);
        List<Entry> entries = buildEntries(sourceText, analysis);
        if (providerFurigana != null && !providerFurigana.isEmpty()) {
            applyProviderFuriganaOverrides(sourceText, entries, providerFurigana);
            applyLexicalOverrides(entries);
            for (Entry entry : entries) entry.romaji = entryRomaji(entry);
            applyCrossTokenSokuon(entries);
        }
        String romaji = buildRomaji(entries);
        return new JapaneseDebugSnapshot(sourceText, analysis, entries, romaji, buildFurigana(sourceText, entries));
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
            if (!isBlank(reading) && reading.equals(entry.dictionaryReadingKana)) {
                entry.readingKana = reading;
                entry.readingReason = "providerRubyValidated";
            }
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
        List<RomajiGroup> groups = romajiGroups(entries);

        int syllPos = 0;
        for (int si = 0; si < syllableTexts.size(); si++) {
            String syllableText = Normalizer.normalize(safe(syllableTexts.get(si)), Normalizer.Form.NFKC);
            while (syllPos < sourceText.length() && Character.isWhitespace(sourceText.charAt(syllPos))) syllPos++;
            int syllStart = syllPos;
            int syllEnd = Math.min(sourceText.length(), syllStart + syllableText.length());
            syllPos = syllEnd;

            StringBuilder romaji = new StringBuilder();
            for (RomajiGroup group : groups) {
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
        return buildEntries(sourceText, new AnalysisText(sourceText, null));
    }

    private static List<Entry> buildEntries(String sourceText, AnalysisText analysisText) {
        List<Token> tokens;
        try {
            tokens = tokenizer().tokenize(analysisText == null ? sourceText : analysisText.text);
        } catch (Throwable t) {
            return new ArrayList<>();
        }
        List<Entry> entries = new ArrayList<>();
        int charPos = 0;
        for (Token token : tokens) {
            String surface = safe(token.getSurface());
            Entry entry = new Entry();
            entry.analysisText = analysisText;
            entry.analysisStart = charPos;
            entry.analysisEnd = charPos + surface.length();
            entry.start = analysisText == null ? charPos : analysisText.originalStart(charPos);
            entry.end = analysisText == null ? charPos + surface.length() : analysisText.originalEnd(charPos + surface.length());
            entry.surface = surface;
            entry.token = token;
            entry.readingKana = readingOfRecord(token, surface);
            entry.dictionaryReadingKana = entry.readingKana;
            entry.readingReason = entry.readingKana == null ? "passthrough" : "unidicReadingOfRecord";
            entries.add(entry);
            charPos += surface.length();
        }
        applyLexicalOverrides(entries);
        for (Entry entry : entries) entry.romaji = entryRomaji(entry);
        applyCrossTokenSokuon(entries);
        return entries;
    }

    private static AnalysisText analysisTextForJapanese(String sourceText) {
        if (isBlank(sourceText)) return new AnalysisText(sourceText, null);
        StringBuilder normalized = new StringBuilder();
        ArrayList<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < sourceText.length(); ) {
            int cp = sourceText.codePointAt(i);
            int len = Character.charCount(cp);
            if (Character.isWhitespace(cp) && hasJapaneseBeforeAndAfter(sourceText, i, i + len)) {
                i += len;
                continue;
            }
            normalized.appendCodePoint(cp);
            for (int j = 0; j < len; j++) offsets.add(i + j);
            i += len;
        }
        int[] map = new int[offsets.size()];
        for (int i = 0; i < offsets.size(); i++) map[i] = offsets.get(i);
        return new AnalysisText(normalized.toString(), map);
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
    private static String readingOfRecord(Token token, String surface) {
        if (!SpicyTextDetection.itemJapaneseTest(surface)) return null;
        String pron = safe(token.getPronunciation());
        String lemmaYomi = safe(token.getLemmaReadingForm());
        if (isBlank(pron) || "*".equals(pron)) {
            if (isKanaOnly(surface)) return kataToHira(surface);
            return null;
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
        return kataToHira(out.toString());
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
     * The ONLY hand-maintained reading layer. The dictionary owns grammar and
     * context; an entry is allowed here solely when the dictionary cannot know
     * the answer. Each entry must cite its reason. Verified against UniDic 2.1.2
     * output (docs/JAPANESE_NLP_AUDIT_AND_PLAN.md §2.4).
     *
     * Song-specific aesthetic readings (gikun: 運命→さだめ etc.) are undecidable
     * from text and belong in a future per-track override store, not here.
     *
     * Known low-frequency reading edges are documented in
     * docs/ROMANIZATION_AUDIT_BACKLOG.md JP-3.
     */
    private static void applyLexicalOverrides(List<Entry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            Token token = entry.token;
            if (token == null) continue;

            // UniDic 2.1.2 lacks the kanji orthography 響めく for どよめく (its own
            // lemma for どよめき is 響動めき; JMdict marks the word usually-kana), so
            // the tokenizer falls back to 響(ヒビキ)+めく suffix. 響 directly before
            // the めく suffix can only read どよ; standalone 響/響く are untouched.
            if ("響".equals(entry.surface) && i + 1 < entries.size()) {
                Token next = entries.get(i + 1).token;
                if (next != null && "接尾辞".equals(safe(next.getPartOfSpeechLevel1()))
                        && "めく".equals(safe(next.getLemma()))) {
                    entry.readingKana = "どよ";
                    entry.readingReason = "lexicalOverride:doyomeku";
                    continue;
                }
            }

            // UniDic prefers the formal ワタクシ for bare 私; sung Japanese is
            // essentially always わたし. POS-guarded so 私立 etc. are untouched.
            if ("私".equals(entry.surface) && "代名詞".equals(token.getPartOfSpeechLevel1())) {
                entry.readingKana = "わたし";
                entry.readingReason = "lexicalOverride:watashiPronoun";
                continue;
            }

            // UniDic can tag bare 君 as the honorific suffix reading クン. As an independent
            // pronoun in lyrics it should read きみ; suffix use remains "kun" in 田中君.
            if ("君".equals(entry.surface) && "代名詞".equals(token.getPartOfSpeechLevel1())) {
                entry.readingKana = "きみ";
                entry.readingReason = "lexicalOverride:kimiPronoun";
                continue;
            }

            // Rendaku: 〜方 as a plural-person suffix directly after a pronoun reads
            // がた (あなた方/君方). kuromoji-unidic 2.1.2 emits unvoiced カタ, tagged
            // 接尾辞 or 名詞 depending on context. Demonstratives (この方) are 連体詞,
            // so "kono kata" is untouched.
            if ("方".equals(entry.surface) && "かた".equals(entry.readingKana) && i > 0) {
                String pos1 = safe(token.getPartOfSpeechLevel1());
                Token prev = entries.get(i - 1).token;
                if (("接尾辞".equals(pos1) || "名詞".equals(pos1))
                        && prev != null && "代名詞".equals(prev.getPartOfSpeechLevel1())) {
                    entry.readingKana = "がた";
                    entry.readingReason = "lexicalOverride:gataAfterPronoun";
                }
            }
        }
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
        String romaji = romanizeKana(entry.readingKana);
        return isBlank(romaji) ? entry.surface : romaji;
    }

    /**
     * A token-final っ geminates the next token's initial consonant (言っ+て → itte).
     * romanizeKana drops a trailing っ, so double the consonant on the next entry.
     */
    private static void applyCrossTokenSokuon(List<Entry> entries) {
        for (int i = 0; i + 1 < entries.size(); i++) {
            Entry entry = entries.get(i);
            Entry next = entries.get(i + 1);
            if ("一".equals(entry.surface)
                    && ("いち".equals(entry.readingKana) || "ichi".equals(entry.romaji))
                    && "歩".equals(next.surface)
                    && "ほ".equals(next.readingKana)) {
                // UniDic splits 一歩, but lexical reading is いっぽ (ippo), not ichi ho.
                entry.readingKana = "いっ";
                entry.romaji = "";
                next.readingKana = "ぽ";
                next.romaji = "ippo";
                continue;
            }
            if ("一".equals(entry.surface)
                    && ("いち".equals(entry.readingKana) || "ichi".equals(entry.romaji))
                    && startsWithConsonant(next.romaji)
                    && startsWithKRow(next.readingKana)) {
                entry.readingKana = "いっ";
                entry.romaji = "";
                next.romaji = "i" + next.romaji.charAt(0) + next.romaji;
                continue;
            }
            if (entry.readingKana != null && entry.readingKana.endsWith("っ") && startsWithConsonant(next.romaji)) {
                next.romaji = next.romaji.charAt(0) + next.romaji;
            }
        }
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
        for (Entry entry : entries) {
            if (isBlank(entry.readingKana)) continue;
            if ("providerRubyValidated".equals(entry.readingReason) && provider != null) {
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
        if (entry.surface.matches("^[。、？！…・「」『』（）().?!,\\s]+$")) return true;
        if (shouldMergeNonJapaneseAscii(entry, prevEntry)) return true;
        if (shouldMergeJapaneseVerbContinuation(entry, prevEntry)) return true;
        if (shouldMergeMekuSuffix(entry, prevEntry)) return true;
        return entry.romaji != null && entry.romaji.length() == 1 && !Character.isLetterOrDigit(entry.romaji.charAt(0));
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

    private static boolean isKanaOnly(String value) {
        if (isBlank(value)) return false;
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            if (!((cp >= 0x3040 && cp <= 0x30FF) || cp == 'ー')) return false;
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
                {"ぴゃ", "pya"}, {"ぴゅ", "pyu"}, {"ぴょ", "pyo"}
        };
        for (String[] pair : base) putKana(pair[0], pair[1]);
    }

}
