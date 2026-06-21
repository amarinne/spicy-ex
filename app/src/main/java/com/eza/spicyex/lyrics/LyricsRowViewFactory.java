package com.eza.spicyex.lyrics;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.google.android.flexbox.AlignItems;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexWrap;
import com.google.android.flexbox.FlexboxLayout;
import com.google.android.flexbox.JustifyContent;

import java.util.List;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;

/** Builds mounted Android views for applied lyric rows. */
public final class LyricsRowViewFactory {
    private final Activity activity;
    private final LyricsTextFactory textFactory;

    public LyricsRowViewFactory(Activity activity, LyricsTextFactory textFactory) {
        this.activity = activity;
        this.textFactory = textFactory;
    }

    public LinearLayout build(AppliedLine line, Options options, RomanizedWordProvider romanizedWordProvider, RowHeightListener heightListener) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(line.oppositeAligned ? Gravity.END : Gravity.START);
        float multiplier = options == null ? 1f : options.lineSpacingMultiplier;
        row.setPadding(dp(6), (int) (dp(10) * multiplier), dp(6), (int) (dp(13) * multiplier));
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
            row.addView(dots, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            attachHeightListener(row, line, heightListener);
            LyricsLineViewState.setRowView(line, row);
            return row;
        }

        boolean japaneseLine = isJapaneseLine(line);
        boolean chineseLine = !japaneseLine && SpicyTextDetection.itemChineseTest(line.text);
        boolean showJapaneseFurigana = japaneseLine && options.showRomanization && options.showJapaneseFurigana;
        boolean showJapaneseRomaji = japaneseLine && options.showRomanization && options.showJapaneseRomaji && !isBlank(line.romanizedText);
        boolean showChineseRomaji = chineseLine && options.showRomanization && !isBlank(line.romanizedText);
        boolean showGenericRomaji = !japaneseLine && !chineseLine && options.showRomanization && !isBlank(line.romanizedText);

        float sizeMultiplier = options == null ? 1f : options.textSizeMultiplier;
        LyricsLineViewState.setBaseTextSp(line, Math.max(1, Math.round(LyricVisuals.lyricTextSizeSp(line.text) * sizeMultiplier)));
        String weight = options == null ? "Medium" : options.lyricWeight;
        String font = options == null ? "default" : options.lyricsFont;
        LyricsLineViewState.clearMainView(line);
        boolean hasSyllableWords = line.words != null && !line.words.isEmpty();
        boolean showAlignedRomaji = hasSyllableWords
                && !showJapaneseFurigana
                && options.attachTransliterationToWords
                && (showJapaneseRomaji || showChineseRomaji || showGenericRomaji);
        boolean useSyllableWords = hasSyllableWords
                && (options.wordLevelFill || options.lineLevelFillSentence || showJapaneseFurigana || showAlignedRomaji);
        boolean lineLevelFillTopDown = !useSyllableWords && options.lineLevelFillTopDown;
        if (useSyllableWords) {
            buildSyllableWords(row, line, options, romanizedWordProvider, showJapaneseFurigana, showAlignedRomaji);
        } else {
            buildLineLevelMain(row, line, showJapaneseFurigana, lineLevelFillTopDown, options.lineLevelFillSentence, weight, font);
        }

        if (!line.bgLine && !showAlignedRomaji && (showJapaneseRomaji || showChineseRomaji || showGenericRomaji)) {
            SpicyAnimatedTextView roman = textFactory.createSecondaryAnimatedText(activity, line.romanizedText, LyricVisuals.secondaryTextSizeSp(LyricsLineViewState.baseTextSp(line)), textFactory.resolveTypeface(false));
            roman.setGravity(line.oppositeAligned ? Gravity.END : Gravity.START);
            roman.setMaxLines(3);
            roman.setSelfGlow(true);
            roman.setVerticalGradient(lineLevelFillTopDown);
            roman.setContentGradient(options.lineLevelFillSentence);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(2);
            row.addView(roman, lp);
            LyricsLineViewState.setRomanView(line, roman);
        }

