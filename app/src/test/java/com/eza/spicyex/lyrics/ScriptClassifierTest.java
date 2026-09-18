package com.eza.spicyex.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ScriptClassifierTest {
    @Test
    public void classifiesKanaAsJapaneseBeforeCjk() {
        assertEquals(ScriptClassifier.ScriptClass.JAPANESE, ScriptClassifier.classify("君のことが好き"));
    }

    @Test
    public void classifiesKanjiOnlyAsChinese() {
        assertEquals(ScriptClassifier.ScriptClass.CHINESE, ScriptClassifier.classify("我爱你"));
    }

    @Test
    public void classifiesHangulAsKorean() {
        assertEquals(ScriptClassifier.ScriptClass.KOREAN, ScriptClassifier.classify("사랑해"));
    }

    @Test
    public void classifiesCyrillicGreekAndIndic() {
        assertEquals(ScriptClassifier.ScriptClass.CYRILLIC, ScriptClassifier.classify("привет мир"));
        assertEquals(ScriptClassifier.ScriptClass.GREEK, ScriptClassifier.classify("καλημέρα"));
        assertEquals(ScriptClassifier.ScriptClass.INDIC, ScriptClassifier.classify("तुम ही हो"));
    }

    @Test
    public void classifiesLatinAndOther() {
        assertEquals(ScriptClassifier.ScriptClass.LATIN, ScriptClassifier.classify("hello world"));
        assertEquals(ScriptClassifier.ScriptClass.LATIN, ScriptClassifier.classify("écoute mon ami"));
        assertEquals(ScriptClassifier.ScriptClass.OTHER, ScriptClassifier.classify("1234 ♪"));
        assertEquals(ScriptClassifier.ScriptClass.OTHER, ScriptClassifier.classify(""));
    }

    @Test
    public void scriptCertainLanguagesSkipTheModel() {
        assertEquals(true, ScriptClassifier.isUnambiguous(ScriptClassifier.ScriptClass.JAPANESE));
        assertEquals(true, ScriptClassifier.isUnambiguous(ScriptClassifier.ScriptClass.KOREAN));
        assertEquals(true, ScriptClassifier.isUnambiguous(ScriptClassifier.ScriptClass.GREEK));
        assertEquals(false, ScriptClassifier.isUnambiguous(ScriptClassifier.ScriptClass.LATIN));
        assertEquals(false, ScriptClassifier.isUnambiguous(ScriptClassifier.ScriptClass.INDIC));
    }
}
