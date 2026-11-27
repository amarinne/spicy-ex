package com.eza.spicyex.lyrics.language;

import com.eza.spicyex.lyrics.language.mandarin.MandarinSpelling;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Resolves authored Mandarin phonetic syllables without inferring Han characters or word tones. */
public final class ZhuyinResolver {
    public static final String REVISION = "zhuyin-2026-11-18.2";
    public static final class Syllable {
        /** Original UTF-16 range, including authored tone marks. */
        public final int start, end;
        public final String numbered;
        Syllable(int start, int end, String numbered) {
            this.start = start; this.end = end; this.numbered = numbered;
        }
    }
    public static final class Part {
        public final int start, end;
        public final String text;
        Part(int start, int end, String text) {
            this.start = start; this.end = end; this.text = text;
        }
    }
    private static final Map<String, String> INVENTORY = new LinkedHashMap<>();
    static {
        String[] rows = {
            "b a o ai ei ao an en ang eng i ie iao ian in ing u",
            "p a o ai ei ao ou an en ang eng i ie iao ian in ing u",
            "m a o e ai ei ao ou an en ang eng i ie iao iu ian in ing u",
            "f a o ei ou an en ang eng u",
            "d a e ai ei ao ou an en ang eng ong i ie iao iu ian ing u uo ui uan un",
            "t a e ai ei ao ou an ang eng ong i ie iao ian ing u uo ui uan un",
            "n a e ai ei ao ou an en ang eng ong i ie iao iu ian in iang ing u uo uan ü üe",
            "l a o e ai ei ao ou an ang eng ong i ia ie iao iu ian in iang ing u uo uan un ü üe",
            "g a e ai ei ao ou an en ang eng ong u ua uo uai ui uan un uang",
            "k a e ai ei ao ou an en ang eng ong u ua uo uai ui uan un uang",
            "h a e ai ei ao ou an en ang eng ong u ua uo uai ui uan un uang",
            "j i ia ie iao iu ian in iang ing iong u ue uan un",
            "q i ia ie iao iu ian in iang ing iong u ue uan un",
            "x i ia ie iao iu ian in iang ing iong u ue uan un",
            "zh a e ai ei ao ou an en ang eng ong i u ua uo uai ui uan un uang",
            "ch a e ai ao ou an en ang eng ong i u ua uo uai ui uan un uang",
            "sh a e ai ei ao ou an en ang eng i u ua uo uai ui uan un uang",
            "r e ao ou an en ang eng ong i u uo ui uan un",
            "z a e ai ei ao ou an en ang eng ong i u uo ui uan un",
            "c a e ai ao ou an en ang eng ong i u uo ui uan un",
            "s a e ai ao ou an en ang eng ong i u uo ui uan un",
            "_ a o e ê ai ei ao ou an en ang eng er yi ya yo ye yao you yan yin yang ying yong wu wa wo wai wei wan wen wang weng yu yue yuan yun"
        };
        String[] initials = {"b","p","m","f","d","t","n","l","g","k","h","j","q","x","zh","ch","sh","r","z","c","s"};
        String[] symbols = {"ㄅ","ㄆ","ㄇ","ㄈ","ㄉ","ㄊ","ㄋ","ㄌ","ㄍ","ㄎ","ㄏ","ㄐ","ㄑ","ㄒ","ㄓ","ㄔ","ㄕ","ㄖ","ㄗ","ㄘ","ㄙ"};
        String[] finals = {"a","o","e","ê","ai","ei","ao","ou","an","en","ang","eng","er","i","ia","io","ie","iao","iou","ian","in","iang","ing","iong","u","ua","uo","uai","uei","uan","uen","uang","ueng","ü","üe","üan","ün"};
        String[] rhymes = {"ㄚ","ㄛ","ㄜ","ㄝ","ㄞ","ㄟ","ㄠ","ㄡ","ㄢ","ㄣ","ㄤ","ㄥ","ㄦ","ㄧ","ㄧㄚ","ㄧㄛ","ㄧㄝ","ㄧㄠ","ㄧㄡ","ㄧㄢ","ㄧㄣ","ㄧㄤ","ㄧㄥ","ㄩㄥ","ㄨ","ㄨㄚ","ㄨㄛ","ㄨㄞ","ㄨㄟ","ㄨㄢ","ㄨㄣ","ㄨㄤ","ㄨㄥ","ㄩ","ㄩㄝ","ㄩㄢ","ㄩㄣ"};
        for (String row : rows) {
            String[] cells = row.split(" ");
            String initial = cells[0];
            for (int i = 1; i < cells.length; i++) {
                String fin = cells[i];
                String pinyin = (initial.equals("_") ? "" : initial) + fin;
                String onset = "";
                for (int j = 0; j < initials.length; j++) if (initial.equals(initials[j])) onset = symbols[j];
                if (initial.equals("_")) {
                    if (fin.startsWith("yu")) fin = "ü" + fin.substring(2);
                    else if (fin.startsWith("yi")) fin = "i" + fin.substring(2);
                    else if (fin.startsWith("y")) fin = "i" + fin.substring(1);
                    else if (fin.startsWith("wu")) fin = "u" + fin.substring(2);
                    else if (fin.startsWith("w")) fin = "u" + fin.substring(1);
                } else if (initial.matches("j|q|x") && fin.startsWith("u")) fin = "ü" + fin.substring(1);
                if (fin.equals("iu")) fin = "iou";
                if (fin.equals("ui")) fin = "uei";
                if (fin.equals("un")) fin = "uen";
                if (fin.equals("ong")) fin = "ueng";
                if (fin.equals("i") && initial.matches("zh|ch|sh|r|z|c|s")) {
                    INVENTORY.put(onset, pinyin);
                    continue;
                }
                for (int j = 0; j < finals.length; j++) if (fin.equals(finals[j])) {
                    INVENTORY.put(onset + rhymes[j], pinyin);
                    break;
                }
            }
        }
    }
    private ZhuyinResolver() { }
    public static Map<String, String> inventory() { return Collections.unmodifiableMap(INVENTORY); }
    public static boolean isSymbol(int cp) {
        return cp >= 0x3100 && cp <= 0x312f || cp >= 0x31a0 && cp <= 0x31bf;
    }
    public static boolean contains(String text) {
        return text != null && text.codePoints().anyMatch(ZhuyinResolver::isSymbol);
    }
    private static int tone(char c) {
        return c == 'ˉ' ? 1 : c == 'ˊ' ? 2 : c == 'ˇ' ? 3 : c == 'ˋ' ? 4 : c == '˙' ? 5 : 0;
    }
    /** Some lyric providers write Han 一 for medial ㄧ, as in 晴天's ㄒ一. */
    private static boolean isMedialAlias(String text, int at) {
        return text.charAt(at) == '一' && at > 0
                && INVENTORY.containsKey(text.substring(at - 1, at) + "ㄧ");
    }
    private static boolean inRun(String text, int at) {
        char c = text.charAt(at);
        return isSymbol(c) || tone(c) != 0 || isMedialAlias(text, at);
    }

