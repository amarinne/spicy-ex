package com.eza.spicyex.lyrics;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.text.LineBreakConfig;
import android.os.Build;
import android.text.Layout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.RequiresApi;

import com.google.android.flexbox.AlignItems;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexWrap;
import com.google.android.flexbox.FlexboxLayout;
import com.google.android.flexbox.JustifyContent;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import com.eza.spicyex.lyrics.reading.ReadingModels.TimedReadingUnit;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;

/** Builds mounted Android views for applied lyric rows. */
public final class LyricsRowViewFactory {
    private final Activity activity;
    private final LyricsTextFactory textFactory;

    public LyricsRowViewFactory(Activity activity, LyricsTextFactory textFactory) {
        this.activity = activity;
        this.textFactory = textFactory;
    }

    public LinearLayout build(AppliedLine line, Options options, RowHeightListener heightListener) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(line.oppositeAligned ? Gravity.END : Gravity.START);
        boolean wrapLongLines = options == null || options.wrapLongLines;
        boolean horizontalSafetyPadding = options == null || options.horizontalSafetyPadding;
        float multiplier = options == null ? 1f : options.lineSpacingMultiplier;
        int leadingPadding = wrapLongLines && horizontalSafetyPadding ? dp(6) : 0;
        int trailingPadding = wrapLongLines && horizontalSafetyPadding ? dp(6) : 0;
        if (!wrapLongLines && horizontalSafetyPadding) {
            if (line.oppositeAligned) leadingPadding = dp(18);
            else trailingPadding = dp(18);
        }
        row.setPadding(leadingPadding, topClearancePx(dp(10), multiplier, 0f, false),
                trailingPadding, Math.round(dp(13) * multiplier));
        row.setClickable(false);
        row.setClipChildren(false);
        row.setClipToPadding(false);

