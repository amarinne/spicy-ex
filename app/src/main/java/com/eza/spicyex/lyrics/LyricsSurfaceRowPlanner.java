package com.eza.spicyex.lyrics;

import static com.eza.spicyex.lyrics.LyricUtils.isBlank;

/**
 * Shared row-shape planner for fullscreen and now-playing surfaces.
 *
 * Surface hosts may choose size, spacing, secondary-line visibility, and overflow policy, but
 * lyric semantics stay here: synthetic word creation, fill routing, furigana mode, and attached
 * transliteration wiring.
 */
public final class LyricsSurfaceRowPlanner {
    private LyricsSurfaceRowPlanner() {
    }

    public static RowPlan plan(
            AppliedLine line,
            LyricsDocument document,
            SurfacePolicy policy,
            LyricsRowViewFactory.RomanizedWordProvider romanizedWordProvider
    ) {
        SurfacePolicy safePolicy = policy == null ? SurfacePolicy.defaultPolicy() : policy;
        AppliedLine displayLine = displayLineForPolicy(line, safePolicy);
        ensureAlignedWordsForSentenceSync(displayLine, safePolicy);

        LyricsRowViewFactory.Options options = new LyricsRowViewFactory.Options();
        options.lineSpacingMultiplier = safePolicy.lineSpacingMultiplier;
        options.showRomanization = safePolicy.showRomanization;
        options.showTranslation = safePolicy.showTranslation;
        options.showJapaneseFurigana = LyricsDisplayMode.showJapaneseFurigana(
                displayLine, safePolicy.showRomanization, safePolicy.japaneseReadingMode);
        options.showJapaneseRomaji = LyricsDisplayMode.showJapaneseRomaji(
                displayLine, safePolicy.showRomanization, safePolicy.japaneseReadingMode);
        options.attachTransliterationToWords = safePolicy.attachTransliterationToWords;
        options.lineLevelFillTopDown = safePolicy.lineLevelFillTopDown;
        options.lineLevelFillSentence = safePolicy.lineLevelFillSentence;
        options.wordLevelFill = safePolicy.wordLevelFill;
        options.interludeNoteIcon = safePolicy.interludeNoteIcon;
        options.lyricWeight = safePolicy.lyricWeight;
        options.lyricsFont = safePolicy.lyricsFont;
        options.textSizeMultiplier = safePolicy.textSizeMultiplier;
        options.translationBright = safePolicy.translationBright;
        options.wrapLongLines = safePolicy.wrapLongLines;
        options.horizontalSafetyPadding = safePolicy.horizontalSafetyPadding;

        boolean hasWords = displayLine != null && displayLine.words != null && !displayLine.words.isEmpty();
        boolean alignedRomaji = hasWords
                && !options.showJapaneseFurigana
                && options.attachTransliterationToWords
                && options.showRomanization
                && displayLine != null
                && !isBlank(displayLine.romanizedText);
        options.documentText = alignedRomaji && document != null ? LyricsDocumentProcessor.collectText(document) : "";
        return new RowPlan(displayLine, options, alignedRomaji ? romanizedWordProvider : null);
    }

    private static AppliedLine displayLineForPolicy(AppliedLine line, SurfacePolicy policy) {
        if (line == null || policy == null || !policy.forceStartAligned || !line.oppositeAligned) {
            return line;
        }
        AppliedLine copy = new AppliedLine();
        copy.text = line.text;
        copy.romanizedText = line.romanizedText;
        copy.translatedText = line.translatedText;
        copy.japaneseReading = line.japaneseReading;
        copy.words.addAll(line.words);
        copy.syntheticWords = line.syntheticWords;
        copy.sourceLine = line.sourceLine;
        copy.startMs = line.startMs;
        copy.endMs = line.endMs;
        copy.totalMs = line.totalMs;
        copy.dotLine = line.dotLine;
        copy.bgLine = line.bgLine;
        copy.oppositeAligned = false;
        return copy;
    }