        if (!line.bgLine && options.showTranslation && !isBlank(line.translatedText)) {
            SpicyAnimatedTextView translated = textFactory.createSecondaryAnimatedText(activity, line.translatedText, Math.max(13, LyricVisuals.secondaryTextSizeSp(LyricsLineViewState.baseTextSp(line)) - 1), Typeface.create(textFactory.resolveTypeface(false), Typeface.ITALIC));
            translated.setGravity(line.oppositeAligned ? Gravity.END : Gravity.START);
            translated.setMaxLines(3);
            translated.setAlpha(1f);
            translated.setBrightnessMultiplier(options.translationBright ? 1f : 0.42f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(2);
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
            RomanizedWordProvider romanizedWordProvider,
            boolean showJapaneseFurigana,
            boolean showAlignedRomaji
    ) {
        FlexboxLayout words = new GlowFlexbox(activity); // draws the line-level continuous glow
        words.setFlexDirection(FlexDirection.ROW);
        words.setFlexWrap(FlexWrap.WRAP);
        words.setJustifyContent(line.oppositeAligned ? JustifyContent.FLEX_END : JustifyContent.FLEX_START);
        words.setAlignItems(showJapaneseFurigana ? AlignItems.BASELINE : AlignItems.STRETCH);
        words.setClipToPadding(false);
        words.setClipChildren(false);
        if (showJapaneseFurigana) words.setPadding(0, dp(4), 0, 0);
        int furiganaOffset = 0;
        for (SyllableSegment seg : line.words) {
            if (seg == null || isBlank(seg.text)) continue;
            if (furiganaOffset > 0 && !seg.partOfWord) furiganaOffset++;
            int wordStart = furiganaOffset;
            furiganaOffset += seg.text.length();
            View wordView = buildWordView(line, seg, showJapaneseFurigana, wordStart,
                    options == null ? "Medium" : options.lyricWeight,
                    options == null ? "default" : options.lyricsFont);
            String romanizedWordText = showAlignedRomaji && romanizedWordProvider != null
                    ? romanizedWordProvider.romanizedText(line, seg, options.documentText)
                    : "";
            if (showAlignedRomaji && !isBlank(romanizedWordText)) {
                wordView = stackRomanizedWord(line, seg, wordView, romanizedWordText);
            } else {
                LyricsSyllableViewState.clearRomanizedTextView(seg);
            }
            FlexboxLayout.LayoutParams wlp = new FlexboxLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (!seg.partOfWord) wlp.rightMargin = dp(8);
            words.addView(wordView, wlp);
            LyricsSyllableViewState.setWordView(seg, wordView);
        }
        row.addView(words, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
                letterView.setText(text);
                letterView.setTextSize(LyricsLineViewState.baseTextSp(line));
                letterView.setTextColor(color);
                letterView.setTypeface(textFactory.resolveLyricTypeface(weight, font));
                letterView.setIncludeFontPadding(true);
                letterView.setMaxLines(1);
                letterView.setGradientPosition(-20f, 0f);
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
        word.setText(showJapaneseFurigana ? FuriganaText.buildWord(line, seg.text, wordStart) : seg.text);
        word.setTextSize(LyricsLineViewState.baseTextSp(line));
        word.setTextColor(color);
        word.setTypeface(textFactory.resolveLyricTypeface(weight, font));
        word.setIncludeFontPadding(true);
        if (showJapaneseFurigana) word.setPadding(0, dp(4), 0, 0);
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
        SpicyAnimatedTextView romanWord = textFactory.createSecondaryAnimatedText(activity, romanizedWordText, Math.max(11, LyricVisuals.secondaryTextSizeSp(LyricsLineViewState.baseTextSp(line)) - 2), textFactory.resolveTypeface(false));
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
                                    String weight, String font) {
        int color = line.bgLine ? Color.rgb(170, 170, 170) : Color.WHITE;
        SpicyAnimatedTextView main = new SpicyAnimatedTextView(activity);
        main.setText(showJapaneseFurigana ? FuriganaText.build(line) : line.text);
        main.setTextSize(LyricsLineViewState.baseTextSp(line));
        main.setTextColor(color);
        main.setTypeface(textFactory.resolveLyricTypeface(weight, font));
        main.setSelfGlow(true); // line-level row: no GlowFlexbox parent, draw its own halo
        main.setIncludeFontPadding(true);
        if (showJapaneseFurigana) main.setPadding(0, dp(4), 0, 0);
        main.setGravity(line.oppositeAligned ? Gravity.END : Gravity.START);
        main.setMaxLines(4);
        main.setVerticalGradient(lineLevelFillTopDown);
        main.setContentGradient(lineLevelFillSentence);
        main.setGradientPosition(-20f, 0f);
        row.addView(main, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LyricsLineViewState.setMainView(line, main);
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
        return hasJapaneseReading(line) || (line != null && SpicyJapaneseChineseProcessor.canRomanizeJapanese(line.text));
    }

    private boolean hasJapaneseReading(AppliedLine line) {
        return line != null && line.japaneseReading != null && line.japaneseReading.furigana != null && !line.japaneseReading.furigana.isEmpty();
    }

    private int dp(int value) {
        float density = activity == null ? 1f : activity.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    public interface RomanizedWordProvider {
        String romanizedText(AppliedLine line, SyllableSegment seg, String fullText);
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
        public String lyricsFont = "default";
        public float textSizeMultiplier = 1f;
        public boolean translationBright;
        public String documentText = "";
    }
}