        if (line.dotLine) {
            LinearLayout dots = new LinearLayout(activity);
            dots.setOrientation(LinearLayout.HORIZONTAL);
            dots.setGravity(line.oppositeAligned ? Gravity.END : Gravity.START);
            dots.setClipToPadding(false);
            LyricsLineViewState.beginDotViews(line);
            // A single music note reuses the same dot animation path (pulse/scale/glow); fewer
            // animated views is fine for the applier, which iterates dotViews defensively.
            boolean note = options != null && options.interludeNoteIcon;
            int glyphCount = note ? 1 : 3;
            for (int i = 0; i < glyphCount; i++) {
                SpicyAnimatedTextView dot = textFactory.createSecondaryAnimatedText(activity, note ? "♪" : "•", note ? 38 : 44, textFactory.resolveTypeface(true));
                dot.setGravity(Gravity.CENTER);
                dot.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(note ? 40 : 30), ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) dlp.leftMargin = dp(5);
                dots.addView(dot, dlp);
                LyricsLineViewState.addDotView(line, dot);
                if (line.words != null && i < line.words.size()) LyricsSyllableViewState.setWordView(line.words.get(i), dot);
            }
            row.addView(dots, new LinearLayout.LayoutParams(
                    wrapLongLines ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            attachHeightListener(row, line, heightListener);
            LyricsLineViewState.setRowView(line, row);
            return row;
        }

        boolean japaneseLine = isJapaneseLine(line);
        boolean chineseLine = !japaneseLine && SpicyTextDetection.itemChineseTest(line.text);
        boolean showJapaneseFurigana = japaneseLine && options.showRomanization && options.showJapaneseFurigana;
        boolean showJapaneseRomaji = japaneseLine && options.showRomanization && options.showJapaneseRomaji
                && line.readingRenderPlan != null;
        boolean showChineseRomaji = chineseLine && options.showRomanization && line.readingRenderPlan != null;
        String plannedReading = line.readingRenderPlan == null ? "" : line.readingRenderPlan.joinedDisplayText;
        boolean showGenericRomaji = !japaneseLine && !chineseLine && options.showRomanization
                && !isBlank(plannedReading);

        float sizeMultiplier = options == null ? 1f : options.textSizeMultiplier;
        LyricsLineViewState.setBaseTextSp(line, Math.max(1, Math.round(LyricVisuals.lyricTextSizeSp(line.text) * sizeMultiplier)));
        float baseTextPx = sp(LyricsLineViewState.baseTextSp(line));
        row.setPadding(leadingPadding, topClearancePx(dp(10), multiplier, baseTextPx, showJapaneseFurigana),
                trailingPadding, Math.round(dp(13) * multiplier));
        String weight = options == null ? "Medium" : options.lyricWeight;
        String font = options == null ? "spotify" : options.lyricsFont;
        LyricsLineViewState.clearMainView(line);
        boolean hasSyllableWords = line.words != null && !line.words.isEmpty();
        boolean hasRealTimedWords = hasSyllableWords && !line.syntheticWords;
        boolean indicLine = SpicyTextDetection.hasIndicScript(line.text);
        boolean furiganaCrossesWords = showJapaneseFurigana && FuriganaText.hasRubyCrossingWordBoundaries(line);
        boolean showAlignedRomaji = !indicLine
                && hasSyllableWords
                && !showJapaneseFurigana
                && options.attachTransliterationToWords
                && line.readingRenderPlan != null
                && line.readingRenderPlan.timedReadingUnits.size() >= line.words.size()
                && (showJapaneseRomaji || showChineseRomaji || showGenericRomaji);
        boolean useSyllableWords = !indicLine && !furiganaCrossesWords && (hasRealTimedWords || (hasSyllableWords
                && (options.wordLevelFill || options.lineLevelFillSentence || showJapaneseFurigana || showAlignedRomaji)));
        boolean lineLevelFillTopDown = !useSyllableWords && options.lineLevelFillTopDown;
        if (useSyllableWords) {
            buildSyllableWords(row, line, options, showJapaneseFurigana, showAlignedRomaji);
        } else {
            buildLineLevelMain(row, line, showJapaneseFurigana, lineLevelFillTopDown,
                    options.lineLevelFillSentence, weight, font, wrapLongLines,
                    options.adaptiveSectioningEnabled);
        }

        if (!line.bgLine && !showAlignedRomaji && (showJapaneseRomaji || showChineseRomaji || showGenericRomaji)) {
            String readingText = plannedReading;
            SpicyAnimatedTextView roman = textFactory.createSecondaryAnimatedText(activity, readingText, LyricVisuals.secondaryTextSizeSp(LyricsLineViewState.baseTextSp(line)), textFactory.resolveTypefaceForText(readingText, false));
            roman.setGravity(line.oppositeAligned ? Gravity.END : Gravity.START);
            roman.setMaxLines(wrapLongLines ? 3 : 1);
            applyAdaptiveWrapping(roman, wrapLongLines && options.adaptiveSectioningEnabled, false);
            roman.setSelfGlow(true);
            roman.setVerticalGradient(lineLevelFillTopDown);
            roman.setContentGradient(options.lineLevelFillSentence);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(2);
            if (!wrapLongLines) lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            row.addView(roman, lp);
            LyricsLineViewState.setRomanView(line, roman);
        }

        if (!line.bgLine && options.showTranslation && !isBlank(line.translatedText)) {
            Typeface translatedTypeface = LyricsTextFactory.shouldUseSystemFallbackForText(line.translatedText)
                    ? Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                    : Typeface.create(textFactory.resolveTypeface(false), Typeface.ITALIC);
            SpicyAnimatedTextView translated = textFactory.createSecondaryAnimatedText(activity, line.translatedText, Math.max(13, LyricVisuals.secondaryTextSizeSp(LyricsLineViewState.baseTextSp(line)) - 1), translatedTypeface);
            translated.setGravity(line.oppositeAligned ? Gravity.END : Gravity.START);
            translated.setMaxLines(wrapLongLines ? 3 : 1);
            applyAdaptiveWrapping(translated, wrapLongLines && options.adaptiveSectioningEnabled, false);
            translated.setAlpha(1f);
            translated.setBrightnessMultiplier(options.translationBright ? 1f : 0.42f);
            translated.setVerticalGradient(lineLevelFillTopDown);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(2);
            if (!wrapLongLines) lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            row.addView(translated, lp);
            LyricsLineViewState.setTranslationView(line, translated);
        }

        attachHeightListener(row, line, heightListener);
        LyricsLineViewState.setRowView(line, row);
        return row;
    }

