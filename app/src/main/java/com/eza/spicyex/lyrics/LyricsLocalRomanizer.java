package com.eza.spicyex.lyrics;

import com.eza.spicyex.SpotifyPlusConfig;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/** Local Japanese/Chinese/generic romanization helpers for lyric documents. */
public final class LyricsLocalRomanizer {
    private static final String TAG = "[SpotifyPlusLocalRomanizer]";

    private LyricsLocalRomanizer() {
    }

    public static boolean shouldLocalRomanize(boolean showRomanization, String chineseMode, LyricsDocument doc, LyricsLine line, String fullText) {
        if (!showRomanization || line == null || isBlank(line.text)) return false;
        List<SpicyTextDetection.Script> scripts = scriptsFor(doc, fullText);
        boolean chineseLine = isChineseLine(doc, line.text, fullText);
        if (chineseLine && isBlank(chineseMode)) return false;
        boolean needsChineseMode = chineseLine
                && (isBlank(line.chineseMode) || !normalizeChineseMode(line.chineseMode).equals(normalizeChineseMode(chineseMode)));
        if (needsChineseMode) return true;
        boolean needsJapaneseReading = isJapaneseLine(doc, line.text, fullText);
        if (needsJapaneseReading) return true;
        if (!shouldGoogleRomanize(showRomanization, line)) return false;
        return SpicyRomanizer.canRomanizeLocally(line.text, scripts, doc == null ? "" : doc.language);
    }

