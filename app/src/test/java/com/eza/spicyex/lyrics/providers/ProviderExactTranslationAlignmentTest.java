package com.eza.spicyex.lyrics.providers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Provider translation alignment through the public parser entry points. NetEase LRC, NetEase
 * YRC, and QQ Music line payloads attach a translation only when its timestamp equals the line
 * start; QQ QRC keeps its documented nearest-row policy, strictly under 1500 ms.
 */
public class ProviderExactTranslationAlignmentTest {
    private static final SpotifyTrack TRACK = new SpotifyTrack(
            "Song", "Artist", "Album", "spotify:track:abc123", 0, "", 0, null, 180000, false);

    private final LyricsParser parser = new LyricsParser(null);

    @Test
    public void neteaseLrcTranslationAttachesOnlyOnExactLineStarts() {
        String lyric = "[00:01.00] hello\n[00:05.00] world\n[00:09.00] again";
        String translation = "[00:01.00] 你好\n[00:05.02] 世界\n[00:09.00] 再来";

        LyricsDocument doc = parser.parseNeteaseLyrics(null, TRACK,
                neteaseLinePayload(lyric, translation));

        assertEquals(3, doc.lines.size());
        assertEquals("你好", doc.lines.get(0).providerTranslatedText);
        assertEquals("", doc.lines.get(1).providerTranslatedText);
        assertEquals("再来", doc.lines.get(2).providerTranslatedText);
    }

    @Test
    public void neteaseWordTranslationAttachesOnlyOnExactLineStart() {
        String yrc = "[1000,1000](1000,500,0)hello\n[5000,1000](5000,500,0)world";
        String translation = "[00:01.00] 你好\n[00:04.98] 世界";

        LyricsDocument doc = parser.parseNeteaseWordLyrics(null, TRACK,
                neteaseWordPayload(yrc, translation));

        assertNotNull(doc);
        assertEquals(2, doc.lines.size());
        assertEquals(1000, doc.lines.get(0).startMs);
        assertEquals("你好", doc.lines.get(0).providerTranslatedText);
        assertEquals("", doc.lines.get(1).providerTranslatedText);
    }

    @Test
    public void qqLineTranslationAttachesOnlyOnExactLineStart() {
        String lyric = "[00:01.00] hello\n[00:05.00] world";
        String translation = "[00:01.00] 你好\n[00:05.02] 世界";

        LyricsDocument doc = parser.parseQqMusicLyrics(null, TRACK,
                qqLinePayload(lyric, translation));

        assertEquals(2, doc.lines.size());
        assertEquals("你好", doc.lines.get(0).providerTranslatedText);
        assertEquals("", doc.lines.get(1).providerTranslatedText);
    }

    @Test
    public void lastValidDuplicateTranslationRowWinsAndBlankRowsDoNotClearIt() {
        String lyric = "[00:01.00] hello\n[00:03.00] middle\n[00:09.00] again";
        String translation = "[00:01.00] 旧\n[00:01.00] 新\n[00:01.00]   \n[00:03.00]   ";

        LyricsDocument doc = parser.parseNeteaseLyrics(null, TRACK,
                neteaseLinePayload(lyric, translation));

        assertEquals(3, doc.lines.size());
        assertEquals("新", doc.lines.get(0).providerTranslatedText);
        assertEquals("", doc.lines.get(1).providerTranslatedText);
        assertEquals("", doc.lines.get(2).providerTranslatedText);
    }

    @Test
    public void malformedAndBlankTranslationRowsAreIgnored() {
        String lyric = "[00:01.00] hello\n[00:06.00] world";
        String translation = "not a timestamp\n[abc] text\n[00:01.00] 你好\n[00:06.00]";

        LyricsDocument doc = parser.parseNeteaseLyrics(null, TRACK,
                neteaseLinePayload(lyric, translation));

        assertEquals(2, doc.lines.size());
        assertEquals("你好", doc.lines.get(0).providerTranslatedText);
        assertEquals("", doc.lines.get(1).providerTranslatedText);
    }