    private void buildSyllableWords(
            LinearLayout row,
            AppliedLine line,
            Options options,
            boolean showJapaneseFurigana,
            boolean showAlignedRomaji
    ) {
        boolean wrapLongLines = options == null || options.wrapLongLines;
        ViewGroup words = wrapLongLines ? new GlowFlexbox(activity) : new LinearLayout(activity);
        if (words instanceof FlexboxLayout) {
            FlexboxLayout flex = (FlexboxLayout) words;
            flex.setFlexDirection(FlexDirection.ROW);
            flex.setFlexWrap(FlexWrap.WRAP);
            flex.setJustifyContent(line.oppositeAligned ? JustifyContent.FLEX_END : JustifyContent.FLEX_START);
            flex.setAlignItems(showJapaneseFurigana ? AlignItems.BASELINE : AlignItems.STRETCH);
        } else if (words instanceof LinearLayout) {
            LinearLayout linear = (LinearLayout) words;
            linear.setOrientation(LinearLayout.HORIZONTAL);
            linear.setGravity(line.oppositeAligned ? Gravity.RIGHT : Gravity.LEFT);
        }
        words.setClipToPadding(false);
        words.setClipChildren(false);
        if (showJapaneseFurigana) {
            words.setPadding(0, FuriganaText.rubyGapReservationPx(sp(LyricsLineViewState.baseTextSp(line))), 0, 0);
        }
        int furiganaOffset = 0;
        int wordIndex = 0;
        Map<String, TimedReadingUnit> timedBySpanId = timedBySpanId(line);
        for (SyllableSegment seg : line.words) {
            if (seg == null || isBlank(seg.text)) continue;
            int[] sourceRange = FuriganaText.wordRange(line, seg, wordIndex, furiganaOffset);
            int wordStart = sourceRange[0];
            furiganaOffset = Math.max(furiganaOffset, sourceRange[1]);
            View wordView = buildWordView(line, seg, showJapaneseFurigana, wordStart,
                    options == null ? "Medium" : options.lyricWeight,
                    options == null ? "spotify" : options.lyricsFont);
            String romanizedWordText = "";
            if (showAlignedRomaji && line.readingRenderPlan != null) {
                romanizedWordText = timedTextForSpan(timedBySpanId, spanId(seg, wordIndex));
            }
            if (showAlignedRomaji && !isBlank(romanizedWordText)) {
                wordView = stackRomanizedWord(line, seg, wordView, romanizedWordText);
            } else {
                LyricsSyllableViewState.clearRomanizedTextView(seg);
            }
            ViewGroup.MarginLayoutParams wlp = words instanceof FlexboxLayout
                    ? new FlexboxLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (seg.boundaryAfter) wlp.rightMargin = dp(8);
            words.addView(wordView, wlp);
            LyricsSyllableViewState.setWordView(seg, wordView);
            wordIndex++;
        }
        row.addView(words, new LinearLayout.LayoutParams(
                wrapLongLines ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static Map<String, TimedReadingUnit> timedBySpanId(AppliedLine line) {
        Map<String, TimedReadingUnit> out = new HashMap<>();
        if (line == null || line.readingRenderPlan == null) return out;
        for (TimedReadingUnit timed : line.readingRenderPlan.timedReadingUnits) {
            if (timed != null && timed.spanId != null) out.put(timed.spanId, timed);
        }
        return out;
    }

    private static String spanId(SyllableSegment segment, int fallbackIndex) {
        if (segment != null && segment.spanId != null && !segment.spanId.trim().isEmpty()) return segment.spanId;
        return String.valueOf(fallbackIndex);
    }

    /** Surface-only groups can represent several provider spans. Preserve plan ownership and join
     * their already-derived timed text here instead of synthesizing a replacement timed unit. */
    private static String timedTextForSpan(Map<String, TimedReadingUnit> timedBySpanId, String spanId) {
        TimedReadingUnit direct = timedBySpanId.get(spanId);
        if (direct != null) return direct.text == null ? "" : direct.text.trim();
        if (spanId == null || !spanId.contains("+")) return "";
        StringBuilder out = new StringBuilder();
        for (String id : spanId.split("\\+")) {
            TimedReadingUnit timed = timedBySpanId.get(id);
            if (timed != null && timed.text != null) out.append(timed.text);
        }
        return out.toString().trim();
    }

    private View buildWordView(AppliedLine line, SyllableSegment seg, boolean showJapaneseFurigana, int wordStart, String weight, String font) {
        int color = line.bgLine ? Color.rgb(170, 170, 170) : Color.WHITE;
        if (!showJapaneseFurigana && LyricVisuals.shouldUseLetterAnimator(seg)) {
            LinearLayout letters = new LinearLayout(activity);
            letters.setOrientation(LinearLayout.HORIZONTAL);
            letters.setClipToPadding(false);
            letters.setGravity(Gravity.CENTER_VERTICAL);
            LyricsSyllableViewState.clearLetters(seg);
            List<String> letterTexts = LyricVisuals.splitCodePoints(seg.text);
            float step = 1f / Math.max(1, letterTexts.size());
            float relativeStart = 0f;
            for (String text : letterTexts) {
                SpicyAnimatedTextView letterView = new SpicyAnimatedTextView(activity);
                letterView.setTextSize(LyricsLineViewState.baseTextSp(line));
                letterView.setTextColor(color);
                textFactory.applyLyricTypeface(letterView, text, weight, font);
                letterView.setIncludeFontPadding(true);
                letterView.setMaxLines(1);
                letterView.setGradientPosition(LyricAnimations.GRADIENT_UNSUNG, 0f);
                letters.addView(letterView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                AnimatedLetterState letter = new AnimatedLetterState();
                letter.start = relativeStart;
                letter.duration = step;
                letter.glowDuration = Math.max(step, 1f - relativeStart);
                letter.view = letterView;
                LyricsSyllableViewState.addLetter(seg, letter);
                relativeStart += step;
            }
            LyricsSyllableViewState.clearTextView(seg);
            return letters;
        }

        SpicyAnimatedTextView word = new SpicyAnimatedTextView(activity);
        CharSequence wordText = showJapaneseFurigana ? FuriganaText.buildWord(line, seg.text, wordStart) : seg.text;
        word.setTextSize(LyricsLineViewState.baseTextSp(line));
        word.setTextColor(color);
        textFactory.applyLyricTypeface(word, wordText, weight, font);
        word.setIncludeFontPadding(true);
        if (showJapaneseFurigana) {
            word.setPadding(0, FuriganaText.rubyGapReservationPx(sp(LyricsLineViewState.baseTextSp(line))), 0, 0);
        }
        word.setMaxLines(1);
        LyricsSyllableViewState.clearLetters(seg);
        LyricsSyllableViewState.setTextView(seg, word);
        return word;
    }

    private View stackRomanizedWord(AppliedLine line, SyllableSegment seg, View wordView, String romanizedWordText) {
        LinearLayout stack = new LinearLayout(activity);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setGravity(Gravity.CENTER);
        stack.setBaselineAligned(true);
        stack.setClipChildren(false);
        stack.setClipToPadding(false);
        stack.addView(wordView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        stack.setBaselineAlignedChildIndex(0);
        SpicyAnimatedTextView romanWord = textFactory.createSecondaryAnimatedText(activity, romanizedWordText, Math.max(11, LyricVisuals.secondaryTextSizeSp(LyricsLineViewState.baseTextSp(line)) - 2), textFactory.resolveTypefaceForText(romanizedWordText, false));
        romanWord.setGravity(Gravity.CENTER);
        romanWord.setMaxLines(1);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(-2);
        stack.addView(romanWord, rlp);
        LyricsSyllableViewState.setRomanizedTextView(seg, romanWord);
        return stack;
    }

    private void buildLineLevelMain(LinearLayout row, AppliedLine line, boolean showJapaneseFurigana,
                                    boolean lineLevelFillTopDown, boolean lineLevelFillSentence,
                                    String weight, String font, boolean wrapLongLines,
                                    boolean adaptiveSectioningEnabled) {
        int color = line.bgLine ? Color.rgb(170, 170, 170) : Color.WHITE;
        SpicyAnimatedTextView main = new SpicyAnimatedTextView(activity);
        CharSequence mainText = showJapaneseFurigana ? FuriganaText.build(line) : line.text;
        main.setTextSize(LyricsLineViewState.baseTextSp(line));
        main.setTextColor(color);
        textFactory.applyLyricTypeface(main, mainText, weight, font);
        main.setSelfGlow(true); // line-level row: no GlowFlexbox parent, draw its own halo
        main.setIncludeFontPadding(true);
        if (showJapaneseFurigana) {
            main.setPadding(0, FuriganaText.rubyGapReservationPx(sp(LyricsLineViewState.baseTextSp(line))), 0, 0);
        }
        main.setGravity(line.oppositeAligned ? Gravity.END : Gravity.START);
        main.setMaxLines(wrapLongLines || showJapaneseFurigana ? 4 : 1);
        applyAdaptiveWrapping(main,
                adaptiveSectioningEnabled && (wrapLongLines || showJapaneseFurigana),
                isCjkPhraseLine(line));
        main.setVerticalGradient(lineLevelFillTopDown);
        main.setContentGradient(lineLevelFillSentence);
        main.setGradientPosition(LyricAnimations.GRADIENT_UNSUNG, 0f);
        row.addView(main, new LinearLayout.LayoutParams(
                wrapLongLines ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LyricsLineViewState.setMainView(line, main);
    }

    @SuppressLint("WrongConstant") // API-23 Layout constants alias the newer LineBreaker IntDef values.
    private void applyAdaptiveWrapping(TextView view, boolean enabled, boolean cjkPhrase) {
        if (view == null) return;
        AdaptiveBreakMode mode = adaptiveBreakMode(enabled, cjkPhrase, Build.VERSION.SDK_INT);
        if (mode == AdaptiveBreakMode.NONE) return;
        if (mode == AdaptiveBreakMode.CJK_PHRASE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applyCjkPhraseWrapping(view);
        } else {
            view.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED);
        }
        view.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @SuppressLint("WrongConstant") // See applyAdaptiveWrapping: keep API-23-compatible constants.
    private void applyCjkPhraseWrapping(TextView view) {
        // Android's phrase mode uses CJK line-break dictionaries. Balanced breaking alone can
        // make visually even rows by putting short particles or punctuation at line start.
        view.setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY);
        view.setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_STRICT);
        view.setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE);
    }

    static AdaptiveBreakMode adaptiveBreakMode(boolean enabled, boolean cjkPhrase, int sdkInt) {
        if (!enabled || sdkInt < Build.VERSION_CODES.M) return AdaptiveBreakMode.NONE;
        if (cjkPhrase && sdkInt >= Build.VERSION_CODES.TIRAMISU) return AdaptiveBreakMode.CJK_PHRASE;
        return AdaptiveBreakMode.BALANCED;
    }

    enum AdaptiveBreakMode {
        NONE,
        BALANCED,
        CJK_PHRASE
    }

    private void attachHeightListener(LinearLayout row, AppliedLine line, RowHeightListener listener) {
        row.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom <= top) return;
            int height = bottom - top;
            if (LyricsLineViewState.updateMeasuredHeight(line, height)) {
                if (listener != null) listener.onRowHeightChanged();
            }
        });
    }

    private boolean isJapaneseLine(AppliedLine line) {
        return hasJapaneseReading(line) || (line != null && SpicyTextDetection.hasKana(line.text));
    }

    private boolean isCjkPhraseLine(AppliedLine line) {
        return isJapaneseLine(line) || (line != null && SpicyTextDetection.itemChineseTest(line.text));
    }

    private boolean hasJapaneseReading(AppliedLine line) {
        return line != null && line.japaneseReading != null && line.japaneseReading.furigana != null && !line.japaneseReading.furigana.isEmpty();
    }

    private int dp(int value) {
        float density = activity == null ? 1f : activity.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private float sp(float value) {
        float scaledDensity = activity == null ? 1f : activity.getResources().getDisplayMetrics().scaledDensity;
        return value * scaledDensity;
    }

    static int topClearancePx(int basePaddingPx, float multiplier, float baseTextPx, boolean showRuby) {
        int scaledPadding = Math.max(0, Math.round(basePaddingPx * multiplier));
        return showRuby ? Math.max(scaledPadding, FuriganaText.rubyAscentReservationPx(baseTextPx))
                : scaledPadding;
    }

    public interface RowHeightListener {
        void onRowHeightChanged();
    }

    public static final class Options {
        public float lineSpacingMultiplier = 1f;
        public boolean showRomanization;
        public boolean showTranslation;
        public boolean showJapaneseFurigana;
        public boolean showJapaneseRomaji;
        public boolean attachTransliterationToWords;
        public boolean lineLevelFillTopDown;
        public boolean lineLevelFillSentence;
        public boolean wordLevelFill;
        public boolean interludeNoteIcon;
        public String lyricWeight = "Medium";
        public String lyricsFont = "spotify";
        public float textSizeMultiplier = 1f;
        public boolean translationBright;
        public boolean wrapLongLines = true;
        public boolean adaptiveSectioningEnabled = true;
        public boolean horizontalSafetyPadding = true;
        public String documentText = "";
    }
}
