package com.eza.spicyex.lyrics;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.processing.LyricsDocumentProcessor;
import com.eza.spicyex.lyrics.providers.LyricsParser;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.CanonicalSourceCodec;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Seeded property & differential tests for TTML, LRC, Spicy JSON, and Spotify-native timing,
 * canonical span text conservation, and cached-replay == fresh-parse equivalence.
 *
 * <p>Set {@code -Dspicy.dst.seed=<long>} to replay a single failing seed directly.
 */
public class LyricTimelineAndParserPropertyTest {

    private static final int SEEDS_PER_SUITE = 80;
    private static final int PROCESSING_VERSION = 1;

    private static final String[] LATIN_WORDS = {
            "hello", "world", "night", "star", "dream", "echo", "fire", "rain", "softly", "again"
    };
    private static final String[] CJK_WORDS = {
            "君の", "声が", "響く", "夜の", "風", "星", "光", "夢", "空へ", "愛"
    };
    private static final long[] BOUNDARY_GAPS_MS = {
            0L, 150L, 1200L, 2999L, 3000L, 3001L, 5500L
    };

    private final LyricsParser parser = new LyricsParser(
            (ctx, doc) -> LyricsDocumentProcessor.finalizeParsedDocument(ctx, doc, PROCESSING_VERSION));

    @Test
    public void ttmlParsePreservesTimingSpanConservationAndCacheEquivalence() {
        FakeAndroidContext context = new FakeAndroidContext();
        for (long seed : seeds(SEEDS_PER_SUITE, 0x771100L)) {
            Random rng = new Random(seed);
            String tag = "ttml seed=" + seed;
            long durationMs = 120_000L + rng.nextInt(120_000);
            SpotifyTrack track = track("spotify:track:ttml" + seed, durationMs);
            String ttml = generateTtml(rng, durationMs);

            LyricsDocument fresh = parser.parseAmllTtml(context, track, ttml);
            LyricTimeline.applySyncedRows(fresh);

            assertFalse(tag + " parsed zero lines", fresh.lines.isEmpty());
            assertTimelineInvariants(fresh, tag, false);
            assertSpanConservation(fresh, tag);
            for (LyricsLine line : fresh.lines) {
                assertFalse(tag + " translation sentinel leaked into lyric text",
                        line.text.contains("TRANS_SENTINEL"));
            }
            assertCachedReplayEquivalence(context, fresh, tag);
        }
    }

    @Test
    public void lrcParsePreservesGapCollapseTimingAndCacheEquivalence() {
        FakeAndroidContext context = new FakeAndroidContext();
        for (long seed : seeds(SEEDS_PER_SUITE, 0x1BC000L)) {
            Random rng = new Random(seed);
            String tag = "lrc seed=" + seed;
            long durationMs = 150_000L + rng.nextInt(90_000);
            SpotifyTrack track = track("spotify:track:lrc" + seed, durationMs);
            String body = generateLrclibJson(rng, track);

            LyricsDocument fresh = parser.parseLrclibLyrics(context, track, body);
            LyricTimeline.applySyncedRows(fresh);

            assertFalse(tag + " parsed zero lines", fresh.lines.isEmpty());
            // Any remaining interlude line after collapseShortLrcGaps must span at least threshold
            // unless it is a leading interlude before the first vocal.
            for (int i = 1; i < fresh.lines.size() - 1; i++) {
                LyricsLine line = fresh.lines.get(i);
                if (line.interlude) {
                    long span = line.endMs - line.startMs;
                    assertTrue(tag + " short LRC gap was not collapsed (span=" + span + "ms)",
                            span >= LyricTimeline.INTERLUDE_SHOW_THRESHOLD_MS);
                }
            }
            assertTimelineInvariants(fresh, tag, false);
            assertCachedReplayEquivalence(context, fresh, tag);
        }
    }

    @Test
    public void spicyJsonSyllableLineAndStaticPreserveInvariantsAndCacheEquivalence() {
        FakeAndroidContext context = new FakeAndroidContext();
        String[] modes = {"Syllable", "Line", "Static"};
        for (long seed : seeds(SEEDS_PER_SUITE, 0x591C70L)) {
            Random rng = new Random(seed);
            String mode = modes[(int) (Math.abs(seed) % modes.length)];
            String tag = "spicy(" + mode + ") seed=" + seed;
            long durationMs = 140_000L + rng.nextInt(100_000);
            SpotifyTrack track = track("spotify:track:spicy" + seed, durationMs);
            String json = generateSpicyJson(rng, mode);

            LyricsDocument fresh = parser.parseSpicyLyrics(context, track, json, false);
            LyricTimeline.applySyncedRows(fresh);

            assertFalse(tag + " parsed zero lines", fresh.lines.isEmpty());
            assertTimelineInvariants(fresh, tag, false);
            if ("Syllable".equals(mode)) {
                assertSpanConservation(fresh, tag);
            }
            assertCachedReplayEquivalence(context, fresh, tag);
        }
    }

