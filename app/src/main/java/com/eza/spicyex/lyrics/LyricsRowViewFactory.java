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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import com.eza.spicyex.lyrics.reading.ReadingModels.TimedReadingUnit;
import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingUnit;
import com.eza.spicyex.lyrics.reading.CodePointRanges;
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
        return build(line, options, null, heightListener);
    }

    public LinearLayout build(AppliedLine line, Options options,
                              RomanizedWordProvider romanizedWordProvider,
                              RowHeightListener heightListener) {
        // Non-overlapping rendering and the cached blur layer: see BlurredRowLayout.
        LinearLayout row = new BlurredRowLayout(activity);
        boolean rtlLine = isRtlLine(line);
        applyLineDirection(row, rtlLine);
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
        if (options != null && options.horizontalOffsetPx > 0) {
            leadingPadding += options.horizontalOffsetPx;
            trailingPadding += options.horizontalOffsetPx;
            if (row instanceof BlurredRowLayout) {
                ((BlurredRowLayout) row).horizontalOffsetPx = options.horizontalOffsetPx;
            }
        }
        row.setPaddingRelative(leadingPadding, topClearancePx(dp(10), multiplier, 0f, false),
                trailingPadding, Math.round(dp(13) * multiplier));
        row.setClickable(false);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setClipToOutline(false);

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
        boolean chineseLine = "zh".equals(ReadingLanguagePolicy.layoutLanguage(line));
        String readingText = displayReading(line);
        boolean showJapaneseFurigana = japaneseLine && options.showRomanization && options.showJapaneseFurigana;
        boolean showJapaneseRomaji = japaneseLine && options.showRomanization && options.showJapaneseRomaji
                && !isBlank(readingText);
        boolean showChineseRomaji = chineseLine && options.showRomanization && !isBlank(readingText);
        boolean showGenericRomaji = !japaneseLine && !chineseLine && options.showRomanization
                && !isBlank(readingText);

        float sizeMultiplier = options == null ? 1f : options.textSizeMultiplier;
        boolean adaptiveTextSize = options == null || options.adaptiveTextSizeEnabled;
        boolean appleCompactText = options != null && options.appleCompactText;
        if (appleCompactText && line.bgLine) sizeMultiplier *= 0.82f;
        int textCurve = appleCompactText
                ? LyricVisuals.appleLyricTextSizeSp(line.text)
                : LyricVisuals.lyricTextSizeSp(line.text, adaptiveTextSize);
        LyricsLineViewState.setBaseTextSp(line, Math.max(1, Math.round(textCurve * sizeMultiplier)));
        float baseTextPx = sp(LyricsLineViewState.baseTextSp(line));
        // Scaled with the actual text size rather than a fixed dp value, so shrinking the font
        // (LYRICS_TEXT_SIZE/custom) proportionally tightens the gap between lines instead of
        // leaving disproportionately large whitespace around now-smaller text - floored so a very
        // small custom size never crushes lines together illegibly.
        int adaptiveTopPadding = Math.max(dp(6), Math.round(baseTextPx * 0.36f));
        int adaptiveBottomPadding = Math.max(dp(8), Math.round(baseTextPx * 0.46f));
        row.setPaddingRelative(leadingPadding,
                topClearancePx(adaptiveTopPadding, multiplier, baseTextPx, showJapaneseFurigana),
                trailingPadding, Math.round(adaptiveBottomPadding * multiplier));
        String weight = options == null ? "Medium" : options.lyricWeight;
        String font = options == null ? "spotify" : options.lyricsFont;
        LyricsLineViewState.clearMainView(line);
        boolean hasSyllableWords = line.words != null && !line.words.isEmpty();
        boolean hasRealTimedWords = hasSyllableWords && !line.syntheticWords;
        boolean indicLine = SpicyTextDetection.hasIndicScript(line.text);
        Map<String, TimedReadingUnit> timedReadingBySpanId = timedBySpanId(line);
        List<String> timedReadingTexts = hasSyllableWords
                ? romanizedWordTexts(line, options, romanizedWordProvider, timedReadingBySpanId)
                : Collections.emptyList();
        boolean exactTimedReading = TimedTextRowProjection.exactlyReconstructs(
                timedReadingTexts, readingText);
        boolean showAlignedRomaji = !indicLine
                && hasSyllableWords
                && !showJapaneseFurigana
                && options.attachTransliterationToWords
                && exactTimedReading
                && (showJapaneseRomaji || showChineseRomaji || showGenericRomaji);
        // Ruby groups remain visual-only; timed provider children keep their existing word path.
        // A ruby run can span two timed words. Per-word slicing rejects that run on both sides,
        // silently producing no furigana, so keep such lines in one TextView.
        boolean rubyRequiresLineLayout = showJapaneseFurigana
                && FuriganaText.hasRubyCrossingWordBoundaries(line);
        boolean useSyllableWords = !indicLine && !rubyRequiresLineLayout
                && (hasRealTimedWords || (hasSyllableWords
                && (options.wordLevelFill || options.lineLevelFillSentence || showJapaneseFurigana || showAlignedRomaji)));
        // Gradient direction is a document-level choice. Do not switch one row to horizontal
        // merely because that row has timed syllables; mixed rows otherwise render with different
        // fill geometry in the same song.
        boolean lineLevelFillTopDown = options.lineLevelFillTopDown;
        if (useSyllableWords) {
            buildSyllableWords(row, line, options, romanizedWordProvider,
                    showJapaneseFurigana, showAlignedRomaji);
        } else {
            buildLineLevelMain(row, line, showJapaneseFurigana, lineLevelFillTopDown,
                    options.lineLevelFillSentence, weight, font, wrapLongLines,
                    options.adaptiveSectioningEnabled);
        }

        boolean showTimedRomanRow = !line.bgLine
                && !showAlignedRomaji
                && (showJapaneseRomaji || showChineseRomaji || showGenericRomaji)
                && exactTimedReading
                && canBuildTimedRomanRow(line, useSyllableWords, romanizedWordProvider != null);
        if (showTimedRomanRow) {
            buildTimedRomanRow(row, line, options, romanizedWordProvider, wrapLongLines);
        } else if (!line.bgLine && !showAlignedRomaji
                && (showJapaneseRomaji || showChineseRomaji || showGenericRomaji)) {
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

        alignSecondaryInk(row, line);
        attachHeightListener(row, line, heightListener);
        LyricsLineViewState.setRowView(line, row);
        return row;
    }

    /**
     * Lines up the translation/reading text with where the lyric's first glyph is actually
     * inked. Both views start at the same edge, but a glyph's left side bearing grows with its
     * size, so the large lyric's ink began visibly further in than the small translation under
     * it - it read as the translation being outdented. Pads the secondary views by the
     * difference (start side; end-aligned rows align at the other edge and are left alone).
     */
    private static void alignSecondaryInk(LinearLayout row, AppliedLine line) {
        if (line == null || line.oppositeAligned) return;
        List<View> secondary = LyricsLineViewState.secondaryViews(line);
        if (secondary.isEmpty()) return;
        TextView main = firstInkedText(row, secondary);
        int mainInk = leadingInkPx(main);
        if (mainInk <= 0) return;
        for (View view : secondary) {
            TextView first = view instanceof TextView ? (TextView) view
                    : firstInkedText(view, java.util.Collections.emptyList());
            int pad = Math.max(0, mainInk - leadingInkPx(first));
            if (pad > 0) {
                view.setPaddingRelative(view.getPaddingStart() + pad, view.getPaddingTop(),
                        view.getPaddingEnd(), view.getPaddingBottom());
            }
        }
    }

    /** First TextView with text in depth-first order, skipping the secondary rows. */
    private static TextView firstInkedText(View view, List<View> skip) {
        if (view == null || skip.contains(view)) return null;
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            return text != null && text.toString().trim().length() > 0 ? (TextView) view : null;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = firstInkedText(group.getChildAt(i), skip);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Distance from the view's text start to the first glyph's ink, in px. */
    private static int leadingInkPx(TextView view) {
        if (view == null) return 0;
        String text = view.getText() == null ? "" : view.getText().toString();
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
        if (start >= text.length()) return 0;
        int end = start + Character.charCount(text.codePointAt(start));
        android.graphics.Rect bounds = new android.graphics.Rect();
        view.getPaint().getTextBounds(text, start, end, bounds);
        return Math.max(0, bounds.left);
    }

    static boolean canBuildTimedRomanRow(AppliedLine line, boolean useSyllableWords,
                                         boolean hasRomanizedWordProvider) {
        if (!useSyllableWords || line == null || line.words == null || line.words.isEmpty()) {
            return false;
        }
        if (line.readingRenderPlan != null) {
            if (line.readingRenderPlan.timedReadingUnits != null
                    && !line.readingRenderPlan.timedReadingUnits.isEmpty()) return true;
            return !alignedCyrillicReadingWords(line).isEmpty();
        }
        return hasRomanizedWordProvider || !alignedCyrillicReadingWords(line).isEmpty();
    }

    /** Plan text wins when aligned; AI and other whole-line readings use the legacy line slot. */
    static String displayReading(AppliedLine line) {
        if (line == null) return "";
        String planned = line.readingRenderPlan == null
                ? "" : LyricUtils.safe(line.readingRenderPlan.joinedDisplayText);
        return isBlank(planned) ? LyricUtils.safe(line.romanizedText) : planned;
    }

    private void buildSyllableWords(
            LinearLayout row,
            AppliedLine line,
            Options options,
            RomanizedWordProvider romanizedWordProvider,
            boolean showJapaneseFurigana,
            boolean showAlignedRomaji
    ) {
        boolean wrapLongLines = options == null || options.wrapLongLines;
        ViewGroup words = wrapLongLines ? new GlowFlexbox(activity) : new LinearLayout(activity);
        if (words instanceof GlowFlexbox && showJapaneseFurigana) {
            ((GlowFlexbox) words).setGlowLayerEnabled(false);
        }
        boolean rtlLine = isRtlLine(line);
        applyLineDirection(words, rtlLine);
        if (words instanceof FlexboxLayout) {
            FlexboxLayout flex = (FlexboxLayout) words;
            flex.setFlexDirection(FlexDirection.ROW);
            flex.setFlexWrap(FlexWrap.WRAP);
            flex.setJustifyContent(line.oppositeAligned ? JustifyContent.FLEX_END : JustifyContent.FLEX_START);
            flex.setAlignItems(showJapaneseFurigana ? AlignItems.BASELINE : AlignItems.STRETCH);
        } else if (words instanceof LinearLayout) {
            LinearLayout linear = (LinearLayout) words;
            linear.setOrientation(LinearLayout.HORIZONTAL);
            linear.setGravity(line.oppositeAligned ? Gravity.END : Gravity.START);
        }
        words.setClipToPadding(false);
        words.setClipChildren(false);
        if (showJapaneseFurigana) {
            words.setPadding(0, FuriganaText.rubyGapReservationPx(sp(LyricsLineViewState.baseTextSp(line))), 0, 0);
        }
        int furiganaOffset = 0;
        Map<String, TimedReadingUnit> timedBySpanId = timedBySpanId(line);
        List<String> mainTexts = new ArrayList<>();
        for (SyllableSegment segment : line.words) {
            mainTexts.add(segment == null ? "" : LyricUtils.safe(segment.text));
        }
        List<TimedTextRowProjection.Chunk> mainProjection = TimedTextRowProjection.project(
                mainTexts, line.text);
        List<TimedTextRowProjection.Chunk> romanizedWords = showAlignedRomaji
                ? TimedTextRowProjection.project(
                romanizedWordTexts(line, options, romanizedWordProvider, timedBySpanId),
                displayReading(line)) : java.util.Collections.emptyList();
        // Adaptive sectioning candidate: only the wrapping word-row flexbox participates. The
        // LinearLayout Scroll/Clip path and line-level TextView rows keep their existing behavior.
        boolean adaptiveWordRow = words instanceof GlowFlexbox && wrapLongLines
                && options != null && options.adaptiveSectioningEnabled;
        List<int[]> adaptiveChildRanges = adaptiveWordRow ? new ArrayList<>() : null;
        for (int groupStart = 0; groupStart < line.words.size();) {
            int groupEnd = TimedWordGrouping.groupEnd(line, groupStart);
            SyllableSegment flowing = groupEnd == groupStart ? line.words.get(groupStart) : null;
            TimedTextRowProjection.Chunk flowingRoman = showAlignedRomaji
                    && groupStart < romanizedWords.size() ? romanizedWords.get(groupStart) : null;
            if (flowing != null && wrapLongLines && !showJapaneseFurigana
                    && (flowingRoman == null || isBlank(flowingRoman.text))
                    && CjkLineBreak.flowsByCharacter(flowing.text)) {
                int[] sourceRange = FuriganaText.wordRange(line, flowing, groupStart, furiganaOffset);
                furiganaOffset = Math.max(furiganaOffset, sourceRange[1]);
                boolean certain = adaptiveRangeCertain(line.text, flowing.text, sourceRange);
                TimedTextRowProjection.Chunk mainChunk = groupStart < mainProjection.size()
                        ? mainProjection.get(groupStart) : null;
                addFlowingCjkSegment(words, line, flowing, options,
                        mainChunk != null && mainChunk.spaceAfter,
                        certain ? sourceRange[0] : -1, adaptiveChildRanges);
                groupStart = groupEnd + 1;
                continue;
            }
            TimedWordMotionLayout motionGroup = groupEnd > groupStart
                    ? new TimedWordMotionLayout(activity) : null;
            if (motionGroup != null) {
                applyLineDirection(motionGroup, rtlLine);
            }
            View singleWord = null;
            View spaceReference = null;
            int firstVisible = -1;
            int rangeStart = -1;
            int rangeEnd = -1;
            boolean certainRange = true;
            for (int wordIndex = groupStart; wordIndex <= groupEnd; wordIndex++) {
                SyllableSegment seg = line.words.get(wordIndex);
                if (seg == null || isBlank(seg.text)) continue;
                if (firstVisible < 0) firstVisible = wordIndex;
                int[] sourceRange = FuriganaText.wordRange(line, seg, wordIndex, furiganaOffset);
                int wordStart = sourceRange[0];
                furiganaOffset = Math.max(furiganaOffset, sourceRange[1]);
                if (!adaptiveRangeCertain(line.text, seg.text, sourceRange)) certainRange = false;
                if (rangeStart < 0) rangeStart = sourceRange[0];
                rangeEnd = sourceRange[1];
                View wordView = buildWordView(line, seg, showJapaneseFurigana, wordStart,
                        options == null ? "Medium" : options.lyricWeight,
                        options == null ? "spotify" : options.lyricsFont,
                        options, wrapLongLines);
                TimedTextRowProjection.Chunk romanChunk = showAlignedRomaji
                        && wordIndex < romanizedWords.size() ? romanizedWords.get(wordIndex) : null;
                String romanizedWordText = romanChunk == null ? "" : romanChunk.text;
                if (showAlignedRomaji && !isBlank(romanizedWordText)) {
                    wordView = stackRomanizedWord(line, seg, wordView, romanizedWordText);
                } else {
                    LyricsSyllableViewState.clearRomanizedTextView(seg);
                }
                LyricsSyllableViewState.setWordView(seg, wordView);
                if (motionGroup != null) {
                    motionGroup.addView(wordView, new ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                } else {
                    singleWord = wordView;
                }
                if (spaceReference == null) spaceReference = wordView;
            }
            View motionView = motionGroup == null ? singleWord : motionGroup;
            if (motionView != null) {
                for (int wordIndex = groupStart; wordIndex <= groupEnd; wordIndex++) {
                    SyllableSegment seg = line.words.get(wordIndex);
                    if (seg != null && !isBlank(seg.text)) {
                        LyricsSyllableViewState.configureWordMotion(
                                seg, motionView, words, wordIndex == firstVisible);
                    }
                }
                ViewGroup.MarginLayoutParams wlp = words instanceof FlexboxLayout
                        ? new FlexboxLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                        : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                TimedTextRowProjection.Chunk mainChunk = groupEnd < mainProjection.size()
                        ? mainProjection.get(groupEnd) : null;
                if (mainChunk != null && mainChunk.spaceAfter) {
                    wlp.setMarginEnd(measuredSpacePx(spaceReference));
                }
                words.addView(motionView, wlp);
                if (adaptiveChildRanges != null) {
                    adaptiveChildRanges.add(certainRange && rangeStart >= 0
                            ? new int[]{rangeStart, rangeEnd} : null);
                }
            }
            groupStart = groupEnd + 1;
        }
        applyAdaptiveWordSectioning(words, line, adaptiveChildRanges);
        row.addView(words, new LinearLayout.LayoutParams(
                wrapLongLines ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * A Han/kana segment in a wrapping row, laid out as running text: each break unit from
     * {@link CjkLineBreak#clusters} is its own item of the row's flexbox, so the segment continues
     * on the current line and wraps part-way through exactly like the rest of the lyric. Every
     * character is a letter view of the one segment (so fill, glow and letter motion are timed as
     * a whole); the units only decide where lines may break. The first unit is the segment's
     * motion view and the rest follow its word-level transform.
     */
    private void addFlowingCjkSegment(ViewGroup words, AppliedLine line, SyllableSegment seg,
                                      Options options, boolean spaceAfter, int sourceStart,
                                      List<int[]> adaptiveChildRanges) {
        String text = LyricUtils.safe(seg.text);
        String weight = options == null ? "Medium" : options.lyricWeight;
        String font = options == null ? "spotify" : options.lyricsFont;
        int total = text.codePointCount(0, text.length());
        LyricsSyllableViewState.clearLetters(seg);
        LyricsSyllableViewState.clearTextView(seg);
        LyricsSyllableViewState.clearRomanizedTextView(seg);
        List<View> units = new ArrayList<>();
        int letterIndex = 0;
        for (int[] cluster : CjkLineBreak.clusters(text)) {
            LinearLayout unit = new LinearLayout(activity);
            unit.setOrientation(LinearLayout.HORIZONTAL);
            unit.setGravity(Gravity.CENTER_VERTICAL);
            unit.setClipChildren(false);
            unit.setClipToPadding(false);
            List<String> letters = LyricVisuals.splitCodePoints(text.substring(cluster[0], cluster[1]));
            addLetterViews(unit, line, seg, letters, letterIndex, total, weight, font);
            letterIndex += letters.size();
            FlexboxLayout.LayoutParams lp = new FlexboxLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            words.addView(unit, lp);
            units.add(unit);
            if (adaptiveChildRanges != null) {
                adaptiveChildRanges.add(sourceStart >= 0
                        ? new int[]{sourceStart + cluster[0], sourceStart + cluster[1]} : null);
            }
        }
        if (units.isEmpty()) return;
        View first = units.get(0);
        LyricsSyllableViewState.setWordView(seg, first);
        LyricsSyllableViewState.configureWordMotion(seg, first, words, true);
        LyricsSyllableViewState.setFollowerMotionViews(seg, units.subList(1, units.size()));
        if (spaceAfter) {
            View last = units.get(units.size() - 1);
            ((ViewGroup.MarginLayoutParams) last.getLayoutParams()).setMarginEnd(measuredSpacePx(last));
        }
    }

    /** Appends one letter view per code point, timed as letters {@code firstIndex..} of
     *  {@code total} across the segment. */
    private void addLetterViews(ViewGroup parent, AppliedLine line, SyllableSegment seg,
                                List<String> letters, int firstIndex, int total,
                                String weight, String font) {
        int color = line.bgLine ? Color.rgb(170, 170, 170) : Color.WHITE;
        float step = 1f / Math.max(1, total);
        for (int i = 0; i < letters.size(); i++) {
            String text = letters.get(i);
            SpicyAnimatedTextView letterView = SpicyAnimatedTextView.unstyled(activity);
            applyTextDirection(letterView, seg.text);
            letterView.setTextSize(LyricsLineViewState.baseTextSp(line));
            letterView.setTextColor(color);
            textFactory.applyLyricTypeface(letterView, text, weight, font);
            letterView.setIncludeFontPadding(true);
            letterView.setMaxLines(1);
            letterView.setGradientPosition(LyricAnimations.GRADIENT_UNSUNG, 0f);
            parent.addView(letterView, parent instanceof LinearLayout
                    ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT)
                    : new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            float relativeStart = (firstIndex + i) * step;
            AnimatedLetterState letter = new AnimatedLetterState();
            letter.start = relativeStart;
            letter.duration = step;
            letter.glowDuration = Math.max(step, 1f - relativeStart);
            letter.view = letterView;
            LyricsSyllableViewState.addLetter(seg, letter);
        }
    }

    /** Arms adaptive wrapBefore planning on the wrapping word-row flexbox when the Adaptive
     * sectioning setting is on. Keep-together constraints come from
     * {@link DisplayLayoutGroup#forLine}; child ranges reuse the FuriganaText.wordRange progression
     * computed during the mount loop, so mapping stays aligned with the existing furigana path. */
    private void applyAdaptiveWordSectioning(ViewGroup words, AppliedLine line,
                                            List<int[]> childRanges) {
        if (!(words instanceof GlowFlexbox)) return;
        GlowFlexbox flex = (GlowFlexbox) words;
        if (childRanges == null || childRanges.isEmpty()) {
            flex.setAdaptiveSectioning(false, null, null);
            return;
        }
        String text = LyricUtils.safe(line == null ? "" : line.text);
        List<DisplayLayoutGroup> groups = line == null || text.isEmpty()
                ? Collections.<DisplayLayoutGroup>emptyList()
                : DisplayLayoutGroup.forLine(adaptiveLayoutLanguage(line), text, line.japaneseReading);
        flex.setAdaptiveSectioning(true,
                adaptiveForbiddenBreaks(groups, childRanges),
                adaptiveKeepTogetherGroups(groups, childRanges));
    }

    /** A mapped range is certain only when the UTF-16 slice really is the word's own text;
     * uncertain mappings become null so the affected boundaries allow breaks instead of merging
     * unrelated text. */
    private static boolean adaptiveRangeCertain(String lineText, String wordText, int[] range) {
        if (range == null || wordText == null || wordText.isEmpty()) return false;
        return range[1] - range[0] == wordText.length()
                && LyricUtils.safe(lineText).regionMatches(range[0], wordText, 0, wordText.length());
    }

    /** Reading metadata disambiguates all-kanji Japanese lines from Chinese text detection. */
    static String adaptiveLayoutLanguage(AppliedLine line) {
        return ReadingLanguagePolicy.layoutLanguage(line);
    }

    /** Break between adaptive children i and i+1 is forbidden when both word ranges sit wholly
     * inside one keepTogether display group. Uncertain mappings (null ranges) allow the break. */
    static boolean[] adaptiveForbiddenBreaks(List<DisplayLayoutGroup> groups,
                                             List<int[]> childRanges) {
        int n = childRanges.size();
        boolean[] forbidden = new boolean[Math.max(0, n - 1)];
        for (DisplayLayoutGroup group : groups) {
            if (group == null || !group.keepTogether) continue;
            for (int i = 0; i + 1 < n; i++) {
                int[] left = childRanges.get(i);
                int[] right = childRanges.get(i + 1);
                if (left == null || right == null) continue;
                if (group.start <= left[0] && left[1] <= group.end
                        && group.start <= right[0] && right[1] <= group.end) {
                    forbidden[i] = true;
                }
            }
        }
        return forbidden;
    }

    /** Inclusive {first, last} child-index spans of keepTogether groups, consumed only by the
     * planner's emergency rule that lets an oversized phrase split rather than overflow. */
    static int[][] adaptiveKeepTogetherGroups(List<DisplayLayoutGroup> groups,
                                              List<int[]> childRanges) {
        ArrayList<int[]> spans = new ArrayList<>();
        for (DisplayLayoutGroup group : groups) {
            if (group == null || !group.keepTogether) continue;
            int first = -1;
            int last = -1;
            for (int i = 0; i < childRanges.size(); i++) {
                int[] range = childRanges.get(i);
                if (range == null) continue;
                if (group.start <= range[0] && range[1] <= group.end) {
                    if (first < 0) first = i;
                    last = i;
                }
            }
            if (first >= 0 && last > first) spans.add(new int[]{first, last});
        }
        return spans.toArray(new int[0][]);
    }

    private void buildTimedRomanRow(LinearLayout row, AppliedLine line, Options options,
                                    RomanizedWordProvider romanizedWordProvider,
                                    boolean wrapLongLines) {
        ViewGroup romanWords = wrapLongLines ? new GlowFlexbox(activity) : new LinearLayout(activity);
        boolean rtlLine = isRtlLine(line);
        applyLineDirection(romanWords, rtlLine);
        if (romanWords instanceof FlexboxLayout) {
            FlexboxLayout flex = (FlexboxLayout) romanWords;
            flex.setFlexDirection(FlexDirection.ROW);
            flex.setFlexWrap(FlexWrap.WRAP);
            flex.setJustifyContent(line.oppositeAligned ? JustifyContent.FLEX_END : JustifyContent.FLEX_START);
            flex.setAlignItems(AlignItems.STRETCH);
        } else {
            LinearLayout linear = (LinearLayout) romanWords;
            linear.setOrientation(LinearLayout.HORIZONTAL);
            linear.setGravity(line.oppositeAligned ? Gravity.END : Gravity.START);
        }
        romanWords.setClipToPadding(false);
        romanWords.setClipChildren(false);

        Map<String, TimedReadingUnit> timedBySpanId = timedBySpanId(line);
        List<TimedTextRowProjection.Chunk> romanizedWords = TimedTextRowProjection.project(
                romanizedWordTexts(line, options, romanizedWordProvider, timedBySpanId),
                displayReading(line));
        List<int[]> adaptiveRomanRanges = romanWords instanceof GlowFlexbox
                && options != null && options.adaptiveSectioningEnabled
                ? new ArrayList<>() : null;
        int sourceCursorUtf16 = 0;
        int wordIndex = 0;
        for (SyllableSegment seg : line.words) {
            if (seg == null) continue;
            TimedTextRowProjection.Chunk projected = wordIndex < romanizedWords.size()
                    ? romanizedWords.get(wordIndex) : null;
            String romanized = projected == null ? "" : projected.text;
            LyricsSyllableViewState.clearRomanizedTextView(seg);
            if (!isBlank(romanized)) {
                SpicyAnimatedTextView romanWord = textFactory.createSecondaryAnimatedText(
                        activity,
                        romanized,
                        LyricVisuals.secondaryTextSizeSp(LyricsLineViewState.baseTextSp(line)),
                        textFactory.resolveTypefaceForText(romanized, false));
                romanWord.setGravity(Gravity.CENTER_VERTICAL);
                romanWord.setMaxLines(1);
                ViewGroup.MarginLayoutParams wordLp = romanWords instanceof FlexboxLayout
                        ? new FlexboxLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                        : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (projected.spaceAfter) {
                    wordLp.setMarginEnd(measuredSpacePx(romanWord));
                }
                romanWords.addView(romanWord, wordLp);
                LyricsSyllableViewState.setRomanizedTextView(seg, romanWord);
                if (adaptiveRomanRanges != null) {
                    int start = seg.canonicalStartCp >= 0
                            ? com.eza.spicyex.lyrics.reading.CodePointRanges
                            .codePointOffsetToUtf16Index(line.text, seg.canonicalStartCp)
                            : sourceCursorUtf16;
                    int end = seg.canonicalEndCp > seg.canonicalStartCp
                            ? com.eza.spicyex.lyrics.reading.CodePointRanges
                            .codePointOffsetToUtf16Index(line.text, seg.canonicalEndCp)
                            : Math.min(line.text == null ? 0 : line.text.length(),
                            start + (seg.text == null ? 0 : seg.text.length()));
                    if (seg.canonicalStartCp < 0 && line.text != null && seg.text != null) {
                        int found = line.text.indexOf(seg.text, sourceCursorUtf16);
                        if (found >= 0) {
                            start = found;
                            end = Math.min(line.text.length(), found + seg.text.length());
                        }
                    }
                    sourceCursorUtf16 = Math.max(sourceCursorUtf16, end);
                    adaptiveRomanRanges.add(new int[]{start, end});
                }
            }
            wordIndex++;
        }

        if (adaptiveRomanRanges != null && !adaptiveRomanRanges.isEmpty()) {
            GlowFlexbox flex = (GlowFlexbox) romanWords;
            String source = LyricUtils.safe(line == null ? "" : line.text);
            List<DisplayLayoutGroup> groups = DisplayLayoutGroup.forLine(
                    adaptiveLayoutLanguage(line), source, line == null ? null : line.japaneseReading);
            flex.setAdaptiveSectioning(true,
                    adaptiveForbiddenBreaks(groups, adaptiveRomanRanges),
                    adaptiveKeepTogetherGroups(groups, adaptiveRomanRanges));
        }

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                wrapLongLines ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(2);
        row.addView(romanWords, rowLp);
        LyricsLineViewState.setTimedRomanRow(line, romanWords);
    }

    static String romanizedWordText(AppliedLine line, SyllableSegment seg, int wordIndex,
                                    Map<String, TimedReadingUnit> timedBySpanId, Options options,
                                    RomanizedWordProvider romanizedWordProvider) {
        TimedTextLookup planned = timedTextForSpan(timedBySpanId, spanId(seg, wordIndex));
        if (planned.found) return planned.text;
        // A line-fallback plan (or an older cached plan) can legitimately have no timingRefs,
        // and provider adapters can rewrite span IDs while preserving canonical ranges. Resolve
        // that case by source range before falling back to the legacy provider callback; otherwise
        // the whole pronunciation either disappears or gets assigned to the first word.
        String ranged = plannedReadingForSegment(line, seg, wordIndex);
        if (!isBlank(ranged)) return ranged;
        if (seg != null && !isBlank(seg.romanizedText)) return seg.romanizedText;
        return romanizedWordProvider == null ? ""
                : LyricUtils.safe(romanizedWordProvider.romanizedText(
                line, seg, options == null ? "" : options.documentText));
    }

    private static String plannedReadingForSegment(AppliedLine line, SyllableSegment segment,
                                                   int fallbackIndex) {
        if (line == null || line.readingRenderPlan == null || segment == null
                || line.readingRenderPlan.readingUnits == null) return "";
        int start = segment.canonicalStartCp;
        int end = segment.canonicalEndCp;
        if (start < 0 || end <= start) {
            int[] range = FuriganaText.wordRange(line, segment, fallbackIndex, 0);
            start = CodePointRanges.utf16IndexToCodePointOffset(line.text, range[0]);
            end = CodePointRanges.utf16IndexToCodePointOffset(line.text, range[1]);
        }
        StringBuilder out = new StringBuilder();
        for (ReadingUnit unit : line.readingRenderPlan.readingUnits) {
            if (unit == null || unit.canonicalRange == null || isBlank(unit.text)) continue;
            int unitStart = unit.canonicalRange.startCp;
            int unitEnd = unit.canonicalRange.endCp;
            boolean overlaps = unitEnd > start && unitStart < end;
            // A line-level fallback belongs to the first source span only; attaching it to every
            // word is the old "all pronunciation in one word" failure in reverse.
            boolean wholeLineFallback = line.words != null && line.words.size() > 1
                    && unitStart == 0 && unitEnd >= CodePointRanges.length(line.text);
            if (!overlaps || wholeLineFallback) continue;
            if (out.length() > 0 && !Character.isWhitespace(out.charAt(out.length() - 1))) out.append(' ');
            out.append(unit.text);
        }
        return out.toString();
    }

    private static List<String> romanizedWordTexts(
            AppliedLine line,
            Options options,
            RomanizedWordProvider romanizedWordProvider,
            Map<String, TimedReadingUnit> timedBySpanId
    ) {
        ArrayList<String> out = new ArrayList<>();
        if (line == null || line.words == null) return out;
        for (int index = 0; index < line.words.size(); index++) {
            out.add(romanizedWordText(line, line.words.get(index), index, timedBySpanId,
                    options, romanizedWordProvider));
        }
        boolean allBlank = true;
        for (String value : out) {
            if (!isBlank(value)) {
                allBlank = false;
                break;
            }
        }
        if (allBlank) {
            List<String> cyrillic = alignedCyrillicReadingWords(line);
            if (!cyrillic.isEmpty()) return cyrillic;
        }
        return out;
    }

    static List<String> alignedCyrillicReadingWords(AppliedLine line) {
        if (line == null || line.words == null || line.words.isEmpty()
                || !SpicyTextDetection.itemCyrillicTest(line.text)) return Collections.emptyList();
        String trimmed = displayReading(line).trim();
        if (trimmed.isEmpty()) return Collections.emptyList();
        String[] pieces = trimmed.split("\\s+");
        if (pieces.length != line.words.size()) return Collections.emptyList();
        ArrayList<String> out = new ArrayList<>(pieces.length);
        Collections.addAll(out, pieces);
        return out;
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
    private static TimedTextLookup timedTextForSpan(
            Map<String, TimedReadingUnit> timedBySpanId, String spanId) {
        if (timedBySpanId == null || spanId == null) return TimedTextLookup.missing();
        TimedReadingUnit direct = timedBySpanId.get(spanId);
        if (direct != null) return TimedTextLookup.found(direct.text);
        if (!spanId.contains("+")) return TimedTextLookup.missing();
        StringBuilder out = new StringBuilder();
        for (String id : spanId.split("\\+")) {
            TimedReadingUnit timed = timedBySpanId.get(id);
            if (timed == null) return TimedTextLookup.missing();
            if (timed.text != null) out.append(timed.text);
        }
        return TimedTextLookup.found(out.toString());
    }

    private static final class TimedTextLookup {
        final boolean found;
        final String text;

        private TimedTextLookup(boolean found, String text) {
            this.found = found;
            this.text = LyricUtils.safe(text);
        }

        static TimedTextLookup found(String text) {
            return new TimedTextLookup(true, text);
        }

        static TimedTextLookup missing() {
            return new TimedTextLookup(false, "");
        }
    }

    private static int measuredSpacePx(View view) {
        TextView text = firstTextView(view);
        return text == null ? 1 : Math.max(1, Math.round(text.getPaint().measureText(" ")));
    }

    private static TextView firstTextView(View view) {
        if (view instanceof TextView) return (TextView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            TextView found = firstTextView(group.getChildAt(index));
            if (found != null) return found;
        }
        return null;
    }

    private View buildWordView(AppliedLine line, SyllableSegment seg, boolean showJapaneseFurigana, int wordStart, String weight, String font,
                               Options options, boolean wrapLongLines) {
        int color = line.bgLine ? Color.rgb(170, 170, 170) : Color.WHITE;
        boolean appleStyle = options != null && options.appleStyle;
        if (!showJapaneseFurigana && LyricVisuals.shouldUseLetterAnimator(seg, appleStyle)) {
            // Single-line on purpose: a segment that must wrap mid-word is Han/kana, and those are
            // laid out as running text by addFlowingCjkSegment instead of a nested wrap box.
            LinearLayout letters = new LinearLayout(activity);
            letters.setOrientation(LinearLayout.HORIZONTAL);
            letters.setGravity(Gravity.CENTER_VERTICAL);
            letters.setClipToPadding(false);
            letters.setClipChildren(false);
            LyricsSyllableViewState.clearLetters(seg);
            List<String> letterTexts = LyricVisuals.splitCodePoints(seg.text);
            addLetterViews(letters, line, seg, letterTexts, 0, letterTexts.size(), weight, font);
            LyricsSyllableViewState.clearTextView(seg);
            return letters;
        }

        SpicyAnimatedTextView word = new SpicyAnimatedTextView(activity);
        CharSequence wordText = showJapaneseFurigana ? FuriganaText.buildWord(line, seg.text, wordStart) : seg.text;
        applyTextDirection(word, seg.text);
        word.setTextSize(LyricsLineViewState.baseTextSp(line));
        word.setTextColor(color);
        textFactory.applyLyricTypeface(word, wordText, weight, font);
        word.setIncludeFontPadding(true);
        if (showJapaneseFurigana) {
            word.setPadding(0, FuriganaText.rubyGapReservationPx(sp(LyricsLineViewState.baseTextSp(line))), 0, 0);
        }
        if (wrapLongLines && showJapaneseFurigana && isCjkWrapText(seg == null ? "" : seg.text)) {
            // Ruby needs one contiguous layout, so a furigana word cannot be split into flowing
            // units; it may wrap inside itself instead. The flexbox measures it against the row's
            // real width, so no width cap is needed here.
            word.setHorizontallyScrolling(false);
            word.setEllipsize(null);
            word.setMaxLines(Integer.MAX_VALUE);
            applyAdaptiveWrapping(word, true, true);
        } else {
            word.setMaxLines(1);
        }
        if (showJapaneseFurigana) {
            word.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        }
        LyricsSyllableViewState.clearLetters(seg);
        LyricsSyllableViewState.setTextView(seg, word);
        return word;
    }

    private static boolean isCjkWrapText(String text) {
        return SpicyTextDetection.hasCjkIdeograph(text)
                || SpicyTextDetection.hasKana(text)
                || SpicyTextDetection.itemKoreanTest(text);
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

        // Extract mini lyric from parentheses
        String[] textParts = showJapaneseFurigana
                ? new String[]{LyricUtils.safe(line.text), ""}
                : extractMainAndMiniText(line.text);
        String mainTextStr = textParts[0];
        String miniTextStr = textParts[1];

        // Create vertical container for main + mini lyrics (mini below main)
        LinearLayout textContainer = new LinearLayout(activity);
        textContainer.setOrientation(LinearLayout.VERTICAL);
        textContainer.setGravity(line.oppositeAligned ? Gravity.END : Gravity.START);

        SpicyAnimatedTextView main = new SpicyAnimatedTextView(activity);
        CharSequence mainText = showJapaneseFurigana ? FuriganaText.build(line) : mainTextStr;
        applyTextDirection(main, mainTextStr);
        main.setTextSize(LyricsLineViewState.baseTextSp(line));
        main.setTextColor(color);
        textFactory.applyLyricTypeface(main, mainText, weight, font);
        main.setSelfGlow(true);
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
        LinearLayout.LayoutParams mainLp = new LinearLayout.LayoutParams(
                wrapLongLines ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        textContainer.addView(main, mainLp);
        LyricsLineViewState.setMainView(line, main);

        // Add mini lyric below main if present
        if (!isBlank(miniTextStr)) {
            SpicyAnimatedTextView mini = new SpicyAnimatedTextView(activity);
            mini.setText(miniTextStr);
            applyTextDirection(mini, miniTextStr);
            float miniTextSize = LyricsLineViewState.baseTextSp(line) * 0.65f; // 65% of main size
            mini.setTextSize(miniTextSize);
            mini.setTextColor(Color.argb(180, 255, 255, 255)); // Slightly transparent white
            textFactory.applyLyricTypeface(mini, miniTextStr, weight, font);
            mini.setSelfGlow(true);
            mini.setIncludeFontPadding(true);
            mini.setGravity(line.oppositeAligned ? Gravity.END : Gravity.START);
            // Background lines projected into the compact card are newline-separated mini rows.
            // Keep them vertical instead of allowing the TextView to collapse the projection
            // into one horizontal line.
            mini.setMaxLines(Math.max(1, Math.min(3, miniTextStr.split("\\n", -1).length)));
            mini.setVerticalGradient(lineLevelFillTopDown);

            // Add mini lyric below main with small top margin
            LinearLayout.LayoutParams miniLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            miniLp.topMargin = dp(2);
            textContainer.addView(mini, miniLp);

            // Store for animation
            LyricsLineViewState.setMiniView(line, mini);
        }

        row.addView(textContainer, new LinearLayout.LayoutParams(
                wrapLongLines ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private String[] extractMainAndMiniText(String text) {
        if (isBlank(text)) {
            return new String[]{"", ""};
        }

        int newline = text.indexOf('\n');
        if (newline >= 0) {
            return new String[]{text.substring(0, newline).trim(),
                    text.substring(newline + 1).trim()};
        }

        // Parentheses and quotation marks are lyric content, not mini-row delimiters. Only the
        // explicit projection newline creates a compact-card mini row.
        return new String[]{text, ""};
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
        // Prevent hyphenation which can break CJK text oddly
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
        // Prevent punctuation (quotes, brackets, etc.) from starting a new line
        // by treating them as part of the preceding word/phrase
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

    public interface RomanizedWordProvider {
        String romanizedText(AppliedLine line, SyllableSegment segment, String fullText);
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
        return LyricsDisplayMode.isJapaneseLine(line);
    }

    private boolean isCjkPhraseLine(AppliedLine line) {
        String language = ReadingLanguagePolicy.layoutLanguage(line);
        return "ja".equals(language) || "zh".equals(language) || "ko".equals(language);
    }

    private boolean hasJapaneseReading(AppliedLine line) {
        return line != null && line.japaneseReading != null && line.japaneseReading.furigana != null && !line.japaneseReading.furigana.isEmpty();
    }

    private int dp(int value) {
        float density = activity == null ? 1f : activity.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    /**
     * Width basis for the Apple long-CJK-word check: screen width minus chrome margin. Slightly
     * conservative on purpose — wrapping a word a touch early is the safe direction.
     */
    private float sp(float value) {
        float scaledDensity = activity == null ? 1f : activity.getResources().getDisplayMetrics().scaledDensity;
        return value * scaledDensity;
    }

    static int topClearancePx(int basePaddingPx, float multiplier, float baseTextPx, boolean showRuby) {
        int scaledPadding = Math.max(0, Math.round(basePaddingPx * multiplier));
        return showRuby ? Math.max(scaledPadding, FuriganaText.rubyAscentReservationPx(baseTextPx))
                : scaledPadding;
    }

    private static void applyLineDirection(View view, boolean rtl) {
        view.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        view.setTextDirection(rtl ? View.TEXT_DIRECTION_FIRST_STRONG_RTL : View.TEXT_DIRECTION_FIRST_STRONG_LTR);
    }

    static boolean isRtlLine(AppliedLine line) {
        if (line == null) return false;
        if (SpicyTextDetection.hasStrongDirection(line.text)) return SpicyTextDetection.isRtl(line.text);
        for (SyllableSegment word : line.words) {
            if (word != null && SpicyTextDetection.isRtl(word.text)) return true;
        }
        return false;
    }

    private static void applyTextDirection(TextView view, String text) {
        view.setTextDirection(SpicyTextDetection.isRtl(text)
                ? View.TEXT_DIRECTION_FIRST_STRONG_RTL
                : View.TEXT_DIRECTION_FIRST_STRONG_LTR);
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
        public boolean adaptiveTextSizeEnabled = true;
        public boolean translationBright;
        public boolean wrapLongLines = true;
        public boolean adaptiveSectioningEnabled = true;
        public boolean horizontalSafetyPadding = true;
        public String documentText = "";
        public boolean appleStyle;
        public boolean appleCompactText;
        /** Explicit horizontal offset for the lyric text, moved from the scroll container 
         *  (see LyricsScrollController) so the row view can remain full-screen width for 
         *  unclipped blur/glow effects while the text keeps its margin. */
        public int horizontalOffsetPx;
    }
}
