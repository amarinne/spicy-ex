package com.eza.spicyex.lyrics;

import com.eza.spicyex.SpotifyTrack;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** TTML adapter coverage shaped by real AMLL probe documents (Never Gonna Give You Up, 晴天). */
public class AmllTtmlParserTest {
    private static final String WORD_DOC =
            "<tt xmlns=\"http://www.w3.org/ns/ttml\" xml:lang=\"en\">"
            + "<head><metadata></metadata></head><body><div>"
            + "<p begin=\"00:18.809\" end=\"00:22.008\" ttm:agent=\"v1\" itunes:key=\"L1\">"
            + "<span begin=\"00:18.809\" end=\"00:19.041\">We&#39;re</span> "
            + "<span begin=\"00:19.041\" end=\"00:19.255\">no</span> "
            + "<span begin=\"00:19.255\" end=\"00:19.843\" amll:empty-beat=\"2\">strangers</span>"
            + "<span ttm:role=\"x-translation\" xml:lang=\"zh-CN\">&#x6211;&#x4EEC;&#x5BF9;&#x7231;&#x60C5;&#x5E76;&#x4E0D;&#x964C;&#x751F;</span>"
            + "</p>"
            + "<p begin=\"00:22.775\" end=\"00:26.763\">"
            + "<span begin=\"00:22.775\" end=\"00:23.045\">You</span> "
            + "<span begin=\"00:23.045\" end=\"00:23.265\">know</span>"
            + "</p>"
            + "</div></body></tt>";

    @Test
    public void parsesWordTimingAndSkipsTranslationSpans() throws Exception {
        LyricsDocument doc = AmllTtmlParser.parse(WORD_DOC, "track", 212000);
        assertEquals("Word", doc.type);
        assertEquals("amll_ttml", doc.fetchSource);
        assertEquals("AMLL", doc.provider);
        assertEquals(2, doc.lines.size());
        LyricsLine first = doc.lines.get(0);
        assertEquals("We're no strangers", first.text);
        assertEquals(18809, first.startMs);
        assertEquals(22008, first.endMs);
        assertEquals(3, first.syllables.size());
        assertEquals("We're", first.syllables.get(0).text);
        assertEquals(18809, first.syllables.get(0).startMs);
        assertEquals(19041, first.syllables.get(0).endMs);
        assertEquals("strangers", first.syllables.get(2).text);
        assertTrue(first.providerTranslatedText.length() > 0);
        assertEquals("zh-CN", first.providerTranslationLanguage);
        assertTrue(doc.lines.get(1).providerTranslatedText.isEmpty());
    }

    @Test
    public void lineTypeWhenNoWordTimings() throws Exception {
        String doc = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div>"
                + "<p begin=\"00:01.000\" end=\"00:04.000\">Hello</p>"
                + "</div></body></tt>";
        LyricsDocument parsed = AmllTtmlParser.parse(doc, "track", 4000);
        assertEquals("Line", parsed.type);
        assertEquals("Hello", parsed.lines.get(0).text);
        assertTrue(parsed.lines.get(0).syllables.isEmpty());
    }

    @Test
    public void staticTypeWhenUntimed() throws Exception {
        String doc = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div>"
                + "<p>Hello</p>"
                + "</div></body></tt>";
        assertEquals("Static", AmllTtmlParser.parse(doc, "track", 0).type);
    }

    @Test
    public void rejectsNonTtmlAndEmpty() {
        try {
            AmllTtmlParser.parse("<html></html>", "track", 0);
            fail("expected rejection");
        } catch (Exception expected) {
        }
        try {
            AmllTtmlParser.parse("", "track", 0);
            fail("expected rejection");
        } catch (Exception expected) {
        }
    }

    @Test
    public void parsesClockForms() {
        assertEquals(18809, AmllTtmlParser.parseTime("00:18.809", -1));
        assertEquals(22008, AmllTtmlParser.parseTime("00:22.008", -1));
        assertEquals(12500, AmllTtmlParser.parseTime("12.5s", -1));
        assertEquals(-1, AmllTtmlParser.parseTime("garbage", -1));
        assertEquals(42, AmllTtmlParser.parseTime("", 42));
    }

    @Test
    public void searchMatcherPicksTitleAndArtistFile() {
        String body = "[{\"title\":\"\\u6674\\u5929\",\"titles\":[\"\\u6674\\u5929\"],"
                + "\"artist\":\"\\u5468\\u6770\\u4F26\",\"artists\":[\"\\u5468\\u6770\\u4F26\"],"
                + "\"file\":\"1730652389036-69615623-d1511ea9.ttml\"},"
                + "{\"title\":\"Dynamite\",\"artist\":\"BTS\","
                + "\"file\":\"unrelated.ttml\"}]";
        SpotifyTrack track = new SpotifyTrack("晴天", "周杰伦", "叶惠美",
                "spotify:track:0F02KChKwbcQ3tk4q1YxLH", 0, "", 0, "", 269000, false);
        assertEquals("1730652389036-69615623-d1511ea9.ttml",
                LyricsRepository.matchAmllSearchResult(body, track));
        assertEquals("", LyricsRepository.matchAmllSearchResult("[]", track));
        assertEquals("", LyricsRepository.matchAmllSearchResult("not json", track));
    }

    @Test
    public void ttmlGateRejectsJsonAndTraversalFiles() {
        assertTrue(LyricsRepository.looksLikeTtml("<tt xmlns=\"http://www.w3.org/ns/ttml\">"));
        assertTrue(LyricsRepository.looksLikeTtml("<?xml version=\"1.0\"?><tt>"));
        assertFalse(LyricsRepository.looksLikeTtml("{\"queries\":[]}"));
        assertFalse(LyricsRepository.looksLikeTtml(null));
        SpotifyTrack track = new SpotifyTrack("Song", "Artist", "", "spotify:track:x", 0,
                "", 0, "", 200000, false);
        assertEquals("", LyricsRepository.matchAmllSearchResult(
                "[{\"title\":\"Song\",\"artist\":\"Artist\",\"file\":\"../evil.ttml\"}]", track));
    }
}
