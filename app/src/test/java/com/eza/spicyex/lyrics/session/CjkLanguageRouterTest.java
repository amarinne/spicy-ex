package com.eza.spicyex.lyrics.session;

import com.eza.spicyex.lyrics.ScriptClassifier;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class CjkLanguageRouterTest {
    @Test public void longUncertainHanVerseInheritsDocumentMajority() {
        CanonicalBase base = base("ja", "君の声", "你好世界今天我们一起唱歌", "この空");
        assertEquals("ja", ContextLanguageRouter.resolve(base, evidence(base, "ja", "", "ja"), null).get(1).language);
    }
    @Test public void longUncertainHanVerseInheritsChineseMajority() {
        CanonicalBase base = base("", "今天我们一起唱歌", "能否请你别遗弃一句爱你爱你", "风很温柔");
        List<DetectionResult> results = evidence(base, "zh", "", "zh");
        assertEquals("zh", ContextLanguageRouter.resolve(base, results, null).get(1).language);
    }
    @Test public void unresolvedHanUsesJapanesePassageContext() {
        CanonicalBase base = base("", "君の声", "東京", "この空");
        List<DetectionResult> results = evidence(base, "ja", "", "ja");
        assertEquals("ja", ContextLanguageRouter.resolve(base, results, null).get(1).language);
    }
    @Test public void chineseVerseOverridesJapaneseMetadataAndNeighbours() {
        CanonicalBase base = base("ja", "君の声", "今天我们一起唱歌", "この空");
        assertEquals("zh", ContextLanguageRouter.resolve(base, evidence(base, "ja", "zh", "ja"), null).get(1).language);
    }
    @Test public void kanaVerseOverridesChineseMetadata() {
        CanonicalBase base = base("zh", "你好", "君の声", "朋友");
        assertEquals("ja", ContextLanguageRouter.resolve(base, evidence(base, "zh", "ja", "zh"), null).get(1).language);
    }
    @Test public void conflictingNeighboursDoNotForceEitherReading() {
        CanonicalBase base = base("ja", "君の声", "漢字", "你好朋友");
        assertFalse(ContextLanguageRouter.resolve(base, evidence(base, "ja", "", "zh"), null).get(1).hasLanguage());
    }
    @Test public void metadataAloneDoesNotRelabelLatinOrUnresolvedHan() {
        CanonicalBase base = base("ja", "Hello", "漢字");
        List<DetectionResult> result = ContextLanguageRouter.resolve(base, evidence(base, "en", ""), null);
        assertEquals("en", result.get(0).language);
        assertFalse(result.get(1).hasLanguage());
    }
    @Test public void contextDoesNotConsumeAnOperationalError() {
        CanonicalBase base = base("ja", "君の声", "東京", "この空");
        List<DetectionResult> rows = evidence(base, "ja", "", "ja");
        rows.set(1, DetectionResult.error(base.rows.get(1).rowId, "東京", ScriptClassifier.ScriptClass.CHINESE));
        assertEquals(DetectionStatus.ERROR, ContextLanguageRouter.resolve(base, rows, null).get(1).status);
    }

    @Test public void aggregateLatinFillsShortRowsWhenDirectEvidenceDoesNotConflict() {
        CanonicalBase base = base("", "I know", "you know", "this is our song tonight");
        List<DetectionResult> rows = evidence(base, "", "", "en");
        DetectionResult aggregate = DetectionResult.detected("", base.joinedText(),
                ScriptClassifier.ScriptClass.LATIN, "en", .96);
        List<DetectionResult> resolved = ContextLanguageRouter.resolve(base, rows, aggregate);
        assertEquals("en", resolved.get(0).language);
        assertEquals("en", resolved.get(1).language);
    }

    @Test public void conflictingDirectLatinEvidenceBlocksGlobalInheritance() {
        CanonicalBase base = base("", "hello tonight", "amor", "bonjour mon ami");
        List<DetectionResult> rows = evidence(base, "en", "", "fr");
        DetectionResult aggregate = DetectionResult.detected("", base.joinedText(),
                ScriptClassifier.ScriptClass.LATIN, "en", .96);
        assertFalse(ContextLanguageRouter.resolve(base, rows, aggregate).get(1).hasLanguage());
    }
    private static List<DetectionResult> evidence(CanonicalBase base, String... languages) {
        List<DetectionResult> results = new ArrayList<>();
        for (int i = 0; i < languages.length; i++) {
            CanonicalRow row = base.rows.get(i);
            results.add(languages[i].isEmpty()
                    ? DetectionResult.scriptOnly(row.rowId, row.text, ScriptClassifier.classify(row.text))
                    : DetectionResult.detected(row.rowId, row.text, ScriptClassifier.classify(row.text), languages[i], .95));
        }
        return results;
    }
    private static CanonicalBase base(String language, String... rows) {
        List<CanonicalRow> values = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) values.add(new CanonicalRow(i * 2, rows[i], i * 1000L,
                i * 1000L + 500L, false, Collections.emptyList()));
        return new CanonicalBase("track", "id", language, "", "", "", 0L, values);
    }
}