    private static void ensureAlignedWordsForSentenceSync(AppliedLine line, SurfacePolicy policy) {
        boolean needsAttachedRomanization = policy.attachTransliterationToWords && policy.showRomanization;
        boolean needsSyntheticWords = needsAttachedRomanization || policy.lineLevelFillSentence || policy.wordLevelFill;
        if (line == null || line.dotLine || line.bgLine) return;
        if (line.syntheticWords && !needsSyntheticWords) {
            line.words.clear();
            line.syntheticWords = false;
            return;
        }
        if (!needsSyntheticWords) return;
        if (!line.words.isEmpty()) return;
        if (isJapaneseLine(line)) return;
        String text = line.text == null ? "" : line.text.trim();
        if (text.isEmpty()) return;
        if (needsAttachedRomanization
                && !policy.lineLevelFillSentence
                && !policy.wordLevelFill
                && isBlank(line.romanizedText)) {
            return;
        }
        if (!text.contains(" ")) return;
        String[] parts = text.split("\\s+");
        if (parts.length < 2) return;
        int totalChars = 0;
        for (String part : parts) totalChars += Math.max(1, part.length());
        long span = Math.max(1L, line.endMs - line.startMs);
        long cursor = line.startMs;
        int acc = 0;
        for (int i = 0; i < parts.length; i++) {
            acc += Math.max(1, parts[i].length());
            long end = (i == parts.length - 1)
                    ? line.endMs
                    : line.startMs + span * acc / totalChars;
            SyllableSegment seg = new SyllableSegment();
            seg.text = parts[i];
            seg.startMs = cursor;
            seg.endMs = Math.max(cursor + 1, end);
            seg.totalMs = seg.endMs - seg.startMs;
            seg.partOfWord = false;
            line.words.add(seg);
            cursor = seg.endMs;
        }
        line.syntheticWords = true;
    }

    private static boolean isJapaneseLine(AppliedLine line) {
        return hasJapaneseReading(line) || (line != null && SpicyTextDetection.hasKana(line.text));
    }

    private static boolean hasJapaneseReading(AppliedLine line) {
        return line != null
                && line.japaneseReading != null
                && line.japaneseReading.furigana != null
                && !line.japaneseReading.furigana.isEmpty();
    }

    public static final class RowPlan {
        public final AppliedLine line;
        public final LyricsRowViewFactory.Options options;
        public final LyricsRowViewFactory.RomanizedWordProvider romanizedWordProvider;

        RowPlan(AppliedLine line,
                LyricsRowViewFactory.Options options,
                LyricsRowViewFactory.RomanizedWordProvider romanizedWordProvider) {
            this.line = line;
            this.options = options;
            this.romanizedWordProvider = romanizedWordProvider;
        }
    }

    public static final class SurfacePolicy {
        public final float lineSpacingMultiplier;
        public final boolean showRomanization;
        public final boolean showTranslation;
        public final String japaneseReadingMode;
        public final boolean attachTransliterationToWords;
        public final boolean lineLevelFillTopDown;
        public final boolean lineLevelFillSentence;
        public final boolean wordLevelFill;
        public final boolean interludeNoteIcon;
        public final String lyricWeight;
        public final String lyricsFont;
        public final float textSizeMultiplier;
        public final boolean translationBright;
        public final boolean wrapLongLines;
        public final boolean forceStartAligned;
        public final boolean horizontalSafetyPadding;

        public SurfacePolicy(
                float lineSpacingMultiplier,
                boolean showRomanization,
                boolean showTranslation,
                String japaneseReadingMode,
                boolean attachTransliterationToWords,
                boolean lineLevelFillTopDown,
                boolean lineLevelFillSentence,
                boolean wordLevelFill,
                boolean interludeNoteIcon,
                String lyricWeight,
                String lyricsFont,
                float textSizeMultiplier,
                boolean translationBright,
                boolean wrapLongLines
        ) {
            this(lineSpacingMultiplier, showRomanization, showTranslation, japaneseReadingMode,
                    attachTransliterationToWords, lineLevelFillTopDown, lineLevelFillSentence,
                    wordLevelFill, interludeNoteIcon, lyricWeight, lyricsFont, textSizeMultiplier,
                    translationBright, wrapLongLines, false);
        }

        public SurfacePolicy(
                float lineSpacingMultiplier,
                boolean showRomanization,
                boolean showTranslation,
                String japaneseReadingMode,
                boolean attachTransliterationToWords,
                boolean lineLevelFillTopDown,
                boolean lineLevelFillSentence,
                boolean wordLevelFill,
                boolean interludeNoteIcon,
                String lyricWeight,
                String lyricsFont,
                float textSizeMultiplier,
                boolean translationBright,
                boolean wrapLongLines,
                boolean forceStartAligned
        ) {
            this(lineSpacingMultiplier, showRomanization, showTranslation, japaneseReadingMode,
                    attachTransliterationToWords, lineLevelFillTopDown, lineLevelFillSentence,
                    wordLevelFill, interludeNoteIcon, lyricWeight, lyricsFont, textSizeMultiplier,
                    translationBright, wrapLongLines, forceStartAligned, true);
        }

