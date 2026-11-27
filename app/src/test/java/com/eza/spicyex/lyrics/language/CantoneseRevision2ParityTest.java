package com.eza.spicyex.lyrics.language;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The cross-app parity corpus for Cantonese eza式 revision 2, copied from cjk-labs
 * {@code tests/fixtures/eza-canto-rev2-parity.json}. The syllable rows are exact for every client.
 * The line rows are exact only for a to-jyutping reader; where this app's lexicon chooses another
 * base reading, the row is listed in {@link #LEXICON_DIFFERENCES} with the differing syllables.
 */
public class CantoneseRevision2ParityTest {
    /** Source lines whose expected readings differ from this app's lexicon, not from R1–R4. */
    private static final List<String> LEXICON_DIFFERENCES = List.of(
            "人值得 命中減少幾秒 多買一隻錶", // 命 meng6/ming6, 中 zung1/zung3 (mobile/to-jyutping)
            "寧願聽舊笑話 安慰可免則免", // 願 jyun6/jyun2, 話 waa6/waa2 (mobile/to-jyutping)
            "大脑神经清醒 原来只系一梦南柯", // 醒 seng2/sing2 (mobile/to-jyutping)
            "餘生都不會再悲哀", // 生 saang1/sang1 (mobile/to-jyutping)
            "來化解一切壓力似失蹤", // 切 cit3/cai3 (mobile/to-jyutping)
            "只望停在遠處 祝君安好", // 處 cyu5/cyu3 (mobile/to-jyutping)
            "無回憶的男人 就當偷厄與瞞騙", // 人 jan4/jan2 (mobile/to-jyutping)
            "哎呀 唉 欸 喔 噢", // 哎 aai1/ai1, 呀 aa3/jaa3 (mobile/to-jyutping)
            "誤會 覺悟 吳先生 呣"); // 會 wui5/wui6 (mobile/to-jyutping)

    @Test
    public void syllableRowsMatchTheSpellingConverter() throws Exception {
        JsonObject fixture = fixture();
        assertEquals("cjk-labs.eza-canto-rev2-parity.v1", fixture.get("schema").getAsString());
        assertEquals(ChineseOutputStyle.REVISION + "/" + JyutpingRomanizer.REVISION,
                fixture.get("policy").getAsString());
        for (JsonElement element : fixture.getAsJsonArray("syllables")) {
            JsonObject row = element.getAsJsonObject();
            String jyutping = row.get("jyutping").getAsString();
            assertEquals(jyutping, row.get("eza").getAsString(), EzaChineseRomanizer.cantonese(jyutping, true));
        }
    }

    @Test
    public void lineRowsMatchWhereTheLexiconAgrees() throws Exception {
        List<String> failures = new ArrayList<>();
        List<String> differences = new ArrayList<>();
        for (JsonElement element : fixture().getAsJsonArray("cases")) {
            JsonObject row = element.getAsJsonObject();
            String source = row.get("source").getAsString();
            String jyutping = SpicyJapaneseChineseProcessor.romanizeChineseLine(source, "jyutping", true);
            String eza = SpicyJapaneseChineseProcessor.romanizeChineseLine(source, "jyutping-eza", true);
            String wantJyutping = row.get("jyutping").getAsString();
            String wantEza = row.get("eza").getAsString();
            if (jyutping.equals(wantJyutping) && eza.equals(wantEza)) continue;
            String detail = source + ": " + syllableDiff(jyutping, wantJyutping) + " | eza " + syllableDiff(eza, wantEza);
            (LEXICON_DIFFERENCES.contains(source) ? differences : failures).add(detail);
        }
        System.out.println("Cantonese rev2 lexicon differences (" + differences.size() + "):");
        for (String line : differences) System.out.println("  " + line);
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    @Test
    public void simplifiedSourceReadsThroughTheTraditionalFormAndKeepsItsCharacters() {
        assertEquals("lei4 hoi1 teng1 cin2", JyutpingRomanizer.romanize("离开听钱"));
        assertEquals("ngoi3 aa3!A", JyutpingRomanizer.romanize("爱啊!A"));
    }

    private static String syllableDiff(String actual, String expected) {
        String[] a = actual.split(" "), e = expected.split(" ");
        if (a.length != e.length) return "got [" + actual + "] want [" + expected + "]";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (!a[i].equals(e[i])) out.append(out.length() > 0 ? ", " : "").append(a[i]).append("≠").append(e[i]);
        }
        return out.length() == 0 ? "same" : out.toString();
    }

    private static JsonObject fixture() throws Exception {
        try (InputStream stream = CantoneseRevision2ParityTest.class
                .getResourceAsStream("/cantonese/eza-canto-rev2-parity.json")) {
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
