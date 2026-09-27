package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.SpotifyTrack;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * A synced payload must never gain an invented timestamp.
 *
 * <p>Regression cover for the defect where a line whose start-time key was absent or renamed read
 * as 0 ms, was then stretched by {@link LyricTimeline#fillMissingEndTimes} across the gap to the
 * next real start, and swallowed the opening of the song while the document still reported itself
 * as correctly synced. Such a line is now placed between its timed neighbours: every word stays
 * on screen, the placed time is bounded by real neighbours, and the synthetic static grid remains
 * legal for a payload that carries no timing at all.
 */
public class NativeLyricsTimingFallbackTest {
    private static final SpotifyTrack TRACK = new SpotifyTrack("Song", "Artist", "Album",
            "spotify:track:abc123", 0, "", 0, null, 180000, false);

    private static LyricsDocument captureColorLyrics(String linesJson) {
        NativeLyricsSource source = new NativeLyricsSource(null, null);
        AtomicReference<LyricsDocument> result = new AtomicReference<>();
        source.addNativeListener((id, doc) -> result.set(doc));
        source.captureColorLyricsResponse("abc123", "{\"lines\":" + linesJson + "}");
        return result.get();
    }

    /** Exercises the lyrics_db parser directly; it is private and needs no SQLite to reach. */
    private static LyricsDocument parseDb(String linesJson, String syncStatus) throws Exception {
        NativeLyricsSource source = new NativeLyricsSource(null, null);
        Method method = NativeLyricsSource.class.getDeclaredMethod("parseNativeDbLyrics",
                String.class, String.class, String.class, String.class, String.class,
                SpotifyTrack.class);
        method.setAccessible(true);
        return (LyricsDocument) method.invoke(source, "abc123", linesJson, syncStatus, "en",
                "{\"displayName\":\"Musixmatch\"}", TRACK);
    }

    private static List<Long> starts(LyricsDocument doc) {
        List<Long> values = new ArrayList<>();
        for (LyricsLine line : doc.lines) values.add(line.startMs);
        return values;
    }

    private static List<String> texts(LyricsDocument doc) {
        List<String> values = new ArrayList<>();
        for (LyricsLine line : doc.lines) values.add(line.text);
        return values;
    }

    // --- the defect: a partial timestamp miss must not fabricate a time ---

    @Test public void syncedPayloadPlacesUntimedMiddleLineBetweenItsNeighbours() {
        LyricsDocument doc = captureColorLyrics(
                "[{\"words\":\"one\",\"startTimeInMs\":1000},"
                        + "{\"words\":\"two\"},"
                        + "{\"words\":\"three\",\"startTimeInMs\":9000}]");
        assertNotNull(doc);
        assertEquals("no lyric text may be dropped", 3, doc.lines.size());
        assertEquals(Arrays.asList("one", "two", "three"), texts(doc));
        assertEquals(1000L, doc.lines.get(0).startMs);
        assertEquals(9000L, doc.lines.get(2).startMs);
        long placed = doc.lines.get(1).startMs;
        assertTrue("placed line must sit after its previous neighbour", placed > 1000L);
        assertTrue("placed line must sit before its next neighbour", placed < 9000L);
        assertEquals("Line", doc.type);
        // LyricTimeline only fills end times it finds missing, so the parser must not pre-fill.
        for (LyricsLine line : doc.lines) assertEquals(0L, line.endMs);
    }

    @Test public void placedLineKeepsNeighbourBoundsWithSeveralUntimedLines() {
        LyricsDocument doc = captureColorLyrics(
                "[{\"words\":\"a\",\"startTimeInMs\":2000},"
                        + "{\"words\":\"b\"},"
                        + "{\"words\":\"c\"},"
                        + "{\"words\":\"d\",\"startTimeInMs\":10000}]");
        assertNotNull(doc);
        assertEquals(4, doc.lines.size());
        long first = doc.lines.get(1).startMs;
        long second = doc.lines.get(2).startMs;
        assertTrue(first > 2000L);
        assertTrue(first < second);
        assertTrue("no placed line may reach the next real start", second < 10000L);
    }

    @Test public void syncedPayloadDoesNotPinLeadingUntimedLineToZero() {
        LyricsDocument doc = captureColorLyrics(
                "[{\"words\":\"orphan\"},{\"words\":\"real\",\"startTimeInMs\":5000}]");
        assertNotNull(doc);
        assertEquals(2, doc.lines.size());
        assertEquals("orphan", doc.lines.get(0).text);
        assertEquals(5000L, doc.lines.get(1).startMs);
        // 0 ms is the defect: fillMissingEndTimes would stretch this line across the whole intro.
        assertTrue("leading untimed line must not be pinned to the start of the song",
                doc.lines.get(0).startMs > 0L);
        assertTrue("and must stay before the first real start",
                doc.lines.get(0).startMs < 5000L);
    }

    @Test public void syncedDbPayloadPlacesRenamedTimestampKey() throws Exception {
        LyricsDocument doc = parseDb(
                "[{\"words\":\"one\",\"startTimeInMs\":1000},{\"words\":\"two\",\"beginMs\":4000}]",
                "LINE");
        assertNotNull(doc);
        assertEquals("renamed key must not cost a line", 2, doc.lines.size());
        assertEquals("one", doc.lines.get(0).text);
        assertEquals("two", doc.lines.get(1).text);
        assertEquals(1000L, doc.lines.get(0).startMs);
        assertTrue("trailing untimed line must follow its neighbour, not precede it",
                doc.lines.get(1).startMs > 1000L);
    }

    // --- guards: the legitimate cases must keep working ---

    @Test public void fullyUntimedPayloadKeepsStaticGrid() {
        LyricsDocument doc = captureColorLyrics(
                "[{\"words\":\"a\"},{\"words\":\"b\"},{\"words\":\"c\"}]");
        assertNotNull(doc);
        assertEquals(3, doc.lines.size());
        assertEquals(Arrays.asList(0L, 3500L, 7000L), starts(doc));
        assertEquals("Static", doc.type);
    }

    @Test public void unsyncedDbPayloadKeepsStaticGrid() throws Exception {
        LyricsDocument doc = parseDb("[{\"words\":\"a\"},{\"words\":\"b\"}]", "UNSYNCED");
        assertNotNull(doc);
        assertEquals(2, doc.lines.size());
        assertEquals(Arrays.asList(0L, 3500L), starts(doc));
        assertEquals("Static", doc.type);
    }

    @Test public void genuineZeroTimestampIsKeptRatherThanTreatedAsMissing() {
        LyricsDocument doc = captureColorLyrics(
                "[{\"words\":\"zero\",\"startTimeInMs\":0},"
                        + "{\"words\":\"next\",\"startTimeInMs\":2000}]");
        assertNotNull(doc);
        assertEquals("0 ms is a real time, not a missing field", 2, doc.lines.size());
        assertEquals(Arrays.asList(0L, 2000L), starts(doc));
        assertEquals("Line", doc.type);
    }

    @Test public void fullySyncedPayloadIsUnchanged() {
        LyricsDocument doc = captureColorLyrics(
                "[{\"words\":\"one\",\"startTimeInMs\":1000},"
                        + "{\"words\":\"two\",\"startTimeInMs\":4000},"
                        + "{\"words\":\"three\",\"startTimeInMs\":9000}]");
        assertNotNull(doc);
        assertEquals(3, doc.lines.size());
        assertEquals(Arrays.asList(1000L, 4000L, 9000L), starts(doc));
        assertEquals("Line", doc.type);
        assertTrue(doc.lines.get(0).endMs == 0L);
    }

    @Test public void alternativeTimestampKeySpellingsStillParse() {
        LyricsDocument doc = captureColorLyrics(
                "[{\"words\":\"one\",\"startTimeMs\":1000},"
                        + "{\"words\":\"two\",\"StartTime\":4000}]");
        assertNotNull(doc);
        assertEquals(2, doc.lines.size());
        assertEquals(Arrays.asList(1000L, 4000L), starts(doc));
    }
}
