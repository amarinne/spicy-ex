package com.eza.spicyex.lyrics.language.mandarin;

import com.eza.spicyex.lyrics.language.MandarinReadings;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Contract, evidence-range, citation, unknown-outcome, and asset-verification checks. */
public class MandarinReadingContractTest {
    private static MandarinReader reader;

    @BeforeClass
    public static void loadReader() throws Exception {
        reader = MandarinReadings.reader();
        assertNotNull(reader);
    }

    @Test
    public void manifestKeepsSourcePinsAndLicences() throws Exception {
        JsonObject manifest = JsonParser.parseString(new String(
                new ClasspathPackSource().manifest(), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray sources = manifest.getAsJsonArray("sources");
        assertNotNull(sources);
        java.util.Map<String, String> expected = new java.util.HashMap<>();
        expected.put("g2pM", "Apache-2.0");
        expected.put("phrase-pinyin-data", "MIT");
        expected.put("pinyin-data", "MIT");
        expected.put("opencc-js", "MIT AND Apache-2.0");
        expected.put("jieba-rs-dict", "MIT");
        expected.put("jieba-rs-hmm", "MIT");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonElement element : sources) {
            JsonObject source = element.getAsJsonObject();
            String id = source.get("id").getAsString();
            if (!expected.containsKey(id)) continue;
            seen.add(id);
            assertEquals(id, expected.get(id), source.get("license").getAsString());
            assertFalse(id, source.get("url") == null && source.get("sha256") == null);
        }
        assertEquals(expected.keySet(), seen);
    }

    @Test
    public void bundleNoticeCarriesAttributionAndLicenceTexts() throws Exception {
        String notice = new String(new ClasspathPackSource().notice(), StandardCharsets.UTF_8);
        assertTrue(notice.contains("CC BY-SA 4.0"));
        assertTrue(notice.contains("owner approved offline bundling"));
        assertTrue(notice.contains("no fallback"));
        for (String licence : new String[]{
                "g2pM-LICENSE", "phrase-pinyin-data-LICENSE", "pinyin-data-LICENSE",
                "opencc-js-LICENSE", "opencc-data-APACHE-2.0.txt", "LICENSE-JIEBA.txt"}) {
            String text = new String(new ClasspathPackSource().licence(licence),
                    StandardCharsets.UTF_8);
            assertTrue(licence, text.length() > 200);
        }
    }

    @Test
    public void neutralFlagKeepsCitationToneAndSeparateDigit() {
        MandarinReadingLine line = reader.read("朋友");
        MandarinSyllable friend = line.tokens().get(0).syllables().get(1);
        assertEquals("you", friend.letters());
        assertEquals(3, friend.tone());
        assertTrue(friend.neutral());
        assertEquals(5, friend.digit());
        assertTrue(friend.hasContext());
        assertEquals(0, friend.contextStart());
        assertEquals(2, friend.contextEnd());
        assertEquals("phrase", friend.basis());
    }

    @Test
    public void citationNeverSubstitutesDifferentLetters() {
        // 了 selects neutral le5. Its alternatives include liao3, but a citation tone may only
        // come from the same letters; the neutral reading and letters survive unchanged.
        MandarinReadingLine line = reader.read("\u4E86");
        MandarinSyllable syllable = line.tokens().get(0).syllables().get(0);
        assertEquals("le", syllable.letters());
        assertEquals(5, syllable.tone());
        assertEquals(5, syllable.digit());
        assertTrue(syllable.neutral());
        assertTrue(syllable.alternatives().contains("liao3"));
        assertEquals("le", MandarinCitation.render(line, true));
    }

    @Test
    public void modelEvidenceSpansTheWholeLine() {
        MandarinReadingLine line = reader.read("\u4ED6\u8BF4\u7684\u786E\u5B9E\u5728\u7406");
        MandarinToken token = line.tokens().get(2);
        assertEquals("\u7684", token.text());
        MandarinSyllable syllable = token.syllables().get(0);
        assertEquals("model", syllable.basis());
        assertEquals(0, syllable.contextStart());
        assertEquals(7, syllable.contextEnd());
    }

    @Test
    public void phraseEvidenceUsesTheSelectedSpanAcrossTokens() {
        MandarinReadingLine line = reader.read("\u6211\u7684\u54E5\u54E5");
        MandarinSyllable last = line.tokens().get(2).syllables().get(1);
        assertEquals("ge5", last.numbered());
        assertEquals("phrase", last.basis());
        assertEquals(2, last.contextStart());
        assertEquals(4, last.contextEnd());
    }

    @Test
    public void characterBasisCarriesNoEvidenceRange() {
        MandarinReadingLine line = reader.read("\u4ED6\u8BF4\u7684\u786E\u5B9E\u5728\u7406");
        MandarinSyllable first = line.tokens().get(0).syllables().get(0);
        assertEquals("character", first.basis());
        assertFalse(first.hasContext());
    }

    @Test
    public void canonicalAndEzaKeepUnknownOutcomesExplicit() {
        MandarinReadingLine unknown = reader.read("\u9FA6");
        assertEquals(MandarinSyllable.STATUS_UNAVAILABLE,
                unknown.tokens().get(0).syllables().get(0).status());
        assertEquals("?", MandarinCitation.render(unknown, true));
        assertEquals("?", MandarinEza.render(unknown, MandarinSandhi.surface(unknown), true));
    }

    @Test
    public void rangesStayExactAroundEmojiAndLineBreaks() {
        String source = "\uD83D\uDE42\u5C55\u89BD\u9928\r\n\u6771\u897F";
        MandarinReadingLine line = reader.read(source);
        int expected = 0;
        for (MandarinToken token : line.tokens()) {
            assertEquals(expected, token.start());
            expected = token.end();
        }
        assertEquals(MandarinReader.points(source).length, expected);
        assertEquals("\uD83D\uDE42", line.tokens().get(0).text());
        assertEquals("\u5C55\u89BD\u9928", line.tokens().get(1).text());
    }

    @Test
    public void modifiedRawAssetIsRejectedBeforeParsing() {
        ClasspathPackSource real = new ClasspathPackSource();
        MandarinReader.PackSource tampered = new MandarinReader.PackSource() {
            @Override
            public byte[] manifest() throws IOException {
                return real.manifest();
            }

            @Override
            public byte[] file(String fileName) throws IOException {
                byte[] value = real.file(fileName);
                if ("t2s.tsv".equals(fileName) && value.length > 32) {
                    value[value.length / 2] ^= 0x01;
                }
                return value;
            }
        };
        try {
            MandarinReader.load(tampered);
            fail("A modified reader asset must not load.");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void readingLineCoversEveryTokenWithoutGaps() {
        MandarinReadingLine line = reader.read("\u670B\u53CB\u5728\u9280\u884C\u3002");
        int next = 0;
        for (MandarinToken token : line.tokens()) {
            assertEquals(next, token.start());
            assertEquals(token.start() + MandarinReader.points(token.text()).length, token.end());
            if ("han".equals(token.kind())) {
                assertEquals(MandarinReader.points(token.text()).length,
                        token.syllables().size());
            } else {
                assertTrue(token.syllables().isEmpty());
            }
            next = token.end();
        }
        assertEquals(MandarinReader.points(line.text()).length, next);
        List<List<String>> surface = MandarinSandhi.surface(line);
        assertEquals(line.tokens().size(), surface.size());
    }
}
