package com.eza.spicyex.lyrics;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Native acceptance checks for wrapped main lyrics and whole-line readings. */
public final class SentenceGradientInstrumentation {
    private final Instrumentation host;
    private SentenceGradientInstrumentation(Instrumentation host) {
        this.host = host;
    }

    public static void run(Instrumentation host) {
        new SentenceGradientInstrumentation(host).run();
    }

    private void run() {
        List<String> failures = new ArrayList<>();
        host.runOnMainSync(() -> {
            check(failures, "nested timing group", this::nestedTimingGroup);
            check(failures, "standalone reading", this::standaloneReading);
            check(failures, "held word emphasis", this::heldWordEmphasis);
        });
        Bundle result = new Bundle();
        result.putInt("expectedCases", 3); result.putInt("completedCases", 3 - failures.size());
        result.putString("stream", failures.isEmpty() ? "PASS: 3 sentence gradient cases\n"
                : "FAIL: " + String.join("\n", failures) + "\n");
        host.finish(failures.isEmpty() ? -1 : 0, result);
    }

    private void nestedTimingGroup() throws Exception {
        AppliedLine line = new AppliedLine();
        line.text = repeated("愛", 48);
        line.startMs = 1000;
        line.endMs = 5000;
        for (int i = 0; i < 48; i++) {
            SyllableSegment word = new SyllableSegment();
            word.text = "愛";
            word.startMs = 1000 + i * 80;
            word.endMs = word.startMs + 80;
            word.providerPartOfWord = i != 31 && i != 47;
            line.words.add(word);
        }
        View row = build(line, options());
        GlowFlexbox flex = findFlex(row);
        if (flex == null) throw new AssertionError("missing word flexbox");
        List<SpicyAnimatedTextView> texts = new ArrayList<>();
        collectText(flex, texts);
        float firstTop = topIn(texts.get(0), flex);
        SpicyAnimatedTextView secondRow = null;
        int rows = 1;
        float previousTop = firstTop;
        for (SpicyAnimatedTextView text : texts) {
            float top = topIn(text, flex);
            if (top > previousTop + text.getHeight() / 2f) {
                rows++;
                if (secondRow == null) secondRow = text;
                previousTop = top;
            }
        }
        if (rows < 3 || secondRow == null) throw new AssertionError("fixture did not wrap to 3 rows");
        LyricsLineViewState.applySentenceGradient(line, -26f, 0f, 1f);
        if (number(texts.get(0), "gradientPosition") <= -40f) {
            throw new AssertionError("first visual row did not advance");
        }
        if (number(secondRow, "gradientPosition") > -39.5f) {
            throw new AssertionError("second visual row advanced with the first");
        }
    }

    private void standaloneReading() throws Exception {
        AppliedLine line = new AppliedLine();
        line.text = "中文歌詞";
        line.romanizedText = repeated("zhong wen ge ci ", 12).trim();
        LyricsRowViewFactory.Options options = options();
        options.showRomanization = true;
        build(line, options);
        List<View> secondary = LyricsLineViewState.secondaryViews(line);
        if (secondary.size() != 1 || !(secondary.get(0) instanceof SpicyAnimatedTextView)) {
            throw new AssertionError("missing standalone reading");
        }
        SpicyAnimatedTextView roman = (SpicyAnimatedTextView) secondary.get(0);
        if (roman.getLayout().getLineCount() < 2) throw new AssertionError("reading did not wrap");
        LyricsLineViewState.applySentenceGradient(line, -26f, 0f, 1f);
        if (!flag(roman, "sequentialLineFill") || !flag(roman, "softSweep")) {
            throw new AssertionError("standalone reading did not use sentence fill");
        }
    }