    @Test
    public void spotifyNativeVsStandardGapPolicyDifferentialInvariants() {
        FakeAndroidContext context = new FakeAndroidContext();
        for (long seed : seeds(SEEDS_PER_SUITE, 0x59071FL)) {
            Random rng = new Random(seed);
            String tag = "native-diff seed=" + seed;
            long durationMs = 180_000L;

            LyricsDocument standard = new LyricsDocument();
            standard.trackId = "track-" + seed;
            standard.type = "Line";
            standard.fetchSource = "lrclib";
            standard.provider = "LRCLIB";
            standard.durationMs = durationMs;

            long cursor = BOUNDARY_GAPS_MS[rng.nextInt(BOUNDARY_GAPS_MS.length)];
            int lineCount = 2 + rng.nextInt(10);
            for (int i = 0; i < lineCount; i++) {
                LyricsLine line = new LyricsLine();
                line.text = LATIN_WORDS[rng.nextInt(LATIN_WORDS.length)] + " " + i;
                line.startMs = cursor;
                long dur = 1_500L + rng.nextInt(2_500);
                // Randomly omit endMs to exercise fillMissingEndTimes on both policies.
                line.endMs = rng.nextBoolean() ? cursor + dur : 0L;
                standard.lines.add(line);
                long gap = BOUNDARY_GAPS_MS[rng.nextInt(BOUNDARY_GAPS_MS.length)];
                cursor = cursor + dur + gap;
            }
            if (cursor + 5_000L > standard.durationMs) {
                standard.durationMs = cursor + 6_000L;
            }

            LyricsDocument nativeDoc = LyricsDocument.copyOf(standard);
            nativeDoc.fetchSource = "spotify_native_model";
            nativeDoc.provider = "Spotify (Musixmatch)";

            LyricsDocumentProcessor.finalizeParsedDocument(context, standard, PROCESSING_VERSION);
            LyricsDocumentProcessor.finalizeParsedDocument(context, nativeDoc, PROCESSING_VERSION);
            LyricTimeline.applySyncedRows(standard);
            LyricTimeline.applySyncedRows(nativeDoc);

            assertTimelineInvariants(standard, tag + "[standard]", false);
            assertTimelineInvariants(nativeDoc, tag + "[native]", true);

            // Differential invariant: Spotify-native NEVER synthesizes dot rows from gaps.
            for (AppliedLine row : nativeDoc.appliedLines) {
                assertFalse(tag + " Spotify-native synthesized a gap dot row", row.dotLine);
            }
            // And each non-final native vocal row holds its active window to the next vocal's start.
            for (int i = 0; i < nativeDoc.appliedLines.size() - 1; i++) {
                AppliedLine cur = nativeDoc.appliedLines.get(i);
                AppliedLine next = nativeDoc.appliedLines.get(i + 1);
                if (next.startMs > cur.startMs) {
                    assertEquals(tag + " Spotify-native vocal row failed to hold until next start",
                            next.startMs, cur.endMs);
                }
            }
            assertCachedReplayEquivalence(context, nativeDoc, tag + "[native]");
        }
    }

