package com.eza.spicyex.lyrics.language;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;

public class PinyinCharacterizationTest {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final int SWEEP_ENTRIES = 20903;
    private static final int SWEEP_MAPPED = 20378;

    private static final String CANONICAL_NO_TONE_DIGEST =
            "d8182bd4192b4870529463301d0e8d87f25c3b77289a3ed64eb94e9979890522";
    private static final String CANONICAL_TONE_DIGEST =
            "cda00046c7e42c515a712ecb321fffdaa778e55222c330fbcac98df7d9421208";
    private static final String EZA_NO_TONE_DIGEST =
            "3da988c8638a8987aa8327a7a205e2927ea52d8621ce76792bb92ba742113e65";
    private static final String EZA_TONE_DIGEST =
            "da212d237e15b0ecefb74d0066aea92376f3d27a4c03ee00554d960530ba6e21";

    private static final String PHRASE_DATA_DIGEST =
            "f055da03094b63ad65ed4b74de94be328d7fbc0d4367548797fe81b45fb5c2c1";

    private static final String[][] CORPUS = {
            {"\u97f3\u4e50\u54cd\u8d77", "yin yue xiang qi",
                    "y\u012bn yu\u00e8 xi\u0103ng q\u012d", "yin yu\u00e8 shi\u1ea3ng ch\u1ec9"},
            {"\u4e50\u961f\u5728\u6f14\u594f\u4e50\u8c31", "yue dui zai yan zou yue pu",
                    "yu\u00e8 du\u00ec z\u00e0i y\u0103n z\u00f2u yu\u00e8 p\u016d",
                    "yu\u00e8 tu\u1ec1i z\u00e0i yi\u1ebbn z\u00f2u yu\u00e8 p\u1ee7"},
            {"\u94f6\u884c\u884c\u957f", "yin hang hang zhang",
                    "y\u00edn h\u00e1ng h\u00e1ng zh\u01ceng",
                    "y\u00edn h\u00e1ng h\u00e1ng zh\u1ea3ng"},
            {"\u91cd\u65b0\u5f00\u59cb", "chong xin kai shi",
                    "ch\u00f3ng x\u012bn k\u0101i sh\u012d", "ch\u00f3ng shin khai sh\u1eed"},
            {"\u7761\u89c9", "shui jiao", "shu\u00ec ji\u00e0o", "shu\u1ec1i zi\u00e0o"},
            {"\u957f\u53d1\u53ca\u8170", "chang fa ji yao",
                    "ch\u00e1ng f\u00e0 j\u00ed y\u0101o", "ch\u00e1ng f\u00e0 z\u00ed yao"},
            {"\u4e3a\u4e86\u4f60", "wei le ni", "w\u00e8i le n\u012d", "w\u1ec1i l\u1ee3 n\u1ec9"},
            {"\u6211\u8fd8\u662f\u5f88\u559c\u6b22\u4f60", "wo hai shi hen xi huan ni",
                    "w\u014f h\u00e1i sh\u00ec h\u0115n x\u012d hu\u0101n n\u012d",
                    "w\u1ecf h\u00e1i sh\u1eeb h\u1ea9n sh\u1ec9 huan n\u1ec9"},
            {"\u5feb\u4e50", "kuai le", "ku\u00e0i l\u00e8", "khu\u00e0i l\u1edd"},
            {"\u91cd\u5e86", "zhong qing", "zh\u00f2ng q\u00ecng", "zh\u00f2ng ch\u00ecng"},
            {"BTS4 \u5728 2024 \u5e74", "BTS4 zai 2024 nian",
                    "BTS4 z\u00e0i 2024 ni\u00e1n", "BTS4 z\u00e0i 2024 ni\u00e9n"},
            {"\ud840\udc00\u5728\u5531\u6b4c", "\ud840\udc00zai chang ge",
                    "\ud840\udc00z\u00e0i ch\u00e0ng g\u0113",
                    "\ud840\udc00z\u00e0i ch\u00e0ng k\u01a1"},
            {"Hello, world! 123", "Hello, world! 123", "Hello, world! 123", "Hello, world! 123"},
            {"\u4e2d\u56fd rock'n'roll", "zhong guo rock'n'roll",
                    "zh\u014dng gu\u00f3 rock'n'roll", "zhong ku\u00f3 rock'n'roll"},
            {"\u6162\u6162\u5730\u8d70", "man man de zou",
                    "m\u00e0n m\u00e0n de z\u014fu", "m\u00e0n m\u00e0n t\u1ee3 z\u1ecfu"},
            {"\u5973\u6b4c\u624b", "nv ge shou", "n\u01da g\u0113 sh\u014fu", "ny\u1ee7 k\u01a1 sh\u1ecfu"}
    };

