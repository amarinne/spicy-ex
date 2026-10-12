package com.eza.spicyex.hooks;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.LyricTimeline;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.LyricsRenderConfig;
import com.eza.spicyex.lyrics.language.ScriptClassifier;
import com.eza.spicyex.lyrics.language.SpicyJapaneseChineseProcessor;
import com.eza.spicyex.lyrics.processing.LyricsDocumentProcessor;
import com.eza.spicyex.lyrics.processing.LyricsTransliterationSession;
import com.eza.spicyex.lyrics.session.DetectionResult;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real preference reads, asynchronous publication, mounted routing, and cycle persistence. */
public class SoundCycleCompositionBehaviorTest {
    @Test public void chineseDetectionPublicationReplacesStaleJapaneseRouteBeforeCycling() {
        FakeAndroidContext context = new FakeAndroidContext();
        SettingsStore store = new SettingsStore(context);
        store.put(Settings.TRANSLITERATION_ENABLED, true);
        store.put(Settings.CHINESE_MODE, "cycle");
        store.put(Settings.LAST_CHINESE_CYCLE_MODE, "jyutping");
        store.put(Settings.JAPANESE_READING_MODE, "romaji_only");
        store.put(Settings.NATIVE_SPICY_ROMANIZATION, true);
        SpotifyPlusConfig settings = SpotifyPlusConfig.from(context);
        LyricsRenderConfig config = LyricsRenderConfig.read(null, settings);
        LyricsTransliterationSession session = new LyricsTransliterationSession(
                settings.get(Settings.NATIVE_SPICY_ROMANIZATION), config,
                null, settings.get(Settings.LAST_CHINESE_CYCLE_MODE), null, null);

        LyricsDocument mounted = document();
        mounted.lines.get(0).japaneseReading = new SpicyJapaneseChineseProcessor.JapaneseReading(
                mounted.lines.get(0).text, "stale reading", Collections.emptyList());
        LyricTimeline.applySyncedRows(mounted);
        AppliedLine active = mounted.appliedLines.get(0);
        assertEquals("ja", SoundModeCyclePolicy.readingLanguage(active, mounted));

        LyricsDocument published = document();
        DetectionResult chinese = DetectionResult.detected("", published.lines.get(0).text,
                ScriptClassifier.ScriptClass.CHINESE, "zh", .99);
        published.lines.get(0).detection = chinese;
        assertEquals(LyricsDocumentProcessor.DerivedMergeResult.CHANGED,
                LyricsDocumentProcessor.mergeDerivedPublication(mounted, published));
        assertSame(active, mounted.appliedLines.get(0));
        assertSame(chinese, active.sourceLine.detection);
        assertEquals("zh", SoundModeCyclePolicy.readingLanguage(active, mounted));

        // Start in visible Jyutping. Every tap rereads the persisted layer configuration.
        String[] modes = {"jyutping", "pinyin", "jyutping", "jyutping", "pinyin"};
        boolean[] visible = {false, true, true, false, true};
        for (int i = 0; i < modes.length; i++) {
            config = LyricsRenderConfig.read(null, settings);
            session.applyConfig(config);
            String language = SoundModeCyclePolicy.readingLanguage(active, mounted);
            LyricsTransliterationSession.CycleResult result = session.cycle(
                    "ja".equals(language), "zh".equals(language),
                    "ko".equals(language), "cy".equals(language));
            assertEquals("cn transliteration mode", result.reason);
            assertEquals("tap " + i, visible[i], result.showRomanization);
            assertEquals("tap " + i, modes[i], session.chineseMode());
            store.put(Settings.NATIVE_SPICY_ROMANIZATION, result.showRomanization);
            store.put(Settings.LAST_CHINESE_CYCLE_MODE, session.chineseMode());
            assertEquals(modes[i], LyricsRenderConfig.read(null, settings).defaultChineseMode);
            assertEquals("cycle", settings.get(Settings.CHINESE_MODE));
            assertEquals("romaji_only", settings.get(Settings.JAPANESE_READING_MODE));
        }

        // A publication without detection must not erase evidence already on the mounted row.
        LyricsDocumentProcessor.mergeDerivedPublication(mounted, document());
        assertSame(chinese, active.sourceLine.detection);
        assertEquals("zh", SoundModeCyclePolicy.readingLanguage(null, mounted));
        AppliedLine english = new AppliedLine(); english.text = "Tonight we sing";
        assertEquals("zh", SoundModeCyclePolicy.readingLanguage(english, mounted));
        AppliedLine japanese = new AppliedLine(); japanese.text = "君の声";
        assertEquals("ja", SoundModeCyclePolicy.readingLanguage(japanese, mounted));
    }

    private static LyricsDocument document() {
        LyricsDocument document = new LyricsDocument();
        document.trackId = "chinese-cycle";
        document.type = "Line";
        LyricsLine line = new LyricsLine();
        line.text = "阳光下的泡沫";
        line.startMs = 1000; line.endMs = 4000;
        document.lines.add(line);
        return document;
    }
}
