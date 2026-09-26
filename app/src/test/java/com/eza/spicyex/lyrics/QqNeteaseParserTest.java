package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.SpotifyTrack;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * QQ/NetEase parser fixtures: LRC bodies, YRC word timing, QRC round-trip through the ported
 * TripleDES, credit stripping, and the karaoke/title-card guards. Word-level documents report
 * type "Word" (the PR labeled them "Syllable").
 */
public class QqNeteaseParserTest {
    private static final SpotifyTrack TRACK = new SpotifyTrack(
            "Song", "Artist", "Album", "spotify:track:abc123", 0, "", 0, null, 180000, false);

    private static LyricsParser parser() {
        return new LyricsParser(null);
    }

    @Test
    public void neteaseLrcParsesLinesAndAlignsTranslation() {
        String body = "{\"lrc\":{\"lyric\":\"[00:01.00] hello\\n[00:05.00] world\\n\"},"
                + "\"tlyric\":{\"lyric\":\"[00:01.00] 你好\\n\"}}";

        LyricsDocument doc = parser().parseNeteaseLyrics(null, TRACK, body);

        assertEquals("netease", doc.fetchSource);
        assertEquals("NetEase", doc.provider);
        assertEquals("Line", doc.type);
        assertEquals(2, doc.lines.size());
        assertEquals("hello", doc.lines.get(0).text);
        assertEquals("你好", doc.lines.get(0).providerTranslatedText);
        assertEquals("", doc.lines.get(1).providerTranslatedText);
    }

    @Test
    public void neteaseLrcStripsCreditLinesIntoWriters() {
        String body = "{\"lrc\":{\"lyric\":\"[00:00.000] 作词 : Bob\\n[00:01.00] hello\\n\"}}";

        LyricsDocument doc = parser().parseNeteaseLyrics(null, TRACK, body);

        assertEquals(1, doc.lines.size());
        assertEquals("hello", doc.lines.get(0).text);
        assertEquals("Bob", doc.songWriters);
    }

    @Test
    public void neteaseLrcEmptyThrows() {
        try {
            parser().parseNeteaseLyrics(null, TRACK, "{\"lrc\":{}}");
            assertTrue("expected IllegalStateException", false);
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void neteaseYrcParsesWordTimingAsWord() {
        String body = "{\"yrc\":{\"lyric\":"
                + "\"[1000,2000](1000,500,0)hel(1500,500,0)lo\\n"
                + "[3000,2000](3000,1000,0)world\"},"
                + "\"ytlrc\":{\"lyric\":\"[00:01.00] 你好\\n\"}}";

        LyricsDocument doc = parser().parseNeteaseWordLyrics(null, TRACK, body);

        assertNotNull(doc);
        assertEquals("Word", doc.type);
        assertEquals(2, doc.lines.size());
        assertEquals("hello", doc.lines.get(0).text);
        assertEquals(2, doc.lines.get(0).syllables.size());
        assertEquals(1000, doc.lines.get(0).startMs);
        assertEquals("你好", doc.lines.get(0).providerTranslatedText);
    }

    @Test
    public void neteaseYrcCollectsWriterCredits() {
        String body = "{\"yrc\":{\"lyric\":"
                + "\"{\\\"t\\\":0,\\\"c\\\":[{\\\"tx\\\":\\\"作词: \\\"},{\\\"tx\\\":\\\"Bob\\\"}]}\\n"
                + "[1000,2000](1000,500,0)hi\"}}";

        LyricsDocument doc = parser().parseNeteaseWordLyrics(null, TRACK, body);

        assertNotNull(doc);
        assertEquals("Bob", doc.songWriters);
        assertEquals(1, doc.lines.size());
    }

    @Test
    public void neteaseYrcWithoutContentReturnsNull() {
        assertNull(parser().parseNeteaseWordLyrics(null, TRACK, "{\"yrc\":{}}"));
        assertNull(parser().parseNeteaseWordLyrics(null, TRACK, "{}"));
    }

    @Test
    public void qqLineParsesBase64BodyAndTranslation() {
        String lyric = Base64.getEncoder().encodeToString(
                "[00:01.00] hello\n[00:05.00] world\n".getBytes(StandardCharsets.UTF_8));
        String trans = Base64.getEncoder().encodeToString(
                "[00:01.00] 你好\n".getBytes(StandardCharsets.UTF_8));
        String body = "{\"lyric\":\"" + lyric + "\",\"trans\":\"" + trans + "\"}";

        LyricsDocument doc = parser().parseQqMusicLyrics(null, TRACK, body);

        assertEquals("qq_music", doc.fetchSource);
        assertEquals("QQ Music", doc.provider);
        assertEquals("Line", doc.type);
        assertEquals(2, doc.lines.size());
        assertEquals("你好", doc.lines.get(0).providerTranslatedText);
    }

    @Test
    public void qqLineEmptyThrows() {
        try {
            parser().parseQqMusicLyrics(null, TRACK, "{\"lyric\":\"\"}");
            assertTrue("expected IllegalStateException", false);
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void qqQrcRoundTripParsesWordTimingAsWord() {
        String response = LyricsParser.buildQqQrcResponseForTest(
                "[1000,2000]hel(1000,500)lo(1500,500)\n[3000,2000]world(3000,1000)",
                "[00:01.00] 你好\n");
        assertTrue(!response.isEmpty());

        LyricsDocument doc = parser().parseQqWordLyrics(null, TRACK, response);

        assertNotNull(doc);
        assertEquals("Word", doc.type);
        assertEquals(2, doc.lines.size());
        assertEquals("hello", doc.lines.get(0).text);
        assertEquals(2, doc.lines.get(0).syllables.size());
        assertEquals("你好", doc.lines.get(0).providerTranslatedText);
    }

    @Test
    public void qqQrcGarbageReturnsNull() {
        assertNull(parser().parseQqWordLyrics(null, TRACK, null));
        assertNull(parser().parseQqWordLyrics(null, TRACK, "not xml at all"));
        assertNull(parser().parseQqWordLyrics(null, TRACK, "<content>zzzz</content>"));
    }

    @Test
    public void instrumentalPlaceholderClearsLinesAndMarks() {
        String body = "{\"lrc\":{\"lyric\":"
                + "\"[00:00.000] 作词 : Bob\\n[00:01.00] 纯音乐，请欣赏\\n\"}}";

        LyricsDocument doc = parser().parseNeteaseLyrics(null, TRACK, body);

        assertTrue(doc.lines.isEmpty());
        assertTrue(InstrumentalTracks.isInstrumental(TRACK));
    }
}
