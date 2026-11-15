package com.eza.spicyex.lyrics.language.mandarin;

import com.eza.spicyex.lyrics.language.MandarinReadings;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The owner's acceptance table for Mandarin eza式 revision 3
 * ({@code eza-2026-11-15.3}), checked at the layer 2-4 boundary.
 *
 * <p>Rows supply the owner-confirmed reading because the installed pack still disagrees on some
 * neutral readings (漂亮, 认识, 头发, 早上, 小姐, 想想, 知道). Reader accuracy is measured
 * separately; those rows are recorded reading gaps, never per-word patches.
 */
public class MandarinRevision3AcceptanceTest {
    private static final String READING_GAP =
            "layer 2-4 boundary rows supply the owner-confirmed reading";

    /** source, reading (digit 5 means neutral), layer-2 digits, eza text. */
    private static final String[][] ROWS = {
            {"你好", "ni3 hao3", "2 3", "ní hảo"},
            {"妈妈", "ma1 ma5", "1 6", "ma mà"},
            {"我很好", "wo3 hen3 hao3", "3 2 3", "wỏhấnhảo"},
            {"他的", "ta1 de5", "1 6", "thatờ"},
            {"你很美", "ni3 hen3 mei3", "3 2 3", "nỉhấnmểi"},
            {"先生", "xian1 sheng5", "1 6", "shien shầng"},
            {"知道", "zhi1 dao5", "1 6", "zhư tào"},
            {"哥哥", "ge1 ge5", "1 6", "kưa kừa"},
            {"我也想你", "wo3 ye3 xiang3 ni3", "2 3 2 3", "wóyẻshiángnỉ"},
            {"爷爷", "ye2 ye5", "2 7", "yé yẻ"},
            {"朋友", "peng2 you5", "2 7", "pấng yỏu"},
            {"头发", "tou2 fa5", "2 7", "thóu fả"},
            {"婆婆", "po2 po5", "2 7", "puó puỏ"},
            {"我也想你好", "wo3 ye3 xiang3 ni3 hao3", "2 3 2 2 3", "wóyẻshiángní hảo"},
            {"来了", "lai2 le5", "2 0", "láilơ"},
            {"觉得", "jue2 de5", "2 0", "zyué tơ"},
            {"别的", "bie2 de5", "2 0", "bié tơ"},
            {"什么", "shen2 me5", "2 0", "shấn mơ"},
            {"老李买酒", "lao3 li3 mai3 jiu3", "2 3 2 3", "láo lỉmái ziỏu"},
            {"你买好酒", "ni3 mai3 hao3 jiu3", "2 3 2 3", "nímải háoziỏu"},
            {"姐姐", "jie3 jie5", "3 0", "ziẻ zie"},
            {"我的", "wo3 de5", "3 0", "wỏtơ"},
            {"奶奶", "nai3 nai5", "3 0", "nải nai"},
            {"喜欢", "xi3 huan5", "3 0", "shỉ huan"},
            {"早上", "zao3 shang5", "3 0", "zảo shang"},
            {"椅子", "yi3 zi5", "3 0", "yỉ zư"},
            {"耳朵", "er3 duo5", "3 0", "ẻr tuo"},
            {"老总好看", "lao3 zong3 hao3 kan4", "2 2 3 4", "láo zónghảo khàn"},
            {"爸爸", "ba4 ba5", "4 6", "ba bà"},
            {"弟弟", "di4 di5", "4 6", "ti tì"},
            {"谢谢", "xie4 xie5", "4 6", "shie shiè"},
            {"漂亮", "piao4 liang5", "4 6", "piao liàng"},
            {"意思", "yi4 si5", "4 6", "yi sừ"},
            {"认识", "ren4 shi5", "4 6", "rân shừ"},
            {"这个", "zhe4 ge5", "4 6", "zhưa kừa"},
            {"是的", "shi4 de5", "4 6", "shưtờ"},
            {"去吧", "qu4 ba5", "4 6", "chyubà"},
            {"展览馆", "zhan3 lan3 guan3", "2 2 3", "zhán lán kuản"},
            {"这样", "zhe4 yang4", "4 4", "zhưa yàng"},
            {"去看", "qu4 kan4", "4 4", "chyukhàn"},
            {"在这", "zai4 zhe4", "4 4", "zaizhừa"},
            {"小老虎", "xiao3 lao3 hu3", "3 2 3", "shiảo láo hủ"},
            {"纸老虎", "zhi3 lao3 hu3", "3 2 3", "zhử láo hủ"},
            {"汽车", "qi4 che1", "4 1", "chì chưa"},
            {"大家", "da4 jia1", "4 1", "tà zia"},
            {"面包", "mian4 bao1", "4 1", "mièn bao"},
            {"唱歌", "chang4 ge1", "4 1", "chàng kưa"},
            {"认真", "ren4 zhen1", "4 1", "rần zhân"},
            {"想想", "xiang3 xiang5", "2 0", "shiáng shiang"},
            {"特别", "te4 bie2", "4 2", "thừa bié"},
            {"问题", "wen4 ti2", "4 2", "wuần thí"},
            {"电脑", "dian4 nao3", "4 3", "tièn nảo"},
            {"跳舞", "tiao4 wu3", "4 3", "thiào wủ"},
            {"小姐", "xiao3 jie3", "2 3", "shiáo ziẻ"},
            {"地方", "di4 fang1", "4 1", "tì fang"},
    };

    private static MandarinReader reader;

    @BeforeClass
    public static void loadReader() throws Exception {
        reader = MandarinReadings.reader();
        assertNotNull(reader);
    }