    /** Longest legal syllable first. An invalid run has no partial reading. */
    public static List<Syllable> resolve(String text) {
        List<Syllable> out = new ArrayList<>();
        if (text == null) return out;
        for (int i = 0; i < text.length();) {
            if (!inRun(text, i)) { i++; continue; }
            int end = i + 1;
            while (end < text.length() && inRun(text, end)) end++;
            List<Syllable> run = new ArrayList<>();
            int at = i;
            while (at < end) {
                int start = at;
                boolean neutral = text.charAt(at) == '˙';
                if (neutral) at++;
                int bodyEnd = at;
                if (bodyEnd < end && text.charAt(bodyEnd) >= 'ㄅ' && text.charAt(bodyEnd) <= 'ㄙ') bodyEnd++;
                if (bodyEnd < end && (text.charAt(bodyEnd) >= 'ㄧ' && text.charAt(bodyEnd) <= 'ㄩ'
                        || isMedialAlias(text, bodyEnd))) bodyEnd++;
                if (bodyEnd < end && text.charAt(bodyEnd) >= 'ㄚ' && text.charAt(bodyEnd) <= 'ㄥ') bodyEnd++;
                if (bodyEnd == at && bodyEnd < end && text.charAt(bodyEnd) == 'ㄦ') bodyEnd++;
                String reading = INVENTORY.get(text.substring(at, bodyEnd).replace('一', 'ㄧ'));
                if (reading == null) break;
                at = bodyEnd;
                int digit = neutral ? 5 : 1;
                if (at < end && tone(text.charAt(at)) > 0
                        && (text.charAt(at) != '˙' || at + 1 == end)) {
                    if (neutral) break;
                    digit = tone(text.charAt(at++));
                }
                run.add(new Syllable(start, at, reading + digit));
            }
            if (at == end && !run.isEmpty() && run.get(run.size() - 1).end == end) out.addAll(run);
            i = end;
        }
        return out;
    }

