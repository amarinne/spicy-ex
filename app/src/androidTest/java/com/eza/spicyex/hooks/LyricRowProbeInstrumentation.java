package com.eza.spicyex.hooks;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.LyricsRowViewFactory;
import com.eza.spicyex.lyrics.LyricsTextFactory;
import com.eza.spicyex.lyrics.SpicyAnimatedTextView;
import com.eza.spicyex.lyrics.SyllableSegment;
import java.util.ArrayList;
import java.util.List;

/** Exercises the layout CLI's production probe on attached, native lyric and text views. */
public final class LyricRowProbeInstrumentation {
    private final Instrumentation host;
    private Activity activity;
    private FrameLayout stage;
    private int completed;

    private LyricRowProbeInstrumentation(Instrumentation host) { this.host = host; }
    public static void run(Instrumentation host) { new LyricRowProbeInstrumentation(host).run(); }

    private void run() {
        Intent intent = new Intent().setClassName(host.getTargetContext(),
                "com.eza.spicyex.auto.AutoRenderingTestActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = host.startActivitySync(intent);
        host.runOnMainSync(() -> {
            stage = new FrameLayout(activity); activity.setContentView(stage);
            android.view.WindowManager.LayoutParams window = activity.getWindow().getAttributes();
            window.width = 680; window.height = 1100; window.x = 37; window.y = 83;
            window.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
            activity.getWindow().setAttributes(window);
        });
        host.waitForIdleSync();
        List<String> failures = new ArrayList<>();
        host.runOnMainSync(() -> {
            check(failures, "sentence whitespace", () -> shortProductionRow(false));
            check(failures, "timed whitespace", () -> shortProductionRow(true));
            check(failures, "real text overlap", this::realOverlap);
            check(failures, "ragged wrapped text", this::raggedText);
            check(failures, "ancestor scale and scroll", this::scaledScroll);
            check(failures, "hidden and clipped text", this::hiddenText);
            activity.finish();
        });
        Bundle result = new Bundle();
        result.putInt("expectedCases", 6); result.putInt("completedCases", completed);
        result.putString("stream", failures.isEmpty() ? "PASS: 6 native lyric probe cases\n"
                : "FAIL: " + String.join("\n", failures) + "\n");
        host.finish(failures.isEmpty() ? -1 : 0, result);
    }

    private void shortProductionRow(boolean timed) {
        AppliedLine line = new AppliedLine(); line.text = "Hi"; line.startMs = 0; line.endMs = 1000;
        if (timed) {
            SyllableSegment word = new SyllableSegment(); word.text = "Hi";
            word.startMs = 0; word.endMs = 1000; line.words.add(word);
        }
        LyricsRowViewFactory.Options options = new LyricsRowViewFactory.Options();
        options.wrapLongLines = true;
        View row = new LyricsRowViewFactory(activity, new LyricsTextFactory(activity, null))
                .build(line, options, null);
        mount(row, 600, 500);
        int[] box = LyricsLayoutEditController.screenRectOf(row);
        if (box == null) throw new AssertionError("row is detached");
        LayoutProbeReport report = new LayoutProbeReport().number("chrome_alpha", 1f)
                .rect("dock", box[2] - 70, box[1], box[2] - 10, box[3]);
        LyricRowProbe.record(report, row);
        if (report.lyricText().isEmpty() || report.lyricText().get(0).isEmpty())
            throw new AssertionError("native text was not measured");
        expect(report, false);
    }

    private void realOverlap() {
        TextView text = text("Hi"); mount(text, 600, 150);
        int[] location = new int[2]; text.getLocationOnScreen(location);
        LayoutProbeReport report = new LayoutProbeReport().number("chrome_alpha", 1f)
                .rect("dock", location[0], location[1], location[0] + 30, location[1] + 150);
        LyricRowProbe.record(report, text);
        expect(report, true);
    }

    private void raggedText() {
        TextView text = text("WWWWWWWWWWWW\nHi"); mount(text, 600, 150);
        if (text.getLayout().getLineCount() != 2) throw new AssertionError("fixture must have two lines");
        int[] location = new int[2]; text.getLocationOnScreen(location);
        int top = location[1] + text.getTotalPaddingTop() + text.getLayout().getLineTop(1);
        LayoutProbeReport report = new LayoutProbeReport().number("chrome_alpha", 1f)
                .rect("dock", location[0] + 300, top, location[0] + 350, top + 20);
        LyricRowProbe.record(report, text);
        expect(report, false);
    }

    private void scaledScroll() {
        FrameLayout row = new FrameLayout(activity);
        row.setPivotX(0); row.setPivotY(0); row.setScaleX(1.25f); row.setScaleY(1.25f);
        TextView text = text("Hi"); text.setPivotX(0); text.setPivotY(0); text.setScaleX(1.5f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(300, 100);
        lp.leftMargin = 60; lp.topMargin = 80; row.addView(text, lp);
        mount(row, 600, 250); row.scrollTo(20, 30);
        LayoutProbeReport report = new LayoutProbeReport(); LyricRowProbe.record(report, row);
        int[] bounds = report.lyricText().get(0).get(0);
        int[] location = new int[2]; text.getLocationOnScreen(location);
        int right = location[0] + Math.round(text.getLayout().getLineRight(0) * 1.5f * 1.25f);
        int top = location[1] + Math.round(text.getTotalPaddingTop() * 1.25f);
        int bottom = location[1] + Math.round((text.getTotalPaddingTop()
                + text.getLayout().getLineBottom(0)) * 1.25f);
        if (Math.abs(bounds[0] - location[0]) > 1 || Math.abs(bounds[2] - right) > 1
                || Math.abs(bounds[1] - top) > 1 || Math.abs(bounds[3] - bottom) > 1)
            throw new AssertionError("text transform differs from native screen coordinates");
    }

    private void hiddenText() {
        FrameLayout row = new FrameLayout(activity);
        TextView hidden = text("Hidden"); hidden.setAlpha(0); row.addView(hidden);
        TextView clipped = text("Clipped");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(300, 100); lp.topMargin = -200;
        row.addView(clipped, lp); mount(row, 600, 100);
        LayoutProbeReport report = new LayoutProbeReport(); LyricRowProbe.record(report, row);
        if (!report.lyricText().get(0).isEmpty()) throw new AssertionError("invisible text was reported");
    }

    private TextView text(String value) {
        TextView text = new SpicyAnimatedTextView(activity);
        text.setText(value); text.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30);
        text.setIncludeFontPadding(false); text.setPadding(0, 0, 0, 0);
        return text;
    }

    private void mount(View view, int width, int height) {
        int[] origin = new int[2]; stage.getRootView().getLocationOnScreen(origin);
        if (origin[0] == 0 || origin[1] == 0) throw new AssertionError("fixture needs a floating window");
        stage.removeAllViews(); stage.addView(view, new FrameLayout.LayoutParams(width, height));
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static void expect(LayoutProbeReport report, boolean covered) {
        boolean actual = report.violations().contains("R7 lyrics_under_chrome 1");
        if (actual != covered || report.violations().size() != (covered ? 1 : 0))
            throw new AssertionError("unexpected violations: " + report.violations());
    }

    private void check(List<String> failures, String label, Runnable test) {
        try { test.run(); completed++; }
        catch (Throwable error) { failures.add(label + ": " + error); }
    }
}
