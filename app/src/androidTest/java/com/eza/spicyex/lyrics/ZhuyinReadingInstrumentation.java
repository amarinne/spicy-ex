package com.eza.spicyex.lyrics;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.eza.spicyex.lyrics.language.*;
import com.eza.spicyex.lyrics.reading.DefaultRenderPlanBuilder;
import com.eza.spicyex.lyrics.session.DetectionResult;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Disposable production reading/row checks. Does not change Spotify, playback, settings or the catalog. */
final class ZhuyinReadingInstrumentation {
    static void run(Instrumentation runner) {
        List<String> failures = new ArrayList<>();
        runner.runOnMainSync(() -> {
            for (boolean qingTian : new boolean[]{false, true}) for (boolean timed : new boolean[]{false, true}) {
                String name = (qingTian ? "qingtian-" : "") + (timed ? "split-timed" : "line");
                try {
                    LyricsDocument doc = new LyricsDocument(); doc.language = "zh";
                    LyricsLine line = new LyricsLine(); line.text = qingTian ? "ㄖㄨㄟ ㄙㄡ ㄙㄡ ㄒ一 ㄉㄡ ㄒ一 ㄌㄚ" : "ㄇㄨㄇㄨㄒㄧㄌㄨㄒㄧㄌㄧ";
                    String expected = qingTian ? "ruī sōu sōu xī dōu xī lā" : "mū mū xī lū xī lī";
                    line.startMs = 0; line.endMs = 6000;
                    line.detection = DetectionResult.detected("fixture", line.text, ScriptClassifier.ScriptClass.CHINESE, "zh", 1);
                    if (timed) {
                        List<String> chunks = qingTian ? Arrays.asList("ㄖ", "ㄨㄟ", "ㄙㄡ ㄙㄡ ㄒ", "一 ㄉㄡ ㄒ一", "ㄌㄚ")
                                : Arrays.asList(line.text.split(""));
                        for (int i = 0; i < chunks.size(); i++) {
                            SyllableSegment seg = new SyllableSegment(); seg.text = seg.sourceText = chunks.get(i);
                            seg.spanId = "s" + i; seg.startMs = i * 500; seg.endMs = (i + 1) * 500;
                            line.syllables.add(seg);
                        }
                    }
                    RomanizationOptions opts = new RomanizationOptions("pinyin", "Off", true, "Off", false);
                    String output = LyricsLocalRomanizer.romanizeLine(opts, doc, line, line.text);
                    if (!expected.equals(output)) throw new AssertionError("wrong reading: " + output);
                    if (line.readingRenderPlan == null || !DefaultRenderPlanBuilder.validate(line.readingRenderPlan).valid)
                        throw new AssertionError("invalid reading plan");
                    if (timed && line.readingRenderPlan.timedReadingUnits.size() != (qingTian ? 5 : 12))
                        throw new AssertionError("lost provider timing owners");
                    if (!"nỉ hảo".equals(SpicyJapaneseChineseProcessor.romanizeChineseLine("ㄋㄧˇㄏㄠˇ", "pinyin-eza", true)))
                        throw new AssertionError("authored eza needs no model pack");
                    AppliedLine row = new AppliedLine(); row.text = line.text; row.sourceLine = line;
                    row.readingRenderPlan = line.readingRenderPlan; row.startMs = line.startMs; row.endMs = line.endMs;
                    row.words.addAll(line.syllables);
                    LyricsRowViewFactory.Options options = new LyricsRowViewFactory.Options();
                    options.showRomanization = true; options.attachTransliterationToWords = true;
                    options.lyricsFont = "system"; options.wrapLongLines = true;
                    View view = new LyricsRowViewFactory(runner.getTargetContext(),
                            new LyricsTextFactory(runner.getTargetContext(), null)).build(row, options, null);
                    view.setBackgroundColor(Color.rgb(24, 24, 24));
                    view.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
                    String shown = text(view).replace(" ", "");
                    if (!shown.replaceAll("[\u3100-\u312f一]", "").equals(expected.replace(" ", "")))
                        throw new AssertionError("reading did not mount exactly once: " + shown);
                    if (!shown.replaceAll("[^\u3100-\u312f一]", "").equals(line.text.replace(" ", "")))
                        throw new AssertionError("original lyrics changed: " + shown);
                    Bitmap pixels = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
                    try {
                        view.draw(new Canvas(pixels));
                        File dir = new File(runner.getTargetContext().getFilesDir(), "zhuyin-evidence"); dir.mkdirs();
                        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
                            pixels.compress(Bitmap.CompressFormat.PNG, 100, out);
                        }
                    } finally { pixels.recycle(); }
                    Bundle status = new Bundle(); status.putString("stream", "PASS " + name + ": " + output + "\n");
                    runner.sendStatus(0, status);
                } catch (Throwable failure) { failures.add(name + ": " + failure); }
            }
        });
        Bundle result = new Bundle();
        result.putString("stream", failures.isEmpty() ? "PASS: 4 Zhuyin production reading/row cases\n"
                : "FAIL: " + String.join("\n", failures) + "\n");
        runner.finish(failures.isEmpty() ? -1 : 0, result);
    }
    private static String text(View view) {
        StringBuilder out = new StringBuilder();
        if (view instanceof TextView) out.append(((TextView) view).getText());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            out.append(text(((ViewGroup) view).getChildAt(i)));
        return out.toString();
    }
}
