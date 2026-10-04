package com.eza.spicyex.lyrics.providers;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricTimeline;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.SyllableSegment;
import com.eza.spicyex.lyrics.session.CanonicalSourceCodec;
import org.junit.Test;

import static org.junit.Assert.*;

/** Minimized timing examples from QQ/NetEase, without saved provider payloads. */
public class ProviderTimingParityTest {
    private final LyricsParser parser = new LyricsParser(null);

    private static SpotifyTrack track(long duration) {
        return new SpotifyTrack("Song", "Artist", "Album", "spotify:track:abc123",
                0, "", 0, null, duration, false);
    }

    private LyricsDocument qq(String lyric, long duration) {
        return parser.parseQqWordLyrics(null, track(duration),
                LyricsParser.buildQqQrcResponseForTest(lyric, ""));
    }

    private LyricsDocument netease(String lyric, long duration) {
        com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
        com.google.gson.JsonObject yrc = new com.google.gson.JsonObject();
        yrc.addProperty("lyric", lyric);
        payload.add("yrc", yrc);
        return parser.parseNeteaseWordLyrics(null, track(duration), payload.toString());
    }

    @Test public void rejectsLongWordOverrunWithoutRescaling() {
        assertNull(qq("[123522,2630]You(123522,500)already(124022,500)know(124522,1130)girl(125652,28620)\n"
                + "[154272,1000]next(154272,1000)", 247000));
        assertNull(netease("[1000,1000](1000,4000,0)word\n[6000,1000](6000,1000,0)next", 10000));
    }

    @Test public void permitsWordEndInsideExistingSmallGapHold() {
        LyricsDocument doc = qq("[160353,3311]you(162851,3342)\n[166193,1000]next(166193,1000)", 200000);
        assertNotNull(doc);
        assertEquals(166193, doc.lines.get(0).syllables.get(0).endMs);
        LyricTimeline.applySyncedRows(doc);
        assertEquals(166193, doc.appliedLines.get(1).endMs); // Initial interlude precedes vocals.
    }

    @Test public void rejectsRecordingLongerThanSpotifyInsteadOfDroppingTail() {
        assertNull(qq("[199449,4811]Popular(199449,1751)I(201200,191)know(201391,181)about(201572,378)popular(201950,2310)", 201000));
        assertNull(netease("[9000,2000](9000,2000,0)tail", 10000));
        assertNull(qq("[9000,1000]tail(9500,1500)", 10000));
    }

    @Test public void lineFallbackAlsoRefusesOutOfDurationSungRows() {
        try {
            parser.parseNeteaseLyrics(null, track(10000), "{\"lrc\":{\"lyric\":\"[00:01.00]ok\\n[00:11.00]tail\"}}");
            fail("invalid line candidate accepted");
        } catch (IllegalStateException expected) { }
    }

    @Test public void attachesLeadingAndTrailingUntimedPunctuationToExactTimedOwners() {
        LyricsDocument doc = netease("[1000,1000](1000,0,0)（(1000,1000,0)hello(2000,0,0)）(2000,0,0),", 5000);
        assertNotNull(doc);
        assertEquals("（hello）,", doc.lines.get(0).text);
        assertEquals(1, doc.lines.get(0).syllables.size());
        SyllableSegment word = doc.lines.get(0).syllables.get(0);
        assertEquals("（hello）,", word.text);
        assertEquals(1000, word.startMs);
        assertEquals(2000, word.endMs);
        assertEquals(0, word.canonicalStartCp);
        assertEquals(8, word.canonicalEndCp);
        CanonicalSourceCodec.Record restored = CanonicalSourceCodec.decode(
                CanonicalSourceCodec.encode(doc, 1, "digest", 0));
        assertNotNull(restored);
        assertEquals("（hello）,", restored.document.lines.get(0).text);
    }

    @Test public void qqUntimedMarksUseTheSamePunctuationPolicy() {
        LyricsDocument doc = qq("[1000,1000](1000,0)hello(1000,1000)!(2000,0)", 5000);
        assertNotNull(doc);
        assertEquals("hello!", doc.lines.get(0).text);
        assertEquals(1, doc.lines.get(0).syllables.size());
        assertEquals(2000, doc.lines.get(0).syllables.get(0).endMs);
    }
    @Test public void lastLrcRowInfersOnlyTheRemainingTrackLifetime() {
        LyricsDocument doc = parser.parseNeteaseLyrics(null, track(10000),
                "{\"lrc\":{\"lyric\":\"[00:09.00]tail\"}}");
        assertEquals(10000, doc.lines.get(0).endMs);
        assertNotNull(CanonicalSourceCodec.decode(CanonicalSourceCodec.encode(doc, 1, "d", 0)));
    }

    @Test public void inferredLrcEndsKeepTheExistingExactInterludeThresholdPolicy() {
        LyricsDocument doc = parser.parseNeteaseLyrics(null, track(10000),
                "{\"lrc\":{\"lyric\":\"[00:01.00]first\\n[00:04.00]second\"}}");
        assertEquals(4500, doc.lines.get(0).endMs);
    }

}