    @Test
    public void translationEqualToSourceTextLeavesSidecarEmpty() {
        String lyric = "[00:01.00] hello";
        String translation = "[00:01.00] hello";

        LyricsDocument doc = parser.parseNeteaseLyrics(null, TRACK,
                neteaseLinePayload(lyric, translation));

        assertEquals(1, doc.lines.size());
        assertEquals("hello", doc.lines.get(0).text);
        assertEquals("", doc.lines.get(0).providerTranslatedText);
    }

    @Test
    public void translationRowsTolerateCrlfAndInvisibleCharacters() {
        String lyric = "[00:01.00] hel\u200Blo\r\n[00:05.00] world";
        String translation = "[00:01.00]\u00A0你好\u200B\r\n[00:05.00]\u00A0世界";

        LyricsDocument doc = parser.parseNeteaseLyrics(null, TRACK,
                neteaseLinePayload(lyric, translation));

        assertEquals(2, doc.lines.size());
        assertEquals("hello", doc.lines.get(0).text);
        assertEquals("你好", doc.lines.get(0).providerTranslatedText);
        assertEquals("世界", doc.lines.get(1).providerTranslatedText);
    }

    @Test
    public void qqQrcTranslationKeepsNearestRowPolicyUnder1500ms() {
        String response = LyricsParser.buildQqQrcResponseForTest(
                "[1000,1000]a(1000,1000)\n[5000,1000]b(5000,1000)",
                "[00:02.49] 近\n[00:07.50] 远");

        LyricsDocument doc = parser.parseQqWordLyrics(null, TRACK, response);

        assertNotNull(doc);
        assertEquals(2, doc.lines.size());
        assertEquals("近", doc.lines.get(0).providerTranslatedText);
        assertEquals("", doc.lines.get(1).providerTranslatedText);
    }

    @Test
    public void qqQrcTranslationBoundaryIsStrictlyBelow1500ms() {
        String lyric = "[1000,1000]a(1000,1000)";

        LyricsDocument atBoundary = parser.parseQqWordLyrics(null, TRACK,
                LyricsParser.buildQqQrcResponseForTest(lyric, "[00:02.50] 远"));
        LyricsDocument belowBoundary = parser.parseQqWordLyrics(null, TRACK,
                LyricsParser.buildQqQrcResponseForTest(lyric, "[00:02.499] 近"));

        assertNotNull(atBoundary);
        assertNotNull(belowBoundary);
        assertEquals("", atBoundary.lines.get(0).providerTranslatedText);
        assertEquals("近", belowBoundary.lines.get(0).providerTranslatedText);
    }

    private static String neteaseLinePayload(String lyric, String translation) {
        JsonObject lrc = new JsonObject();
        lrc.addProperty("lyric", lyric);
        JsonObject tlyric = new JsonObject();
        tlyric.addProperty("lyric", translation);
        JsonObject root = new JsonObject();
        root.add("lrc", lrc);
        root.add("tlyric", tlyric);
        return root.toString();
    }

    private static String neteaseWordPayload(String yrc, String translation) {
        JsonObject yrcObject = new JsonObject();
        yrcObject.addProperty("lyric", yrc);
        JsonObject ytlrc = new JsonObject();
        ytlrc.addProperty("lyric", translation);
        JsonObject root = new JsonObject();
        root.add("yrc", yrcObject);
        root.add("ytlrc", ytlrc);
        return root.toString();
    }

    private static String qqLinePayload(String lyric, String translation) {
        JsonObject root = new JsonObject();
        root.addProperty("lyric", Base64.getEncoder().encodeToString(
                lyric.getBytes(StandardCharsets.UTF_8)));
        root.addProperty("trans", Base64.getEncoder().encodeToString(
                translation.getBytes(StandardCharsets.UTF_8)));
        return root.toString();
    }
}
