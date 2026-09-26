package com.eza.spicyex.lyrics;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a Chinese/Japanese timed segment may break across lines.
 *
 * <p>Han and kana text has no spaces: a line may break between almost any two characters, so a
 * long timed segment has to be able to continue on the current line and wrap part-way through,
 * like running text. Wrapping it as one unit instead pushes the whole segment to a fresh line and
 * leaves a ragged gap behind it. The exceptions are the usual kinsoku rules - closing punctuation,
 * small kana and the long-vowel mark never start a line, opening brackets never end one - plus
 * keeping embedded Latin words and numbers whole.
 *
 * <p>Korean is deliberately excluded: it is spaced, and like Apple Music it breaks at word
 * boundaries (CSS keep-all), never inside a word.
 */
public final class CjkLineBreak {
    private static final String NO_LINE_START =
            "、。，．,.・：；:;？！?!ー～〜…‥」』）)］]｝}〉》〕】〙〗”’\"'゛゜ゝゞヽヾ々〻"
                    + "ぁぃぅぇぉっゃゅょゎゕゖァィゥェォッャュョヮヵヶㇰㇱㇲㇳㇴㇵㇶㇷㇸㇹㇺㇻㇼㇽㇾㇿ";
    private static final String NO_LINE_END = "「『（(［[｛{〈《〔【〘〖“‘";

    private CjkLineBreak() {
    }

    /** True for a multi-character segment with Han or kana text that should flow character-wise. */
    public static boolean flowsByCharacter(String text) {
        if (text == null || text.codePointCount(0, text.length()) < 2) return false;
        if (SpicyTextDetection.itemKoreanTest(text) || SpicyTextDetection.containsRtl(text)) return false;
        return SpicyTextDetection.hasCjkIdeograph(text) || SpicyTextDetection.hasKana(text);
    }

    /**
     * Splits {@code text} into the smallest units a line may break between, as UTF-16
     * {start, end} ranges covering the whole string in order.
     */
    public static List<int[]> clusters(String text) {
        List<int[]> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        int start = 0;
        int previous = -1;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int next = i + Character.charCount(cp);
            boolean join = i > 0 && (NO_LINE_START.indexOf(cp) >= 0
                    || Character.isWhitespace(cp)
                    || NO_LINE_END.indexOf(previous) >= 0
                    || (isWordChar(cp) && isWordChar(previous)));
            if (!join && i > 0) {
                out.add(new int[]{start, i});
                start = i;
            }
            previous = cp;
            i = next;
        }
        out.add(new int[]{start, text.length()});
        return out;
    }

    private static boolean isWordChar(int cp) {
        return cp >= 0 && cp < 0x2E80 && Character.isLetterOrDigit(cp);
    }
}