    public static List<Part> renderParts(String text, boolean tones, boolean eza,
                                          Function<String, String> fallback) {
        List<Part> parts = new ArrayList<>();
        int at = 0;
        for (Syllable syllable : resolve(text)) {
            if (at < syllable.start) add(parts, text, at, syllable.start,
                    fallback(text.substring(at, syllable.start), fallback));
            String value = eza ? MandarinSpelling.spell(syllable.numbered, tones)
                    : SpicyJapaneseChineseProcessor.formatAuthoredPinyin(syllable.numbered, tones);
            add(parts, text, syllable.start, syllable.end, value);
            at = syllable.end;
        }
        if (at < text.length()) add(parts, text, at, text.length(), fallback(text.substring(at), fallback));
        return parts;
    }
    private static String fallback(String source, Function<String, String> renderer) {
        // Invalid phonetic runs stay literal, including any contextual Han medial alias.
        StringBuilder out = new StringBuilder();
        int at = 0;
        for (int i = 0; i < source.length();) {
            if (!inRun(source, i)) { i++; continue; }
            if (at < i) out.append(renderGap(source.substring(at, i), renderer));
            int end = i + 1;
            while (end < source.length() && inRun(source, end)) end++;
            out.append(source, i, end);
            at = i = end;
        }
        if (at < source.length()) out.append(renderGap(source.substring(at), renderer));
        return out.toString();
    }
    private static String renderGap(String source, Function<String, String> renderer) {
        int start = 0, end = source.length();
        while (start < end && Character.isWhitespace(source.charAt(start))) start++;
        while (end > start && Character.isWhitespace(source.charAt(end - 1))) end--;
        if (start == end) return source;
        String value = renderer.apply(source.substring(start, end));
        return source.substring(0, start) + (value == null ? source.substring(start, end) : value)
                + source.substring(end);
    }
    private static void add(List<Part> parts, String source, int start, int end, String value) {
        if (value == null || value.isEmpty()) value = source.substring(start, end);
        if (!parts.isEmpty()) {
            Part previous = parts.get(parts.size() - 1);
            if (!previous.text.isEmpty() && Character.isLetter(previous.text.codePointBefore(previous.text.length()))
                    && Character.isLetter(value.codePointAt(0))
                    && (!previous.text.equals(source.substring(previous.start, previous.end))
                        || !value.equals(source.substring(start, end)))) value = " " + value;
        }
        parts.add(new Part(start, end, value));
    }
    public static String join(List<Part> parts) {
        StringBuilder out = new StringBuilder();
        for (Part part : parts) out.append(part.text);
        return out.toString();
    }
}