    @Test
    public void acceptanceTableMatchesTheOwnerDecision() {
        assertEquals(55, ROWS.length);
        List<String> failures = new ArrayList<>();
        for (String[] row : ROWS) {
            MandarinReadingLine line = withReading(reader.read(row[0]), row[1]);
            String tones = tones(line);
            String eza = eza(line);
            if (!row[2].equals(tones) || !row[3].equals(eza)) {
                failures.add(row[0] + ": tones " + tones + " (want " + row[2] + "), eza "
                        + eza + " (want " + row[3] + ")");
            }
        }
        assertTrue(READING_GAP + "; " + failures, failures.isEmpty());
    }

    /** Replace a line's layer-1 syllables with an owner-confirmed reading; 5 keeps the real tone. */
    private static MandarinReadingLine withReading(MandarinReadingLine line, String reading) {
        String[] values = reading.trim().split("\\s+");
        int at = 0;
        List<MandarinToken> tokens = new ArrayList<>();
        for (MandarinToken token : line.tokens()) {
            if (!"han".equals(token.kind())) {
                tokens.add(token);
                continue;
            }
            int count = MandarinG2pM.points(token.text()).length;
            List<MandarinSyllable> own = new ArrayList<>(count);
            for (int index = 0; index < count; index++, at++) {
                String item = values[at];
                int digit = item.charAt(item.length() - 1) - '0';
                String letters = item.substring(0, item.length() - 1);
                MandarinSyllable original = token.syllables().get(index);
                boolean unavailable = original.isUnavailable();
                int real = digit;
                if (digit == 5 && !unavailable && original.letters().equals(letters)) {
                    real = original.tone();
                }
                boolean always = digit == 5 && !unavailable && original.alwaysNeutral();
                own.add(MandarinSyllable.fromData(letters, real, digit == 5, always, "phrase",
                        -1, -1, null, null));
            }
            tokens.add(new MandarinToken(token.text(), token.start(), token.end(), token.kind(),
                    token.tag(), token.subwords(), own));
        }
        return new MandarinReadingLine(line.text(), tokens);
    }

    /** Layer-2 digits for every syllable, in line order, separated by one space. */
    private static String tones(MandarinReadingLine line) {
        StringBuilder out = new StringBuilder();
        for (List<String> own : MandarinSandhi.surface(line)) {
            if (own == null) continue;
            for (String syllable : own) {
                if (out.length() > 0) out.append(' ');
                out.append(syllable.charAt(syllable.length() - 1));
            }
        }
        return out.toString();
    }

    /** Layer 4 per Han token, joined exactly as the owner's table writes it. */
    private static String eza(MandarinReadingLine line) {
        List<List<String>> surface = MandarinSandhi.surfaceAndRealize(line);
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < line.tokens().size(); index++) {
            List<String> own = surface.get(index);
            if (own == null) continue;
            for (int at = 0; at < own.size(); at++) {
                if (at > 0) out.append(' ');
                out.append(MandarinSpelling.spell(own.get(at), true));
            }
        }
        return out.toString();
    }

    @Test
    public void recordedReadingGapsStayRecorded() {
        // These gaps keep the model decision by policy; no per-word rule repairs them.
        assertReading("xing2", "行", reader.read("我去银行"));
        assertReading("ge4", "个", reader.read("一个人"));
        assertReading("he1", "喝", reader.read("吆喝"));
        // Documented gap: the pack reads 地 de5; the owner reading dì stays recorded, not patched.
        assertReading("de5", "地", reader.read("轻轻地"));
        assertReading("de2", "得", reader.read("开心得不得了"));
        assertReading("de2", "得", reader.read("想得睡不着"));
    }

    private static void assertReading(String expected, String character, MandarinReadingLine line) {
        for (MandarinToken token : line.tokens()) {
            String[] chars = MandarinG2pM.points(token.text());
            for (int at = 0; at < chars.length; at++) {
                if (!character.equals(chars[at])) continue;
                MandarinSyllable syllable = token.syllables().get(at);
                assertEquals(character + " in " + line.text(), expected, syllable.numbered());
                return;
            }
        }
        org.junit.Assert.fail(character + " missing from " + line.text());
    }

    @Test
    public void layerTwoRejectsRevisionTwoInput() {
        try {
            MandarinReader reader = MandarinReadings.reader();
            assertNotNull(reader);
            MandarinReadingLine line = reader.read("一");
            List<MandarinToken> tokens = new ArrayList<>();
            MandarinToken token = line.tokens().get(0);
            List<MandarinSyllable> syllables = new ArrayList<>();
            syllables.add(MandarinSyllable.fromData("yi", 2, false, "phrase", -1, -1, null, null));
            tokens.add(new MandarinToken(token.text(), token.start(), token.end(), "han",
                    token.tag(), token.subwords(), syllables));
            MandarinSandhi.surface(new MandarinReadingLine(line.text(), tokens));
            org.junit.Assert.fail("A stored sandhi 一 must be rejected at the layer-2 boundary.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("一 must be yi1"));
        }
    }

    @Test
    public void layerTwoRejectsATonelessNeutralWithAFullAlternative() {
        MandarinReadingLine line = reader.read("得");
        MandarinToken token = line.tokens().get(0);
        List<MandarinSyllable> syllables = new ArrayList<>();
        syllables.add(MandarinSyllable.fromData("de", 5, true, "phrase", -1, -1, null,
                List.of("de5", "de2")));
        List<MandarinToken> tokens = new ArrayList<>();
        tokens.add(new MandarinToken(token.text(), token.start(), token.end(), "han",
                token.tag(), token.subwords(), syllables));
        try {
            MandarinSandhi.surface(new MandarinReadingLine(line.text(), tokens));
            org.junit.Assert.fail("A neutral without its real tone must be rejected.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must carry its real tone"));
        }
    }
}