    private static void assertTimelineInvariants(LyricsDocument doc, String tag, boolean spotifyNative) {
        for (int i = 0; i < doc.lines.size(); i++) {
            LyricsLine line = doc.lines.get(i);
            assertTrue(tag + " line[" + i + "] endMs < startMs (" + line.startMs + ".." + line.endMs + ")",
                    line.endMs >= line.startMs);
            for (int s = 0; s < line.syllables.size(); s++) {
                SyllableSegment seg = line.syllables.get(s);
                assertTrue(tag + " line[" + i + "].syl[" + s + "] endMs < startMs",
                        seg.endMs >= seg.startMs);
            }
        }
        List<AppliedLine> rows = doc.appliedLines;
        assertFalse(tag + " appliedLines empty", rows.isEmpty());
        for (int i = 0; i < rows.size(); i++) {
            AppliedLine row = rows.get(i);
            assertTrue(tag + " row[" + i + "] endMs < startMs", row.endMs >= row.startMs);
            long fillEnd = LyricTimeline.fillEndMs(row);
            assertTrue(tag + " row[" + i + "] fillEndMs exceeded row.endMs",
                    fillEnd <= row.endMs || row.sourceLine == null);
            if (spotifyNative && (row.sourceLine == null || !row.sourceLine.interlude)) {
                assertFalse(tag + " spotify-native row[" + i + "] must not be a synthesized dot row",
                        row.dotLine);
            }
            // Sample start, midpoint, and end-1 for primary-active-row consistency.
            long[] probes = {row.startMs, row.startMs + Math.max(0L, (row.endMs - row.startMs) / 2L),
                    Math.max(row.startMs, row.endMs - 1L)};
            for (long pos : probes) {
                int primary = LyricTimeline.findPrimaryActiveRow(rows, pos);
                if (primary >= 0) {
                    assertTrue(tag + " findPrimaryActiveRow returned inactive row at " + pos,
                            LyricTimeline.isRowActiveAt(rows.get(primary), pos));
                    // If a lead vocal is active at pos, primary must not pick a dot or background row.
                    boolean anyLeadActive = false;
                    for (AppliedLine candidate : rows) {
                        if (!candidate.bgLine && !candidate.dotLine
                                && LyricTimeline.isRowActiveAt(candidate, pos)) {
                            anyLeadActive = true;
                            break;
                        }
                    }
                    if (anyLeadActive) {
                        assertFalse(tag + " dot row beat active lead vocal at " + pos,
                                rows.get(primary).dotLine);
                        assertFalse(tag + " background row beat active lead vocal at " + pos,
                                rows.get(primary).bgLine);
                    }
                }
            }
        }
    }

    private static void assertSpanConservation(LyricsDocument doc, String tag) {
        for (int i = 0; i < doc.lines.size(); i++) {
            LyricsLine line = doc.lines.get(i);
            if (line.syllables == null || line.syllables.isEmpty()) continue;
            int lineCps = line.text.codePointCount(0, line.text.length());
            StringBuilder joined = new StringBuilder();
            for (SyllableSegment seg : line.syllables) {
                if (seg.text != null) joined.append(seg.text);
                if (seg.canonicalStartCp >= 0 && seg.canonicalEndCp >= 0) {
                    assertTrue(tag + " line[" + i + "] invalid canonical range "
                                    + seg.canonicalStartCp + ".." + seg.canonicalEndCp + " vs " + lineCps,
                            seg.canonicalStartCp <= seg.canonicalEndCp && seg.canonicalEndCp <= lineCps);
                }
            }
            assertEquals(tag + " line[" + i + "] non-whitespace characters dropped or duplicated across syllables",
                    stripWhitespace(line.text), stripWhitespace(joined.toString()));
        }
    }

    private static void assertCachedReplayEquivalence(FakeAndroidContext context,
                                                      LyricsDocument fresh, String tag) {
        CanonicalBase freshBase = CanonicalBase.fromDocument(fresh.trackId, fresh);
        String encoded = CanonicalSourceCodec.encode(fresh, 1, freshBase.digest, 123_456L);
        CanonicalSourceCodec.Record decoded = CanonicalSourceCodec.decode(encoded);
        assertNotNull(tag + " CanonicalSourceCodec.decode returned null", decoded);
        LyricsDocument replayed = decoded.document;
        LyricsDocumentProcessor.finalizeParsedDocument(context, replayed, PROCESSING_VERSION);
        LyricTimeline.applySyncedRows(replayed);

        CanonicalBase replayedBase = CanonicalBase.fromDocument(replayed.trackId, replayed);
        assertEquals(tag + " canonical digest changed after codec round-trip",
                freshBase.digest, replayedBase.digest);
        assertEquals(tag + " line count changed after codec round-trip",
                fresh.lines.size(), replayed.lines.size());
        for (int i = 0; i < fresh.lines.size(); i++) {
            LyricsLine a = fresh.lines.get(i);
            LyricsLine b = replayed.lines.get(i);
            assertEquals(tag + " line[" + i + "].text mismatch on replay", a.text, b.text);
            assertEquals(tag + " line[" + i + "].startMs mismatch on replay", a.startMs, b.startMs);
            assertEquals(tag + " line[" + i + "].endMs mismatch on replay", a.endMs, b.endMs);
            assertEquals(tag + " line[" + i + "].syllables.size mismatch on replay",
                    a.syllables.size(), b.syllables.size());
            for (int s = 0; s < a.syllables.size(); s++) {
                assertEquals(tag + " line[" + i + "].syl[" + s + "].text mismatch on replay",
                        a.syllables.get(s).text, b.syllables.get(s).text);
                assertEquals(tag + " line[" + i + "].syl[" + s + "].boundaryAfter mismatch on replay",
                        a.syllables.get(s).boundaryAfter, b.syllables.get(s).boundaryAfter);
            }
        }
        assertEquals(tag + " appliedLines count changed after codec round-trip",
                fresh.appliedLines.size(), replayed.appliedLines.size());
        for (int i = 0; i < fresh.appliedLines.size(); i++) {
            AppliedLine a = fresh.appliedLines.get(i);
            AppliedLine b = replayed.appliedLines.get(i);
            assertEquals(tag + " appliedLine[" + i + "].dotLine mismatch", a.dotLine, b.dotLine);
            assertEquals(tag + " appliedLine[" + i + "].startMs mismatch", a.startMs, b.startMs);
            assertEquals(tag + " appliedLine[" + i + "].endMs mismatch", a.endMs, b.endMs);
        }
    }