    private static final class Sweep {
        final String digest;
        final int entries;
        final int mapped;

        Sweep(String digest, int entries, int mapped) {
            this.digest = digest;
            this.entries = entries;
            this.mapped = mapped;
        }
    }

    private static String canonical(String text, boolean tones) {
        return SpicyJapaneseChineseProcessor.romanizeChinesePinyinLine(text, tones);
    }

    private static String eza(String text, boolean tones) {
        return SpicyJapaneseChineseProcessor.romanizeChineseLine(text, "pinyin-eza", tones);
    }

    private static Sweep sweep(boolean ezaStyle, boolean tones) {
        StringBuilder serialized = new StringBuilder();
        int entries = 0;
        int mapped = 0;
        for (int cp = 0x3007; cp <= 0x9FA5; cp++) {
            if (cp > 0x3007 && cp < 0x4E00) continue;
            if (Character.charCount(cp) != 1) continue;
            String raw = new String(Character.toChars(cp));
            String output = ezaStyle ? eza(raw, tones) : canonical(raw, tones);
            serialized.append(Integer.toHexString(cp)).append('=').append(output).append('\n');
            entries++;
            if (!output.equals(raw)) mapped++;
        }
        return new Sweep(sha256(serialized.toString()), entries, mapped);
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(HEX[(b >> 4) & 0x0f]).append(HEX[b & 0x0f]);
            }
            return out.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void assertAllVariants(String text, String expected) {
        assertEquals("canonical no-tone: " + text, expected, canonical(text, false));
        assertEquals("canonical tone-marked: " + text, expected, canonical(text, true));
        assertEquals("eza no-tone: " + text, expected, eza(text, false));
        assertEquals("eza tone-marked: " + text, expected, eza(text, true));
    }

    @Test
    public void fullRangeCanonicalNoToneDigest() {
        Sweep sweep = sweep(false, false);
        assertEquals(SWEEP_ENTRIES, sweep.entries);
        assertEquals(SWEEP_MAPPED, sweep.mapped);
        assertEquals(CANONICAL_NO_TONE_DIGEST, sweep.digest);
    }

    @Test
    public void fullRangeCanonicalToneMarkedDigest() {
        Sweep sweep = sweep(false, true);
        assertEquals(SWEEP_ENTRIES, sweep.entries);
        assertEquals(SWEEP_MAPPED, sweep.mapped);
        assertEquals(CANONICAL_TONE_DIGEST, sweep.digest);
    }

    @Test
    public void fullRangeEzaNoToneDigest() {
        Sweep sweep = sweep(true, false);
        assertEquals(SWEEP_ENTRIES, sweep.entries);
        assertEquals(SWEEP_MAPPED, sweep.mapped);
        assertEquals(EZA_NO_TONE_DIGEST, sweep.digest);
    }

    @Test
    public void fullRangeEzaToneMarkedDigest() {
        Sweep sweep = sweep(true, true);
        assertEquals(SWEEP_ENTRIES, sweep.entries);
        assertEquals(SWEEP_MAPPED, sweep.mapped);
        assertEquals(EZA_TONE_DIGEST, sweep.digest);
    }

    @Test
    public void phraseTableIsFrozen() {
        String data = PinyinPhraseData.data();
        assertEquals(PHRASE_DATA_DIGEST, sha256(data));
        String[] rows = data.split("\n", -1);
        int nonEmpty = 0;
        for (String row : rows) {
            if (row.trim().isEmpty()) continue;
            nonEmpty++;
            int separator = row.indexOf('=');
            assertEquals("single separator: " + row, separator, row.lastIndexOf('='));
            assertTrue("phrase before separator: " + row, separator > 0);
            String phrase = row.substring(0, separator);
            String reading = row.substring(separator + 1);
            assertFalse("non-empty phrase: " + row, phrase.isEmpty());
            assertFalse("non-empty reading: " + row, reading.isEmpty());
            for (String syllable : reading.split(" ", -1)) {
                assertTrue("tone-numbered syllable: " + row,
                        syllable.matches("[a-z\u00fc:]+[1-5]"));
            }
        }
        assertEquals(280, nonEmpty);
    }

    @Test
    public void toneThreeUsesBreveInCanonicalLibraryOutput() {
        assertEquals("n\u012d", canonical("\u4f60", true));
        assertEquals("h\u0103o", canonical("\u597d", true));
    }