    /** Compare production drawing with independent canvas transforms at rise, hold, switch, and fall. */
    private void heldWordEmphasis() {
        GlowFlexbox flex = new GlowFlexbox(host.getTargetContext());
        flex.setClipChildren(false);
        for (int color : new int[]{0xffff0000, 0xff00ff00, 0xff0000ff}) {
            View word = new View(host.getTargetContext());
            word.setBackgroundColor(color);
            flex.addView(word, new com.google.android.flexbox.FlexboxLayout.LayoutParams(60, 40));
        }
        layoutEmphasis(flex, 240);
        assertEmphasisPixels(flex, new float[]{1f, 1f, 1f});
        flex.stepWordEmphasis(flex.getChildAt(1), 1f, 0.05f);
        float rising = 1f + 0.07f * (1f - (float) Math.exp(-0.05f / 0.12f));
        assertEmphasisPixels(flex, new float[]{1f, rising, 1f});
        flex.stepWordEmphasis(flex.getChildAt(1), 1f, 10f);
        assertEmphasisPixels(flex, new float[]{1f, 1.07f, 1f});
        flex.stepWordEmphasis(flex.getChildAt(0), 0.5f, 10f);
        assertEmphasisPixels(flex, new float[]{1.035f, 1f, 1f});
        layoutEmphasis(flex, 260);
        assertEmphasisPixels(flex, new float[]{1.035f, 1f, 1f});
        flex.stepWordEmphasis(null, 0f, 10f);
        assertEmphasisPixels(flex, new float[]{1f, 1f, 1f});
    }

    private static void layoutEmphasis(GlowFlexbox flex, int width) {
        flex.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
        flex.layout(0, 0, width, 100);
    }

    private static void assertEmphasisPixels(GlowFlexbox flex, float[] scales) {
        Bitmap actual = Bitmap.createBitmap(flex.getWidth(), flex.getHeight(), Bitmap.Config.ARGB_8888);
        Bitmap expected = Bitmap.createBitmap(flex.getWidth(), flex.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            flex.draw(new Canvas(actual));
            Canvas canvas = new Canvas(expected);
            for (int i = 0; i < flex.getChildCount(); i++) {
                View child = flex.getChildAt(i);
                if (child.getScaleX() != 1f || child.getScaleY() != 1f || child.getTranslationX() != 0f) {
                    throw new AssertionError("word emphasis changed child layout properties");
                }
                int save = canvas.save();
                canvas.scale(scales[i], scales[i], (child.getLeft() + child.getRight()) / 2f,
                        child.getBottom());
                canvas.translate(child.getLeft(), child.getTop());
                child.draw(canvas);
                canvas.restoreToCount(save);
            }
            if (!expected.sameAs(actual)) throw new AssertionError("word emphasis geometry or pixels changed");
        } finally {
            actual.recycle();
            expected.recycle();
        }
    }

    private LyricsRowViewFactory.Options options() {
        LyricsRowViewFactory.Options options = new LyricsRowViewFactory.Options();
        options.lyricsFont = "system";
        options.adaptiveSectioningEnabled = false;
        options.lineLevelFillSentence = true;
        options.sequentialLineFill = true;
        options.continuousSentenceFill = true;
        return options;
    }

    private View build(AppliedLine line, LyricsRowViewFactory.Options options) {
        View row = new LyricsRowViewFactory(host.getTargetContext(),
                new LyricsTextFactory(host.getTargetContext(), null)).build(line, options, null);
        row.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
        return row;
    }

    private static GlowFlexbox findFlex(View view) {
        if (view instanceof GlowFlexbox) return (GlowFlexbox) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                GlowFlexbox found = findFlex(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void collectText(View view, List<SpicyAnimatedTextView> out) {
        if (view instanceof SpicyAnimatedTextView) out.add((SpicyAnimatedTextView) view);
        else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectText(group.getChildAt(i), out);
        }
    }

    private static float topIn(View view, View ancestor) {
        float top = 0f;
        while (view != ancestor) {
            top += view.getTop();
            view = (View) view.getParent();
        }
        return top;
    }

    private static Object field(Object value, String name) throws Exception {
        Field field = SpicyAnimatedTextView.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }
    private static String repeated(String value, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
    private static float number(Object value, String name) throws Exception {
        return ((Number) field(value, name)).floatValue();
    }
    private static boolean flag(Object value, String name) throws Exception {
        return (Boolean) field(value, name);
    }
    private static void check(List<String> failures, String name, Check check) {
        try { check.run(); }
        catch (Throwable error) { failures.add(name + ": " + error); }
    }
    private interface Check { void run() throws Exception; }
}