    public static String romanizeLine(RomanizationOptions opts, LyricsDocument doc, LyricsLine line, String fullText) {
        try {
            List<SpicyTextDetection.Script> scripts = scriptsFor(doc, fullText);
            if (doc != null && isChineseLine(doc, line.text, fullText)) {
                if (opts == null || isBlank(opts.chineseMode)) return "";
                // Clear stale JP reading state from older/wrong cycles so Chinese rows cannot render furigana.
                line.japaneseReading = new SpicyJapaneseChineseProcessor.JapaneseReading("", "", new ArrayList<>());
                line.chineseMode = normalizeChineseMode(opts.chineseMode);
                return SpicyJapaneseChineseProcessor.romanizeChineseLine(line.text, line.chineseMode, opts.chineseTones);
            }
            if (doc != null && isJapaneseLine(doc, line.text, fullText)) {
                SpicyJapaneseChineseProcessor.JapaneseReading local =
                        SpicyJapaneseChineseProcessor.analyzeJapaneseLine(line.text, null);
                if (local != null) {
                    boolean hasProviderFurigana = line.japaneseReading != null
                            && line.japaneseReading.furigana != null
                            && !line.japaneseReading.furigana.isEmpty();
                    if (hasProviderFurigana) {
                        SpicyJapaneseChineseProcessor.JapaneseReading providerAware =
                                SpicyJapaneseChineseProcessor.analyzeJapaneseLineWithProviderFurigana(
                                line.text, line.japaneseReading.furigana);
                        if (providerAware != null) line.japaneseReading = providerAware;
                        String romaji = providerAware == null ? "" : providerAware.romaji;
                        if (!isBlank(romaji)) return romaji;
                        return "";
                    }
                    line.japaneseReading = local;
                    if (!isBlank(local.romaji)) return local.romaji;
                }
                return "";
            }
            return SpicyRomanizer.romanizeLine(line.text, scripts, doc == null ? "" : doc.language, opts);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " local romanization failed: " + t);
            return "";
        }
    }

    public static void populateLocalSegmentRomanization(RomanizationOptions opts, LyricsDocument doc, LyricsLine line, String fullText) {
        if (line == null || line.syllables == null || line.syllables.isEmpty()) return;
        List<SpicyTextDetection.Script> scripts = scriptsFor(doc, fullText);
        if (isJapaneseLine(doc, line.text, fullText)) {
            ArrayList<String> syllableTexts = new ArrayList<>();
            for (SyllableSegment seg : line.syllables) syllableTexts.add(seg == null ? "" : seg.text);
            List<String> localSyllables = SpicyJapaneseChineseProcessor.romanizeJapaneseSyllables(line.text, syllableTexts);
            if (localSyllables.size() == line.syllables.size()) {
                for (int i = 0; i < line.syllables.size(); i++) {
                    SyllableSegment seg = line.syllables.get(i);
                    if (seg == null || !isBlank(seg.romanizedText)) continue;
                    String local = localSyllables.get(i);
                    seg.romanizedText = !isBlank(local) && !local.equals(seg.text) && !SpicyTextDetection.hasRomanizableScript(local)
                            ? local : "";
                }
                return;
            }
        }
        if (opts != null && SpicyRomanizer.koreanFollowSound(opts.koreanMode)
                && scripts.contains(SpicyTextDetection.Script.KOREAN)
                && SpicyTextDetection.itemKoreanTest(line.text)
                && populateKoreanPronunciationSegments(line)) {
            return;
        }
        for (SyllableSegment seg : line.syllables) {
            if (seg == null || isBlank(seg.text)) continue;
            if (!isBlank(seg.romanizedText)) continue;
            String local = romanizeText(opts, doc, seg.text, fullText, line.chineseMode);
            seg.romanizedText = !isBlank(local) && !local.equals(seg.text) && !SpicyTextDetection.hasRomanizableScript(local)
                    ? local : "";
        }
    }

    private static boolean populateKoreanPronunciationSegments(LyricsLine line) {
        if (line == null || isBlank(line.text) || line.syllables == null || line.syllables.isEmpty()) return false;
        List<String> pieces = SpicyKoreanG2P.romanizeSyllablePieces(line.text);
        if (pieces.isEmpty()) return false;
        int searchFrom = 0;
        boolean changed = false;
        for (SyllableSegment seg : line.syllables) {
            if (seg == null || isBlank(seg.text) || !isBlank(seg.romanizedText)) continue;
            int start = line.text.indexOf(seg.text, searchFrom);
            if (start < 0) start = line.text.indexOf(seg.text);
            if (start < 0) continue;
            int pieceStart = line.text.codePointCount(0, start);
            int pieceCount = seg.text.codePointCount(0, seg.text.length());
            if (pieceStart < 0 || pieceStart + pieceCount > pieces.size()) continue;
            StringBuilder local = new StringBuilder();
            for (int i = pieceStart; i < pieceStart + pieceCount; i++) local.append(pieces.get(i));
            String value = local.toString();
            if (!isBlank(value) && !value.equals(seg.text) && !SpicyTextDetection.hasRomanizableScript(value)) {
                seg.romanizedText = value;
                changed = true;
            }
            searchFrom = start + seg.text.length();
        }
        return changed;
    }

    public static void clearSegmentRomanization(LyricsLine line) {
        if (line == null || line.syllables == null) return;
        for (SyllableSegment seg : line.syllables) {
            if (seg != null) seg.romanizedText = "";
        }
    }

    public static boolean shouldGoogleRomanize(boolean showRomanization, LyricsLine line) {
        if (!showRomanization || line == null || isBlank(line.text) || !SpicyTextDetection.hasRomanizableScript(line.text)) return false;
        return isBlank(line.romanizedText) || SpicyTextDetection.hasRomanizableScript(line.romanizedText);
    }

    public static boolean shouldGoogleTranslate(LyricsDocument doc, LyricsLine line) {
        if (line == null || isBlank(line.text) || !isBlank(line.translatedText)) return false;
        return SpicyProcessing.shouldTranslateLine(line.text, doc == null ? "" : doc.language, "en");
    }

    public static String normalizeChineseMode(String mode) {
        if ("jyutping".equalsIgnoreCase(mode) || "cantonese".equalsIgnoreCase(mode)) return SpotifyPlusConfig.CHINESE_MODE_JYUTPING;
        return SpotifyPlusConfig.CHINESE_MODE_PINYIN;
    }

    public static String romanizeText(RomanizationOptions opts, LyricsDocument doc, String text, String fullText, String lineChineseMode) {
        try {
            String language = doc == null ? "" : doc.language;
            List<SpicyTextDetection.Script> scripts = scriptsFor(doc, fullText);
            if (isChineseLine(doc, text, fullText)) {
                if (opts == null || isBlank(opts.chineseMode)) return "";
                String mode = normalizeChineseMode(isBlank(lineChineseMode) ? opts.chineseMode : lineChineseMode);
                return SpicyJapaneseChineseProcessor.romanizeChineseLine(text, mode, opts.chineseTones);
            }
            if (isJapaneseLine(doc, text, fullText)) {
                SpicyJapaneseChineseProcessor.JapaneseReading local =
                        SpicyJapaneseChineseProcessor.analyzeJapaneseLine(text, null);
                return local == null ? "" : safe(local.romaji);
            }
            return SpicyRomanizer.romanizeLine(text, scripts, language, opts);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " local segment romanization failed: " + t);
            return "";
        }
    }

    private static boolean isChineseLine(LyricsDocument doc, String text, String fullText) {
        return hanLineScript(doc, text, fullText) == SpicyTextDetection.Script.CHINESE;
    }

    private static boolean isJapaneseLine(LyricsDocument doc, String text, String fullText) {
        if (SpicyTextDetection.hasKana(text)) return true;
        return hanLineScript(doc, text, fullText) == SpicyTextDetection.Script.JAPANESE;
    }

    private static SpicyTextDetection.Script hanLineScript(LyricsDocument doc, String text, String fullText) {
        if (!SpicyTextDetection.itemChineseTest(text)) return null;
        if (SpicyTextDetection.hasKana(text)) return SpicyTextDetection.Script.JAPANESE;
        if (documentHasKana(doc, fullText)) return SpicyTextDetection.Script.JAPANESE;
        SpicyTextDetection.Script languageScript = SpicyTextDetection.scriptFromLanguage(
                doc == null ? "" : doc.language, "");
        if (languageScript == SpicyTextDetection.Script.JAPANESE) return SpicyTextDetection.Script.JAPANESE;
        if (languageScript == SpicyTextDetection.Script.CHINESE) return SpicyTextDetection.Script.CHINESE;
        return SpicyTextDetection.Script.CHINESE;
    }

    private static boolean documentHasKana(LyricsDocument doc, String fullText) {
        if (!isBlank(fullText)) return SpicyTextDetection.hasKana(fullText);
        if (doc == null || doc.lines == null) return false;
        for (LyricsLine line : doc.lines) {
            if (line != null && SpicyTextDetection.hasKana(line.text)) return true;
        }
        return false;
    }

    private static List<SpicyTextDetection.Script> scriptsFor(LyricsDocument doc, String fullText) {
        if (doc != null && !doc.detectedScripts.isEmpty()) return doc.detectedScripts;
        return SpicyTextDetection.detectPresentScripts(fullText, doc == null ? "" : doc.language, "");
    }

}
