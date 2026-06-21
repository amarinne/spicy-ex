package com.eza.spicyex.lyrics;

import com.atilika.kuromoji.unidic.Token;
import com.atilika.kuromoji.unidic.Tokenizer;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
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
 * - Furigana spans whole kanji runs (jukugo ruby); we never guess per-kanji
 *   splits. Per-kanji segmentation, if ever wanted, comes from dictionary
 *   data (JmdictFurigana), not heuristics.
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
        int start;
        int end;
        String surface;
        String readingKana; // hiragana reading-of-record; null when token has no reading
        String romaji;
        Token token;
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

    private static volatile Tokenizer tokenizer;
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

        List<Entry> entries = buildEntries(sourceText);
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

        List<Entry> entries = buildEntries(sourceText);
        if (entries.isEmpty()) return null;

        applyProviderFuriganaOverrides(sourceText, entries, furigana);
        applyLexicalOverrides(entries);
        for (Entry entry : entries) entry.romaji = entryRomaji(entry);
        applyCrossTokenSokuon(entries);
        return new JapaneseReading(sourceText, buildRomaji(entries), buildFurigana(sourceText, entries));
    }

    private static void applyProviderFuriganaOverrides(String sourceText, List<Entry> entries, List<FuriganaSegment> furigana) {
        ArrayList<FuriganaSegment> sorted = new ArrayList<>(furigana);
        sorted.sort((a, b) -> Integer.compare(a.start, b.start));

        for (Entry entry : entries) {
            String reading = readingFromProviderFurigana(sourceText, entry.start, entry.end, sorted);
            if (!isBlank(reading)) entry.readingKana = reading;
        }
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
            if (isKanjiChar(ch) && segment != null && segment.start <= pos && segment.end > pos) {
                if (pos == segment.start) {
                    reading.append(kataToHira(segment.reading));
                    usedProvider = true;
                }
                pos = Math.min(end, segment.end);
                continue;
            }
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

        List<Entry> entries = buildEntries(sourceText);
        if (entries.isEmpty()) return out;

        int syllPos = 0;
        int prevLastIndex = -1;
        for (int si = 0; si < syllableTexts.size(); si++) {
            String syllableText = Normalizer.normalize(safe(syllableTexts.get(si)), Normalizer.Form.NFKC);
            while (syllPos < sourceText.length() && Character.isWhitespace(sourceText.charAt(syllPos))) syllPos++;
            int syllStart = syllPos;
            int syllEnd = Math.min(sourceText.length(), syllStart + syllableText.length());
            syllPos = syllEnd;

            StringBuilder romaji = new StringBuilder();
            int lastIndex = -1;
            for (int ei = 0; ei < entries.size(); ei++) {
                Entry entry = entries.get(ei);
                if (isBlank(entry.romaji)) continue;
                if (entry.end <= syllStart || entry.start >= syllEnd) continue; // no overlap
                // Emit each token's WHOLE romaji once, at the syllable where the token begins.
                // Continuation syllables (token started earlier) stay blank. This keeps readings
                // intact — ちゃった -> "chatta", not the per-char "chi"+"ya" the old slicer produced —
                // and never drops/garbles a word from positional slicing drift.
                if (entry.start >= syllStart) {
                    Entry prevEntry = ei > 0 ? entries.get(ei - 1) : null;
                    boolean noSpaceBefore = shouldNoSpaceBefore(entry, prevEntry);
                    if (romaji.length() > 0 && !noSpaceBefore) romaji.append(' ');
                    romaji.append(entry.romaji);
                    lastIndex = ei;
                }
            }
            if (lastIndex >= 0) prevLastIndex = lastIndex;
            out.set(si, normalizeSpaces(romaji.toString()));
        }
        return out;
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
        List<Token> tokens;
        try {
            tokens = tokenizer().tokenize(sourceText);
        } catch (Throwable t) {
            return new ArrayList<>();
        }
        List<Entry> entries = new ArrayList<>();
        int charPos = 0;
        for (Token token : tokens) {
            String surface = safe(token.getSurface());
            Entry entry = new Entry();
            entry.start = charPos;
            entry.end = charPos + surface.length();
            entry.surface = surface;
            entry.token = token;
            entry.readingKana = readingOfRecord(token, surface);
            entries.add(entry);
            charPos += surface.length();
        }
        applyLexicalOverrides(entries);
        for (Entry entry : entries) entry.romaji = entryRomaji(entry);
        applyCrossTokenSokuon(entries);
        return entries;
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
     */
    private static void applyLexicalOverrides(List<Entry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            Token token = entry.token;
            if (token == null) continue;

            // UniDic prefers the formal ワタクシ for bare 私; sung Japanese is
            // essentially always わたし. POS-guarded so 私立 etc. are untouched.
            if ("私".equals(entry.surface) && "代名詞".equals(token.getPartOfSpeechLevel1())) {
                entry.readingKana = "わたし";
                continue;
            }

            // UniDic can tag bare 君 as the honorific suffix reading クン. As an independent
            // pronoun in lyrics it should read きみ; suffix use remains "kun" in 田中君.
            if ("君".equals(entry.surface) && "代名詞".equals(token.getPartOfSpeechLevel1())) {
                entry.readingKana = "きみ";
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
        StringBuilder out = new StringBuilder();
        boolean lastWasPinyin = false;
        for (int i = 0; i < text.length(); ) {
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
        List<FuriganaSegment> out = new ArrayList<>();
        for (Entry entry : entries) {
            if (isBlank(entry.readingKana)) continue;
            List<TokenFuriganaReading> tokenSegments = kanaReadingSegments(entry.surface, entry.readingKana);
            for (TokenFuriganaReading segment : tokenSegments) {
                if (isBlank(segment.text)) continue;
                int start = Math.max(0, Math.min(lineText.length(), entry.start + segment.targetStart));
                int end = Math.max(start + 1, Math.min(lineText.length(), entry.start + segment.targetEnd));
                out.add(new FuriganaSegment(start, end, segment.text));
            }
        }
        return out;
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
            String nextKana = null;
            for (int i = charIndex; i < chars.size(); i++) {
                if (isKanaChar(chars.get(i))) {
                    nextKana = chars.get(i);
                    break;
                }
            }
            int readingStart = kanaCursor;
            if (nextKana != null) {
                int nextIndex = kana.indexOf(nextKana, kanaCursor);
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
        if (shouldMergeJapaneseVerbContinuation(entry, prevEntry)) return true;
        return entry.romaji != null && entry.romaji.length() == 1 && !Character.isLetterOrDigit(entry.romaji.charAt(0));
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
