package com.eza.spicyex.lyrics;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.eza.spicyex.lyrics.language.ChineseCharacterForm;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Exercises dictionary resources and the production row renderer without changing playback. */
final class ChineseCharacterFormInstrumentation {
    private static final String SOURCE = "靜静看着头发与皇后😀";
    private static final String[] EXPECTED = {SOURCE, "静静看着头发与皇后😀", "靜靜看著頭髮與皇后😀", SOURCE};
    private static final String[] BACKGROUND = {"请別离开", "请别离开", "請別離開", "请別离开"};
    private static final ChineseCharacterForm[] FORMS = {ChineseCharacterForm.ORIGINAL,
            ChineseCharacterForm.SIMPLIFIED, ChineseCharacterForm.TRADITIONAL, ChineseCharacterForm.ORIGINAL};

    static void run(Instrumentation runner) {
        List<String> failures = new ArrayList<>();
        int[] completed = {0};
        runner.runOnMainSync(() -> {
            for (boolean timed : new boolean[]{false, true}) {
                LyricsDocument doc = document(timed);
                for (int i = 0; i < FORMS.length; i++) {
                    String name = FORMS[i] + (timed ? "-word" : "-sentence") + "-" + i;
                    Bundle status = new Bundle();
                    status.putString("class", ChineseCharacterFormInstrumentation.class.getName());
                    status.putString("test", name); status.putInt("numtests", 8);
                    status.putInt("current", ++completed[0]);
                    runner.sendStatus(1, status);
                    try {
                        doc.chineseCharacterForm = FORMS[i];
                        LyricTimeline.applySyncedRows(doc);
                        if (!SOURCE.equals(doc.lines.get(0).text)
                                || !"请別离开".equals(doc.lines.get(0).backgroundLines.get(0).text))
                            throw new AssertionError("canonical text changed");
                        AppliedLine actual = doc.appliedLines.get(0);
                        AppliedLine bg = doc.appliedLines.get(1);
                        assertRow(actual, doc.lines.get(0).syllables, EXPECTED[i]);
                        assertRow(bg, doc.lines.get(0).backgroundLines.get(0).syllables, BACKGROUND[i]);
                        View rendered = build(runner, actual);
                        View reference = build(runner, expected(actual, EXPECTED[i]));
                        if (!shape(reference).equals(shape(rendered))) throw new AssertionError("text or geometry mismatch");
                        Bitmap pixels = pixels(rendered), referencePixels = pixels(reference);
                        try {
                            if (!pixels.sameAs(referencePixels)) throw new AssertionError("glyph pixels mismatch");
                            File directory = new File(runner.getTargetContext().getCacheDir(), "behavior/chinese");
                            if (!directory.isDirectory() && !directory.mkdirs()) throw new AssertionError("missing evidence directory");
                            try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
                                pixels.compress(Bitmap.CompressFormat.PNG, 100, output);
                            }
                        } finally { pixels.recycle(); referencePixels.recycle(); }
                        // Background vocals use the same native renderer and their own phrase context.
                        if (!shape(build(runner, expected(bg, BACKGROUND[i]))).equals(shape(build(runner, bg))))
                            throw new AssertionError("background glyph mismatch");
                        runner.sendStatus(0, status);
                    } catch (Throwable error) {
                        failures.add(name + ": " + error);
                        status.putString("stack", error.toString()); runner.sendStatus(-2, status);
                    }
                }
            }
        });
        Bundle result = new Bundle();
        result.putInt("expectedCases", 8); result.putInt("completedCases", completed[0] - failures.size());
        result.putString("stream", failures.isEmpty() ? "PASS: 8 Chinese character form cases\n"
                : "FAIL: " + String.join("\n", failures) + "\n");
        runner.finish(failures.isEmpty() ? -1 : 0, result);
    }

    private static void assertRow(AppliedLine row, List<SyllableSegment> canonical, String expected) {
        if (!expected.equals(row.text)) throw new AssertionError("dictionary output mismatch");
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < row.words.size(); i++) {
            SyllableSegment from = canonical.get(i), to = row.words.get(i);
            if (from == to || !from.spanId.equals(to.spanId) || from.startMs != to.startMs || from.endMs != to.endMs
                    || from.canonicalStartCp != to.canonicalStartCp || from.canonicalEndCp != to.canonicalEndCp)
                throw new AssertionError("timed ownership changed");
            joined.append(to.text);
        }
        if (!row.words.isEmpty() && !expected.contentEquals(joined)) throw new AssertionError("timed glyphs mismatch");
    }

    private static LyricsDocument document(boolean timed) {
        LyricsDocument doc = new LyricsDocument();
        doc.type = timed ? "Syllable" : "Line"; doc.language = "zh"; doc.detectedChinese = true;
        LyricsLine source = new LyricsLine();
        source.text = SOURCE; source.startMs = 0; source.endMs = 2000;
        if (timed) addWords(source.syllables, SOURCE);
        BackgroundLine bg = new BackgroundLine();
        bg.text = "请別离开"; bg.startMs = 0; bg.endMs = 2000;
        if (timed) addWords(bg.syllables, bg.text);
        source.backgroundLines.add(bg); doc.lines.add(source);
        return doc;
    }

    private static void addWords(List<SyllableSegment> words, String text) {
        int cp = 0;
        for (int offset = 0; offset < text.length();) {
            int end = offset + Character.charCount(text.codePointAt(offset));
            SyllableSegment word = new SyllableSegment();
            word.text = word.sourceText = text.substring(offset, end); word.spanId = "w" + cp;
            word.canonicalStartCp = cp; word.canonicalEndCp = cp + 1;
            word.startMs = cp * 100L; word.endMs = (cp + 1) * 100L; word.totalMs = 100;
            words.add(word); offset = end; cp++;
        }
    }

    private static AppliedLine expected(AppliedLine actual, String text) {
        AppliedLine expected = new AppliedLine();
        expected.text = text; expected.startMs = actual.startMs; expected.endMs = actual.endMs;
        expected.bgLine = actual.bgLine;
        for (SyllableSegment word : actual.words) {
            SyllableSegment copy = SyllableSegment.copyOf(word);
            copy.text = text.substring(text.offsetByCodePoints(0, copy.canonicalStartCp),
                    text.offsetByCodePoints(0, copy.canonicalEndCp));
            expected.words.add(copy);
        }
        return expected;
    }

    private static View build(Instrumentation runner, AppliedLine row) {
        LyricsRowViewFactory.Options options = new LyricsRowViewFactory.Options();
        options.wrapLongLines = true; options.adaptiveSectioningEnabled = false;
        options.lyricsFont = "system"; options.lyricWeight = "Regular";
        View view = new LyricsRowViewFactory(runner.getTargetContext(),
                new LyricsTextFactory(runner.getTargetContext(), null)).build(row, options, null);
        view.setBackgroundColor(Color.rgb(24, 24, 24));
        view.measure(View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        return view;
    }

    private static Bitmap pixels(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap)); return bitmap;
    }

    private static String shape(View view) {
        StringBuilder out = new StringBuilder(view.getClass().getSimpleName());
        out.append(':').append(view.getMeasuredWidth()).append('x').append(view.getMeasuredHeight());
        if (view instanceof TextView) out.append(':').append(((TextView) view).getText());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) out.append('[').append(shape(group.getChildAt(i))).append(']');
        }
        return out.toString();
    }
}