    private static String generateTtml(Random rng, long durationMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("<tt xmlns=\"http://www.w3.org/ns/ttml\" ")
                .append("xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\" xml:lang=\"")
                .append(rng.nextBoolean() ? "ja" : "en").append("\"><body><div>");
        long cursor = BOUNDARY_GAPS_MS[rng.nextInt(BOUNDARY_GAPS_MS.length)];
        int lines = 2 + rng.nextInt(8);
        for (int i = 0; i < lines; i++) {
            boolean cjk = rng.nextBoolean();
            int words = 2 + rng.nextInt(4);
            long lineStart = cursor;
            StringBuilder spans = new StringBuilder();
            long segCursor = lineStart;
            for (int w = 0; w < words; w++) {
                long segDur = rng.nextInt(6) == 0 ? 0L : 250L + rng.nextInt(600);
                long segEnd = segCursor + segDur;
                String token = cjk
                        ? CJK_WORDS[rng.nextInt(CJK_WORDS.length)]
                        : LATIN_WORDS[rng.nextInt(LATIN_WORDS.length)];
                spans.append("<span begin=\"").append(formatTtmlTime(segCursor))
                        .append("\" end=\"").append(formatTtmlTime(segEnd)).append("\">")
                        .append(token).append("</span>");
                if (!cjk && w + 1 < words) spans.append(" ");
                segCursor = segEnd + rng.nextInt(80);
            }
            long lineEnd = Math.max(lineStart + 300L, segCursor);
            sb.append("<p begin=\"").append(formatTtmlTime(lineStart))
                    .append("\" end=\"").append(formatTtmlTime(lineEnd)).append("\">")
                    .append(spans);
            if (rng.nextBoolean()) {
                sb.append("<span ttm:role=\"x-translation\" xml:lang=\"en\">TRANS_SENTINEL ")
                        .append(i).append("</span>");
            }
            sb.append("</p>");
            cursor = lineEnd + BOUNDARY_GAPS_MS[rng.nextInt(BOUNDARY_GAPS_MS.length)];
        }
        sb.append("</div></body></tt>");
        return sb.toString();
    }

    private static String formatTtmlTime(long ms) {
        long minutes = ms / 60_000L;
        long seconds = (ms % 60_000L) / 1_000L;
        long millis = ms % 1_000L;
        return String.format(Locale.ROOT, "%02d:%02d.%03d", minutes, seconds, millis);
    }

    private static String generateLrclibJson(Random rng, SpotifyTrack track) {
        StringBuilder lrc = new StringBuilder();
        long cursor = BOUNDARY_GAPS_MS[rng.nextInt(BOUNDARY_GAPS_MS.length)];
        int lines = 2 + rng.nextInt(10);
        for (int i = 0; i < lines; i++) {
            lrc.append('[').append(formatLrcTime(cursor)).append("] ")
                    .append(LATIN_WORDS[rng.nextInt(LATIN_WORDS.length)]).append(' ')
                    .append(LATIN_WORDS[rng.nextInt(LATIN_WORDS.length)]).append('\n');
            long dur = 1_500L + rng.nextInt(2_500);
            long endMarker = cursor + dur;
            if (rng.nextBoolean()) {
                lrc.append('[').append(formatLrcTime(endMarker)).append("]\n");
            }
            long gap = BOUNDARY_GAPS_MS[rng.nextInt(BOUNDARY_GAPS_MS.length)];
            cursor = endMarker + gap;
        }
        JsonObject candidate = new JsonObject();
        candidate.addProperty("trackName", track.title);
        candidate.addProperty("artistName", track.artist);
        candidate.addProperty("duration", track.duration / 1000.0);
        candidate.addProperty("syncedLyrics", lrc.toString());
        JsonArray arr = new JsonArray();
        arr.add(candidate);
        return arr.toString();
    }

