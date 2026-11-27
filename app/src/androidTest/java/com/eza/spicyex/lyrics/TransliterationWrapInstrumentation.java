package com.eza.spicyex.lyrics;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;
import com.eza.spicyex.lyrics.reading.ReadingModels.TextRange;
import com.eza.spicyex.lyrics.reading.ReadingModels.TimedReadingUnit;
import java.util.ArrayList;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/** Native production-row regression for provider spans containing several reading words. */
final class TransliterationWrapInstrumentation {
    static void run(Instrumentation runner) {
        List<String> failures = new ArrayList<>();
        runner.runOnMainSync(() -> {
            for (boolean adaptive : new boolean[]{false, true}) {
                try { check(runner, adaptive); }
                catch (Throwable error) { failures.add("adaptive=" + adaptive + ": " + error); }
            }
        });
        Bundle result = new Bundle();
        result.putString("stream", failures.isEmpty() ? "PASS: 2 transliteration wrap cases\n"
                : "FAIL: " + String.join("\n", failures) + "\n");
        runner.finish(failures.isEmpty() ? -1 : 0, result);
    }

    private static void check(Instrumentation runner, boolean adaptive) throws Exception {
        AppliedLine line = new AppliedLine();
        line.text = "你的笑容曾温暖我的心";
        String[] source = {"你的", "笑容曾温暖我的", "心"};
        String[] reading = {"nỉ tơ", "shiao róng tsáng wuàn nuǎn wǒ", "shin"};
        List<TimedReadingUnit> units = new ArrayList<>();
        int cursor = 0;
        for (int i = 0; i < source.length; i++) {
            SyllableSegment word = new SyllableSegment();
            word.text = word.sourceText = source[i]; word.spanId = "s" + i;
            word.startMs = i * 1000; word.endMs = (i + 1) * 1000; word.totalMs = 1000;
            word.canonicalStartCp = cursor; cursor += source[i].length(); word.canonicalEndCp = cursor;
            line.words.add(word);
            units.add(new TimedReadingUnit(word.spanId,
                    new TextRange(word.canonicalStartCp, cursor), reading[i], word.spanId));
        }
        line.readingRenderPlan = new RenderPlan("wrap", Collections.emptyList(),
                Collections.emptyList(), units, String.join(" ", reading), null);
        LyricsRowViewFactory.Options options = new LyricsRowViewFactory.Options();
        options.showRomanization = true; options.adaptiveSectioningEnabled = adaptive;
        options.textSizeMultiplier = 1.6f;
        options.lyricsFont = "system";
        ViewGroup row = new LyricsRowViewFactory(runner.getTargetContext(),
                new LyricsTextFactory(runner.getTargetContext(), null)).build(line, options, null);
        ViewGroup roman = (ViewGroup) row.getChildAt(1);
        TextView reference = (TextView) roman.getChildAt(0);
        int width = (int) Math.ceil(reference.getPaint().measureText(reading[1]))
                + row.getPaddingLeft() + row.getPaddingRight() + 10;
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
        TreeSet<Integer> tops = new TreeSet<>();
        StringBuilder shown = new StringBuilder();
        for (int i = 0; i < roman.getChildCount(); i++) {
            TextView text = (TextView) roman.getChildAt(i);
            tops.add(text.getTop());
            shown.append(text.getText().toString().replace(" ", ""));
            if (text.getLayout().getEllipsisCount(0) > 0)
                throw new AssertionError("reading clipped");
        }
        if (!shown.toString().equals(String.join("", reading).replace(" ", "")))
            throw new AssertionError("reading changed: " + shown);
        if (tops.size() != 2) throw new AssertionError("expected 2 reading lines, got " + tops.size());
        // The middle provider span has six visual words but still owns one continuous sweep.
        TextView first = (TextView) roman.getChildAt(2);
        TextView last = (TextView) roman.getChildAt(7);
        LyricsSyllableViewState.applyWordGradient(line.words.get(1), -100f, 0f);
        Bitmap firstUnsung = pixels(first), lastUnsung = pixels(last);
        LyricsSyllableViewState.applyWordGradient(line.words.get(1), 50f, 0f);
        Bitmap firstMiddle = pixels(first), lastMiddle = pixels(last);
        try {
            if (firstUnsung.sameAs(firstMiddle) || !lastUnsung.sameAs(lastMiddle))
                throw new AssertionError("timed span sweep restarted inside its visual words");
        } finally {
            firstUnsung.recycle(); lastUnsung.recycle(); firstMiddle.recycle(); lastMiddle.recycle();
        }
        for (int i = 0; i < line.words.size(); i++) {
            SyllableSegment word = line.words.get(i);
            if (!word.text.equals(source[i]) || word.startMs != i * 1000 || word.endMs != (i + 1) * 1000)
                throw new AssertionError("source or timing changed");
        }
        for (SyllableSegment word : line.words) LyricsSyllableViewState.applyWordGradient(word, 100f, 0f);
        row.setBackgroundColor(Color.BLACK);
        Bitmap screenshot = pixels(row);
        File dir = new File(runner.getTargetContext().getFilesDir(), "translit-wrap-evidence");
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "adaptive-" + adaptive + ".png"))) {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally { screenshot.recycle(); }
    }

    private static Bitmap pixels(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }
}
