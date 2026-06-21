package com.eza.spicyex.lyrics;

import java.util.ArrayList;
import java.util.List;

/** Conservative readability word breaks for Hangul runs that lyrics often publish without spaces. */
final class SpicyKoreanSpacing {
    private static final String[][] FIXED_PHRASES = {
            {"안녕하세요", "안녕", "하세요"},
            {"안녕하십니까", "안녕", "하십니까"},
            {"감사합니다", "감사", "합니다"}
    };

    private static final String[] SPLIT_SUFFIXES = {
            "싶어요", "싶어", "싶다", "싶은", "싶고",
            "합니다", "하세요", "하십니까", "해요", "해서", "하고", "하면", "하니", "하지", "하죠", "하는", "하게", "하자",
            "거예요", "거에요", "거야", "거죠"
    };

    private SpicyKoreanSpacing() {
    }

    static List<String> splitRun(String run) {
        ArrayList<String> out = new ArrayList<>();
        splitInto(run, out);
        return out;
    }

    private static void splitInto(String run, List<String> out) {
        if (run == null || run.isEmpty()) return;
        for (String[] phrase : FIXED_PHRASES) {
            if (run.equals(phrase[0])) {
                out.add(phrase[1]);
                out.add(phrase[2]);
                return;
            }
            if (run.startsWith(phrase[0]) && run.length() > phrase[0].length()) {
                out.add(phrase[1]);
                out.add(phrase[2]);
                splitInto(run.substring(phrase[0].length()), out);
                return;
            }
        }
        for (String suffix : SPLIT_SUFFIXES) {
            if (!run.endsWith(suffix)) continue;
            int split = run.length() - suffix.length();
            if (split < 2) continue;
            splitInto(run.substring(0, split), out);
            out.add(suffix);
            return;
        }
        out.add(run);
    }
}