        public SurfacePolicy(
                float lineSpacingMultiplier,
                boolean showRomanization,
                boolean showTranslation,
                String japaneseReadingMode,
                boolean attachTransliterationToWords,
                boolean lineLevelFillTopDown,
                boolean lineLevelFillSentence,
                boolean wordLevelFill,
                boolean interludeNoteIcon,
                String lyricWeight,
                String lyricsFont,
                float textSizeMultiplier,
                boolean translationBright,
                boolean wrapLongLines,
                boolean forceStartAligned,
                boolean horizontalSafetyPadding
        ) {
            this.lineSpacingMultiplier = lineSpacingMultiplier;
            this.showRomanization = showRomanization;
            this.showTranslation = showTranslation;
            this.japaneseReadingMode = japaneseReadingMode == null ? "" : japaneseReadingMode;
            this.attachTransliterationToWords = attachTransliterationToWords;
            this.lineLevelFillTopDown = lineLevelFillTopDown;
            this.lineLevelFillSentence = lineLevelFillSentence;
            this.wordLevelFill = wordLevelFill;
            this.interludeNoteIcon = interludeNoteIcon;
            this.lyricWeight = lyricWeight == null ? "Medium" : lyricWeight;
            this.lyricsFont = lyricsFont == null ? "default" : lyricsFont;
            this.textSizeMultiplier = textSizeMultiplier;
            this.translationBright = translationBright;
            this.wrapLongLines = wrapLongLines;
            this.forceStartAligned = forceStartAligned;
            this.horizontalSafetyPadding = horizontalSafetyPadding;
        }

        public static SurfacePolicy fullscreen(
                LyricsRenderConfig config,
                boolean showRomanization,
                boolean showTranslation,
                String japaneseReadingMode
        ) {
            LyricsRenderConfig cfg = config;
            return new SurfacePolicy(
                    cfg == null ? 1f : cfg.lineSpacingMultiplier,
                    showRomanization,
                    showTranslation,
                    japaneseReadingMode,
                    cfg != null && cfg.attachTransliterationToWords,
                    cfg != null && cfg.lineSyncFillTopDown(),
                    cfg != null && cfg.lineSyncFillSentence(),
                    cfg != null && cfg.lineSyncFillWord(),
                    cfg != null && cfg.interludeNoteIcon,
                    cfg == null ? "Medium" : cfg.lyricWeight,
                    cfg == null ? "default" : cfg.lyricsFont,
                    cfg == null ? 1f : cfg.lyricsTextSizeMultiplier,
                    cfg != null && cfg.translationBright,
                    true,
                    false);
        }

        public static SurfacePolicy liveCard(LyricsRenderConfig config) {
            LyricsRenderConfig cfg = config;
            boolean scrollOverflow = cfg != null && "Scroll with lyric".equals(cfg.liveCardOverflowMode);
            boolean wrapOverflow = cfg != null && "Wrap".equals(cfg.liveCardOverflowMode);
            return new SurfacePolicy(
                    0.30f,
                    cfg != null && cfg.liveCardShowTransliteration,
                    cfg != null && cfg.liveCardShowTranslation,
                    cfg == null ? "" : cfg.defaultJapaneseReadingMode,
                    cfg != null && cfg.attachTransliterationToWords,
                    cfg != null && cfg.lineSyncFillTopDown(),
                    cfg != null && cfg.lineSyncFillSentence(),
                    cfg != null && cfg.lineSyncFillWord(),
                    cfg != null && cfg.interludeNoteIcon,
                    cfg == null ? "Medium" : cfg.liveCardWeight,
                    cfg == null ? "default" : cfg.lyricsFont,
                    cfg == null ? 1f : Math.max(0.50f, 0.68f * cfg.liveCardTextSizeMultiplier),
                    cfg != null && cfg.translationBright,
                    wrapOverflow,
                    scrollOverflow,
                    wrapOverflow);
        }

        public static SurfacePolicy defaultPolicy() {
            return new SurfacePolicy(1f, false, false, "", false, false, false,
                    false, false, "Medium", "default", 1f, false, true, false);
        }
    }
}
