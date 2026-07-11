package com.eza.spicyex.lyrics.reading;

import java.util.ArrayList;
import java.util.List;

import com.eza.spicyex.lyrics.reading.ReadingContracts.ScriptPartitioner;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.LanguageContext;
import com.eza.spicyex.lyrics.reading.ReadingModels.ScriptRun;
import com.eza.spicyex.lyrics.reading.ReadingModels.TextRange;

public final class DefaultScriptPartitioner implements ScriptPartitioner {
    private static String scriptOf(int cp) {
        if (Character.isWhitespace(cp)) return "Whitespace";
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        if (script == Character.UnicodeScript.HANGUL) return "Hangul";
        if (script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA) return "Kana";
        if (script == Character.UnicodeScript.HAN) return "Han";
        if (script == Character.UnicodeScript.LATIN) return "Latin";
        if (script == Character.UnicodeScript.CYRILLIC) return "Cyrillic";
        if (script == Character.UnicodeScript.GREEK) return "Greek";
        int type = Character.getType(cp);
        if (type >= Character.CONNECTOR_PUNCTUATION && type <= Character.OTHER_PUNCTUATION) return "Punctuation";
        if (type >= Character.MATH_SYMBOL && type <= Character.OTHER_SYMBOL) return "Punctuation";
        return "Other";
    }

    @Override
    public List<ScriptRun> partition(CanonicalLine line, LanguageContext context) {
        List<ScriptRun> runs = new ArrayList<>();
        int length = CodePointRanges.length(line.text);
        if (length == 0) return runs;
        int start = 0;
        String script = scriptOf(line.text.codePointAt(0));
        for (int offset = 1; offset <= length; offset++) {
            String next = offset < length
                    ? scriptOf(line.text.codePointAt(CodePointRanges.codePointOffsetToUtf16Index(line.text, offset)))
                    : null;
            if (!script.equals(next)) {
                runs.add(new ScriptRun(script, new TextRange(start, offset)));
                start = offset;
                script = next;
            }
        }
        return runs;
    }
}