    @Test
    public void phraseRowsUseCaronWhileStandaloneFallbackUsesLibraryMark() {
        assertEquals("yue pu", canonical("\u4e50\u8c31", false));
        assertEquals("yu\u00e8 p\u01d4", canonical("\u4e50\u8c31", true));
        assertEquals("pu", canonical("\u8c31", false));
        assertEquals("p\u016d", canonical("\u8c31", true));
    }

    @Test
    public void uUmlautAndVOutputIsLocked() {
        assertEquals("nv", canonical("\u5973", false));
        assertEquals("n\u01da", canonical("\u5973", true));
        assertEquals("nyu", eza("\u5973", false));
        assertEquals("ny\u1ee7", eza("\u5973", true));
    }

    @Test
    public void neutralToneIsLocked() {
        assertEquals("de", canonical("\u7684", false));
        assertEquals("de", canonical("\u7684", true));
        assertEquals("t\u01a1", eza("\u7684", false));
        assertEquals("t\u1ee3", eza("\u7684", true));
    }

    @Test
    public void toneMarkPlacementForOuIuUi() {
        assertEquals("y\u014fu", canonical("\u6709", true));
        assertEquals("li\u00f9", canonical("\u516d", true));
        assertEquals("shu\u012d", canonical("\u6c34", true));
    }

    @Test
    public void nonBmpAndUnmappedHanScriptPassThroughEveryVariant() {
        assertAllVariants("\ud840\udc00", "\ud840\udc00");
        assertAllVariants("\u3005", "\u3005");
    }

    @Test
    public void latinDigitsAndPunctuationPassThroughWithoutInsertedSpaces() {
        assertAllVariants("BTS4 mp3 ma3", "BTS4 mp3 ma3");
        assertAllVariants("Hello, world! 123", "Hello, world! 123");
        assertEquals("Azhong guo", canonical("A\u4e2d\u56fd", false));
        assertEquals("Azh\u014dng gu\u00f3", canonical("A\u4e2d\u56fd", true));
        assertEquals("Azhong kuo", eza("A\u4e2d\u56fd", false));
        assertEquals("Azhong ku\u00f3", eza("A\u4e2d\u56fd", true));
        assertEquals("2024nian", canonical("2024\u5e74", false));
        assertEquals("2024ni\u00e1n", canonical("2024\u5e74", true));
        assertEquals("2024nien", eza("2024\u5e74", false));
        assertEquals("2024ni\u00e9n", eza("2024\u5e74", true));
        assertEquals("zhong guo rock'n'roll", canonical("\u4e2d\u56fd rock'n'roll", false));
        assertEquals("zh\u014dng gu\u00f3 rock'n'roll", canonical("\u4e2d\u56fd rock'n'roll", true));
        assertEquals("zhong kuo rock'n'roll", eza("\u4e2d\u56fd rock'n'roll", false));
        assertEquals("zhong ku\u00f3 rock'n'roll", eza("\u4e2d\u56fd rock'n'roll", true));
        assertEquals("3.14-zhong guo\uff01", canonical("3.14-\u4e2d\u56fd\uff01", false));
        assertEquals("3.14-zh\u014dng gu\u00f3\uff01", canonical("3.14-\u4e2d\u56fd\uff01", true));
        assertEquals("3.14-zhong kuo\uff01", eza("3.14-\u4e2d\u56fd\uff01", false));
        assertEquals("3.14-zhong ku\u00f3\uff01", eza("3.14-\u4e2d\u56fd\uff01", true));
    }

    @Test
    public void canonicalOutputIsNfcNormalized() {
        String[] samples = {
                canonical("\u4e2d\u56fd", true),
                canonical("\u5973", true),
                canonical("\u97f3\u4e50", true),
                canonical("\u6709", true)
        };
        for (String sample : samples) {
            assertTrue("NFC: " + sample, Normalizer.isNormalized(sample, Normalizer.Form.NFC));
            assertEquals(sample, Normalizer.normalize(sample, Normalizer.Form.NFC));
        }
    }

    @Test
    public void realLineCorpus() {
        for (String[] row : CORPUS) {
            assertEquals("canonical no-tone: " + row[0], row[1], canonical(row[0], false));
            assertEquals("canonical tone-marked: " + row[0], row[2], canonical(row[0], true));
            assertEquals("eza tone-marked: " + row[0], row[3], eza(row[0], true));
        }
    }
}
