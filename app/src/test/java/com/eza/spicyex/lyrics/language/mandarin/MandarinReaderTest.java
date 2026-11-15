package com.eza.spicyex.lyrics.language.mandarin;

import com.eza.spicyex.lyrics.language.MandarinReadings;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Reader-level checks: jieba supplies feet, tags, and subwords only; g2pM supplies readings;
 * the plain projection stays separate; frozen sandhi and spelling stay unchanged.
 */
public class MandarinReaderTest {
    private static MandarinReader reader;

    @BeforeClass
    public static void loadReader() throws Exception {
        reader = MandarinReadings.reader();
        assertNotNull(reader);
    }

    @Test
    public void segmentationKeepsJiebaFeetTagsAndSearchSubwords() {
        MandarinReadingLine line = reader.read("\u4E3B\u6559\u7EC3");
        assertEquals(1, line.tokens().size());
        MandarinToken token = line.tokens().get(0);
        assertEquals("\u4E3B\u6559\u7EC3", token.text());
        assertEquals("n", token.tag());
        assertEquals(Arrays.asList("\u4E3B\u6559", "\u6559\u7EC3"), token.subwords());
        assertEquals(3, token.syllables().size());
    }

    @Test
    public void tokensHaveNoReadingAuthority() {
        // 的 is a model decision even though a token-local 的确 phrase exists in the pack.
        MandarinReadingLine line = reader.read("\u4ED6\u8BF4\u7684\u786E\u5B9E\u5728\u7406");
        MandarinSyllable particle = line.tokens().get(2).syllables().get(0);
        assertEquals("model", particle.basis());
        assertEquals("de5", particle.numbered());
    }

    @Test
    public void traditionalShadowKeepsSourceTextAndReadsTheSimplifiedForm() {
        MandarinReadingLine line = reader.read("\u6688\u958B\u4E86\u7D50\u5C40");
        assertEquals("\u6688\u958B\u4E86\u7D50\u5C40", line.text());
        assertEquals("yun1 kai1 le5 jie2 ju2", raws(line));
        List<String> tokens = new ArrayList<>();
        for (MandarinToken token : line.tokens()) tokens.add(token.text());
        assertEquals(Arrays.asList("\u6688\u958B", "\u4E86", "\u7D50\u5C40"), tokens);
    }

    @Test
    public void unavailableCharacterKeepsItsTokenAndReturnsNoReading() {
        MandarinReadingLine line = reader.read("\u9FA6");
        MandarinSyllable syllable = line.tokens().get(0).syllables().get(0);
        assertEquals(MandarinSyllable.STATUS_UNAVAILABLE, syllable.status());
        assertEquals("?", MandarinCitation.render(line, false));
    }

    @Test
    public void frozenSurfaceToneAndSpellingStillApply() {
        // Revision 3: 妹妹 is mêi mềi (tone 4 before the neutral is written flat, the neutral grave).
        MandarinReadingLine line = reader.read("\u59B9\u59B9");
        assertEquals("m\u00EAi m\u1EC1i", MandarinEza.render(line, true));
    }

    @Test
    public void displayShowsOneSelectedReadingPerSyllable() {
        // Owner 2026-11-15: Standard Pinyin and eza式 show the selected reading, never the
        // heteronym list. 了 keeps le5/liao3 evidence but renders one syllable.
        MandarinReadingLine particle = reader.read("\u4E86");
        assertTrue(particle.tokens().get(0).syllables().get(0).alternatives().contains("liao3"));
        assertEquals("le", MandarinCitation.render(particle, true));
        assertEquals("l\u01A1", MandarinEza.render(particle, true));
        assertEquals("x\u00EDng", MandarinCitation.render(reader.read("\u884C"), true));
        assertEquals("sh\u00EDng", MandarinEza.render(reader.read("\u884C"), true));
    }

    @Test
    public void dictionaryReadsNaZheNaBeforeAMeasureWord() {
        // Owner 2026-11-15: follow the dictionary (nà ge, zhè ge, nǎ ge); the spoken
        // nèi/zhèi/něi are speaker variation and get no rule.
        assertEquals("na4 ge5", raws(reader.read("\u90A3\u4E2A")));
        assertEquals("zhe4 ge5", raws(reader.read("\u8FD9\u4E2A")));
        assertEquals("na3 ge5", raws(reader.read("\u54EA\u4E2A")));
        assertFalse(raws(reader.read("\u90A3\u4E2A")).contains("nei"));
    }

    private static String raws(MandarinReadingLine line) {
        StringBuilder out = new StringBuilder();
        for (MandarinToken token : line.tokens()) {
            for (MandarinSyllable syllable : token.syllables()) {
                if (out.length() > 0) out.append(' ');
                out.append(syllable.isUnavailable()
                        ? "<unavailable>" : syllable.letters() + syllable.digit());
            }
        }
        return out.toString();
    }
}
