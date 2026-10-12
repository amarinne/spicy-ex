package com.eza.spicyex.lyrics;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.eza.spicyex.lyrics.language.SpicyJapaneseChineseProcessor.FuriganaSegment;
import com.eza.spicyex.lyrics.language.SpicyJapaneseChineseProcessor.JapaneseReading;

/** Native regression checks for the shared fullscreen and Android Auto word renderer. */
public final class TimedWordSpacingInstrumentation extends Instrumentation {
    private int caseIndex;
    private boolean sentenceSuite;
    private boolean autoSuite;
    private boolean chineseSuite;
    private boolean paidLinkageSuite;
    private boolean layoutProbeSuite;
    private boolean aiStatusSuite;
    private boolean aiRecoverySuite;
    private boolean receiptSuite;
    private boolean unknownSuite;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        sentenceSuite = arguments != null && "sentence".equals(arguments.getString("suite"));
        autoSuite = arguments != null && "auto".equals(arguments.getString("suite"));
        chineseSuite = arguments != null && "chinese".equals(arguments.getString("suite"));
        paidLinkageSuite = arguments != null && "paid-linkage".equals(arguments.getString("suite"));
        layoutProbeSuite = arguments != null && "layout-probe".equals(arguments.getString("suite"));
        aiStatusSuite = arguments != null && "ai-status".equals(arguments.getString("suite"));
        aiRecoverySuite = arguments != null && "ai-recovery".equals(arguments.getString("suite"));
        receiptSuite = arguments != null && "diagnostic-receipt".equals(arguments.getString("suite"));
        String suite = arguments == null ? null : arguments.getString("suite");
        unknownSuite = suite != null && !suite.isEmpty() && !sentenceSuite && !autoSuite && !chineseSuite && !paidLinkageSuite && !layoutProbeSuite && !aiStatusSuite && !aiRecoverySuite && !receiptSuite
                && !"spacing".equals(suite);
        start();
    }

    @Override public void onStart() {
        if (receiptSuite) {
            com.eza.spicyex.diagnostics.DiagnosticReceiptInstrumentation.run(this);
            return;
        }
        if (aiRecoverySuite) {
            com.eza.spicyex.lyrics.ai.AiRecoveryInstrumentation.run(this);
            return;
        }
        if (aiStatusSuite) {
            AiFailureIndicatorInstrumentation.run(this);
            return;
        }
        if (layoutProbeSuite) {
            com.eza.spicyex.hooks.LyricRowProbeInstrumentation.run(this);
            return;
        }
        if (paidLinkageSuite) {
            com.eza.spicyex.lyrics.session.AiLinkageMigrationInstrumentation.run(this);
            return;
        }
        if (unknownSuite) {
            Bundle result = new Bundle();
            result.putString("stream", "FAIL: unknown behavior suite\n");
            finish(0, result);
            return;
        }
        if (sentenceSuite) {
            SentenceGradientInstrumentation.run(this);
            return;
        }
        if (autoSuite) {
            com.eza.spicyex.auto.AndroidAutoRenderingInstrumentation.run(this);
            return;
        }
        if (chineseSuite) {
            ChineseCharacterFormInstrumentation.run(this);
            return;
        }
        Bundle result = new Bundle();
        List<String> failures = new ArrayList<>();
        runOnMainSync(() -> {
            for (boolean wrap : new boolean[]{false, true}) {
                check(failures, "word wrap=" + wrap, "Hello world!",
                        new String[]{"Hello", "world!"}, new String[]{"Hello ", "world!"}, 300, wrap);
                check(failures, "letters wrap=" + wrap, "Hello world!",
                        new String[]{"Hello", "world!"}, new String[]{"Hello ", "world!"}, 1500, wrap);
                check(failures, "CJK wrap=" + wrap, "今夜 星空",
                        new String[]{"今夜", "星空"}, new String[]{"今夜 ", "星空"}, 1500, wrap);
                check(failures, "leading wrap=" + wrap, "Hello world!",
                        new String[]{"Hello", "world!"}, new String[]{"Hello", " world!"}, 300, wrap);
                check(failures, "split wrap=" + wrap, "Hello world!",
                        new String[]{"Hel", "lo", "world!"}, new String[]{"Hel", "lo ", "world!"}, 300, wrap);
                check(failures, "ruby wrap=" + wrap, "今夜 星空",
                        new String[]{"今夜", "星空"}, new String[]{"今夜", " 星空"}, 300, wrap);
            }
        });
        result.putString("stream", failures.isEmpty() ? "PASS: 12 native spacing cases\n"
                : "FAIL: " + String.join("\n", failures) + "\n");
        result.putInt("expectedCases", 12); result.putInt("completedCases", 12 - failures.size());
        finish(failures.isEmpty() ? -1 : 0, result);
    }

    private void check(List<String> failures, String name, String text,
                       String[] plain, String[] spaced, long duration, boolean wrap) {
        Bundle status = new Bundle();
        status.putString("class", getClass().getName());
        status.putString("test", name);
        status.putInt("numtests", 12);
        status.putInt("current", ++caseIndex);
        sendStatus(1, status);
        try {
            AppliedLine expected = line(text, plain, duration);
            AppliedLine actual = line(text, spaced, duration);
            if (name.startsWith("ruby")) {
                expected.japaneseReading = actual.japaneseReading = new JapaneseReading(text, "konya hoshizora",
                        Arrays.asList(new FuriganaSegment(0, 2, "こんや"), new FuriganaSegment(3, 5, "ほしぞら")));
            }
            View reference = build(expected, wrap);
            View rendered = build(actual, wrap);
            String referenceShape = shape(reference);
            String renderedShape = shape(rendered);
            if (!referenceShape.equals(renderedShape)) {
                throw new AssertionError("different text or geometry: " + referenceShape + " / " + renderedShape);
            }
            Bitmap referencePixels = pixels(reference);
            Bitmap renderedPixels = pixels(rendered);
            try {
                if (!referencePixels.sameAs(renderedPixels)) throw new AssertionError("different drawn pixels");
            } finally {
                referencePixels.recycle();
                renderedPixels.recycle();
            }
            for (int i = 0; i < actual.words.size(); i++) {
                SyllableSegment word = actual.words.get(i);
                if (!spaced[i].equals(word.text) || word.startMs != i * 2000L
                        || word.endMs != i * 2000L + duration) {
                    throw new AssertionError("source token or timing changed");
                }
            }
            sendStatus(0, status);
        } catch (Throwable error) {
            failures.add(name + ": " + error);
            status.putString("stack", error.toString());
            sendStatus(-2, status);
        }
    }

    private View build(AppliedLine line, boolean wrap) {
        LyricsRowViewFactory.Options options = new LyricsRowViewFactory.Options();
        options.wrapLongLines = wrap;
        options.adaptiveSectioningEnabled = false;
        options.lyricsFont = "system";
        options.lyricWeight = "Regular";
        options.showRomanization = line.japaneseReading != null;
        options.showJapaneseFurigana = line.japaneseReading != null;
        View view = new LyricsRowViewFactory(getTargetContext(),
                new LyricsTextFactory(getTargetContext(), null)).build(line, options, null);
        view.measure(View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        return view;
    }

    private static Bitmap pixels(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static AppliedLine line(String text, String[] tokens, long duration) {
        AppliedLine line = new AppliedLine();
        line.text = text;
        for (int i = 0; i < tokens.length; i++) {
            SyllableSegment word = new SyllableSegment();
            word.text = tokens[i];
            word.startMs = i * 2000L;
            word.endMs = word.startMs + duration;
            word.totalMs = duration;
            word.boundaryAfter = i == tokens.length - 1 || !tokens[i].equals("Hel");
            word.partOfWord = !word.boundaryAfter;
            line.words.add(word);
        }
        return line;
    }

    private static String shape(View view) {
        StringBuilder out = new StringBuilder(view.getClass().getSimpleName());
        out.append(':').append(view.getMeasuredWidth()).append('x').append(view.getMeasuredHeight());
        if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            out.append(':').append(((ViewGroup.MarginLayoutParams) view.getLayoutParams()).getMarginEnd());
        }
        if (view instanceof TextView) out.append(':').append(((TextView) view).getText());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) out.append('[').append(shape(group.getChildAt(i))).append(']');
        }
        return out.toString();
    }
}
