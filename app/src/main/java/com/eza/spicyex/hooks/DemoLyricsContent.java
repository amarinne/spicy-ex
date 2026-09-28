package com.eza.spicyex.hooks;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;

/**
 * Static preview content for the layout editor's Demo toggle - a synthetic track, artwork, and
 * lyrics document so the editor has something to show when nothing (or nothing with useful
 * lyrics) is actually playing. Text is native per-script content only (no romanization/furigana
 * baked in): {@code NativeSpicyShellViewImpl#renderDocument()} already runs the real local
 * romanization pipeline against whatever it's handed, so the user's live reading-mode settings
 * annotate this exactly like a real document would.
 */
final class DemoLyricsContent {
    private DemoLyricsContent() {
    }

    /** Sentinel URI/imageId: never matches a real {@code SpotifyArtworkCache} entry. */
    static SpotifyTrack demoTrack() {
        return new SpotifyTrack("Layout Preview", "SpicyEx", "Demo",
                "spotify:track:spicyexlayoutpreviewdemo00", 45_000L, "#1ED760",
                SystemClock.elapsedRealtime(), "spotify:image:spicyexlayoutpreviewdemo00",
                180_000L, false);
    }

    /** Mixes short and long lines across every script this app has dedicated reading/romanization
     *  logic for (Japanese, Chinese, Korean, Cyrillic, Greek) plus one RTL line (Arabic), timed so
     *  {@link com.eza.spicyex.lyrics.LyricTimeline#applySyncedRows} synthesizes intro/mid-song/
     *  outro gap rows purely from the timing, matching a real synced document's shape.
     *
     *  <p>{@code doc.type = "Syllable"} because most lines below carry real per-word timing
     *  ({@link #addSyllableLine}), which is what actually drives word-highlight rendering
     *  ({@code LyricsRowViewFactory} keys off each line's own {@code syllables}, not the
     *  document-level type) - two lines are still built with the plain line-level {@link #addLine}
     *  (no syllables) so that render path stays visibly represented too, and two lines carry
     *  {@code translatedText} so Translation shows dual-language rendering in the demo as well. */
    static LyricsDocument demoDocument() {
        LyricsDocument doc = new LyricsDocument();
        doc.trackId = "spicyex-layout-preview-demo";
        doc.provider = "Demo";
        doc.fetchSource = "demo";
        doc.type = "Syllable";
        doc.language = "en";
        doc.durationMs = 50_000L;
        addSyllableLine(doc, 4_000, 7_000, "Hello", "(world)");
        addSyllableLine(doc, 7_000, 10_000, "今日は", "(いい天気)", "です", "ね");
        addSyllableLine(doc, 10_000, 13_500, "你好，", "欢迎", "(来到这里)");
        addSyllableLine(doc, 13_500, 17_000, "오늘은", "(정말)", "좋은", "날이에요")
                .translatedText = "Today is a really good day";
        addSyllableLine(doc, 17_000, 20_500, "Сегодня", "(прекрасный)", "день");
        addSyllableLine(doc, 20_500, 24_000, "Σήμερα", "είναι", "(μια όμορφη)", "μέρα");
        addSyllableLine(doc, 24_000, 27_500, "اليوم", "يوم", "(جميل)")
                .translatedText = "Today is a beautiful day";
        addSyllableLine(doc, 27_500, 33_000,
                "This", "is", "a", "longer", "preview", "line", "(meant)", "to", "wrap", "across",
                "more", "than", "one", "row", "so", "you", "can", "see", "how", "longer", "lyrics",
                "behave", "in", "this", "layout.");
        // Kept as plain line-level lines (no syllables) so that render path - not just the
        // syllable/word-highlight one above - is still represented in the demo.
        addLine(doc, 33_000, 36_000, "Yeah (demo)");
        addSyllableLine(doc, 40_000, 43_000, "One", "more", "(line)", "to", "check", "spacing");
        addLine(doc, 43_000, 46_000, "Thanks! (SpicyEx)");
        return doc;
    }

    private static void addLine(LyricsDocument doc, long startMs, long endMs, String text) {
        LyricsLine line = new LyricsLine();
        line.text = text;
        line.startMs = startMs;
        line.endMs = endMs;
        doc.lines.add(line);
    }

    /** Builds a line with real per-word timing: {@code words} split evenly across the line's
     *  [startMs, endMs) span and joined with a space to form {@code line.text}. The timing is
     *  synthetic (evenly spaced, not real per-syllable data), which is fine for a layout preview -
     *  what matters is that each word has its own timed span so word-highlight rendering
     *  ({@code LyricsRowViewFactory}'s {@code hasRealTimedWords} path) actually engages. Returns
     *  the built line so callers can chain a {@code translatedText} assignment. */
    private static LyricsLine addSyllableLine(LyricsDocument doc, long startMs, long endMs,
            String... words) {
        LyricsLine line = new LyricsLine();
        line.startMs = startMs;
        line.endMs = endMs;
        long span = Math.max(1, endMs - startMs);
        int count = Math.max(1, words.length);
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            long segStart = startMs + span * i / count;
            long segEnd = startMs + span * (i + 1) / count;
            SyllableSegment seg = new SyllableSegment();
            seg.text = words[i];
            seg.sourceText = words[i];
            seg.startMs = segStart;
            seg.endMs = segEnd;
            seg.totalMs = segEnd - segStart;
            seg.boundaryAfter = i < words.length - 1;
            line.syllables.add(seg);
            full.append(words[i]);
            if (seg.boundaryAfter) full.append(' ');
        }
        line.text = full.toString();
        doc.lines.add(line);
        return line;
    }

    /** Drawn at runtime - the app ships no placeholder album-art asset. A plain diagonal
     *  gradient plus a faint note glyph, large enough to downscale cleanly to any real art
     *  frame's size via the existing {@code roundBitmap} rounding helper. */
    static Bitmap demoArtBitmap() {
        int size = 512;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gradientPaint.setShader(new LinearGradient(0, 0, size, size,
                Color.rgb(64, 47, 120), Color.rgb(20, 130, 110), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, size, size, gradientPaint);
        Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glyphPaint.setColor(0x33FFFFFF);
        glyphPaint.setTextAlign(Paint.Align.CENTER);
        glyphPaint.setTextSize(size * 0.55f);
        Paint.FontMetrics fm = glyphPaint.getFontMetrics();
        float baseline = size / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText("♪", size / 2f, baseline, glyphPaint);
        return bitmap;
    }
}