    private static String formatLrcTime(long ms) {
        long minutes = ms / 60_000L;
        long seconds = (ms % 60_000L) / 1_000L;
        long centis = (ms % 1_000L) / 10L;
        return String.format(Locale.ROOT, "%02d:%02d.%02d", minutes, seconds, centis);
    }

    private static String generateSpicyJson(Random rng, String mode) {
        JsonObject root = new JsonObject();
        root.addProperty("Type", mode);
        root.addProperty("Language", rng.nextBoolean() ? "en" : "ja");
        root.addProperty("source", "apple_music");
        JsonArray content = new JsonArray();
        double cursorSec = BOUNDARY_GAPS_MS[rng.nextInt(BOUNDARY_GAPS_MS.length)] / 1000.0;
        int lines = 2 + rng.nextInt(7);
        for (int i = 0; i < lines; i++) {
            if ("Static".equals(mode)) {
                JsonObject line = new JsonObject();
                line.addProperty("Text", LATIN_WORDS[rng.nextInt(LATIN_WORDS.length)] + " " + i);
                content.add(line);
            } else if ("Line".equals(mode)) {
                double dur = 1.5 + rng.nextInt(2500) / 1000.0;
                JsonObject line = new JsonObject();
                line.addProperty("Type", "Vocal");
                line.addProperty("Text", LATIN_WORDS[rng.nextInt(LATIN_WORDS.length)] + " line " + i);
                line.addProperty("StartTime", cursorSec);
                line.addProperty("EndTime", cursorSec + dur);
                line.addProperty("OppositeAligned", rng.nextInt(4) == 0);
                content.add(line);
                cursorSec += dur + BOUNDARY_GAPS_MS[rng.nextInt(BOUNDARY_GAPS_MS.length)] / 1000.0;
            } else {
                JsonObject wrapper = new JsonObject();
                wrapper.addProperty("Type", "Vocal");
                wrapper.addProperty("OppositeAligned", rng.nextInt(4) == 0);
                JsonObject lead = new JsonObject();
                JsonArray syllables = new JsonArray();
                double lineStart = cursorSec;
                double segCursor = lineStart;
                int count = 2 + rng.nextInt(4);
                boolean cjk = rng.nextBoolean();
                for (int s = 0; s < count; s++) {
                    double segDur = 0.2 + rng.nextInt(500) / 1000.0;
                    JsonObject syl = new JsonObject();
                    String tok = cjk
                            ? CJK_WORDS[rng.nextInt(CJK_WORDS.length)]
                            : LATIN_WORDS[rng.nextInt(LATIN_WORDS.length)];
                    syl.addProperty("Text", tok);
                    syl.addProperty("StartTime", segCursor);
                    syl.addProperty("EndTime", segCursor + segDur);
                    syl.addProperty("IsPartOfWord", cjk ? rng.nextBoolean() : (s + 1 < count && rng.nextInt(4) == 0));
                    syllables.add(syl);
                    segCursor += segDur + 0.05;
                }
                lead.add("Syllables", syllables);
                lead.addProperty("StartTime", lineStart);
                lead.addProperty("EndTime", segCursor);
                wrapper.add("Lead", lead);
                content.add(wrapper);
                cursorSec = segCursor + BOUNDARY_GAPS_MS[rng.nextInt(BOUNDARY_GAPS_MS.length)] / 1000.0;
            }
        }
        root.add("Static".equals(mode) ? "Lines" : "Content", content);
        return root.toString();
    }

    private static String stripWhitespace(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) out.append(c);
        }
        return out.toString();
    }

    private static SpotifyTrack track(String uri, long durationMs) {
        return new SpotifyTrack("Title", "Artist", "Album", uri, 0L, "", 0L, "", durationMs, false);
    }

    private static long[] seeds(int count, long baseSeed) {
        String single = System.getProperty("spicy.dst.seed");
        if (single != null && !single.trim().isEmpty()) {
            return new long[]{Long.parseLong(single.trim())};
        }
        long[] out = new long[count];
        for (int i = 0; i < count; i++) out[i] = baseSeed + i;
        return out;
    }
}
