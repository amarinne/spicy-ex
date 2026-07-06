package com.eza.spicyex.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class LyricQualityRankerTest {
    @Test
    public void officialSpicySyllableBeatsNativeLine() {
        assertTrue(score(spicy("Syllable", true, false)) > score(nativeDoc("Line")));
    }

    @Test
    public void officialSpicyLineBeatsNativeSynced() {
        assertTrue(score(spicy("Line", true, false)) > score(nativeDoc("Line")));
    }

    @Test
    public void spicyFetchSourceWinsOverMusixmatchProviderLabel() {
        assertTrue(score(spicyProvider("Line", true, false, "Musixmatch")) > score(nativeDoc("Line")));
    }

    @Test
    public void nativeSyncedBeatsSpicyStatic() {
        assertTrue(score(nativeDoc("Line")) > score(spicy("Static", true, false)));
    }

    @Test
    public void nativeSyncedBeatsPlainSpicyLine() {
        assertTrue(score(nativeDoc("Line")) > score(spicy("Line", false, false)));
    }

    @Test
    public void preferNativeSyncedOverSuspiciousSpicyStatic() {
        assertTrue(score(nativeDoc("Line")) > score(spicy("Static", false, true)));
    }

    @Test
    public void nativeStaticBeatsPoisonedSpicyStatic() {
        assertTrue(score(nativeDoc("Static")) > score(spicy("Static", true, true)));
    }

    @Test
    public void officialSpicyStaticBeatsLrclibStatic() {
        assertTrue(score(spicy("Static", true, false)) > score(lrclib("Static")));
    }

    @Test
    public void nativeStaticBeatsOfficialSpicyStatic() {
        assertTrue(score(nativeDoc("Static")) > score(spicy("Static", true, false)));
    }

    @Test
    public void lrclibSyncedBeatsSpicyStatic() {
        assertTrue(score(lrclib("Line")) > score(spicy("Static", true, false)));
    }

    @Test
    public void nativeSyncedBeatsLrclibSynced() {
        assertTrue(score(nativeDoc("Line")) > score(lrclib("Line")));
    }

    @Test
    public void nativeStaticBeatsPlainSpicyStatic() {
        assertTrue(score(nativeDoc("Static")) > score(spicy("Static", false, false)));
    }

    private static int score(LyricsDocument doc) {
        return LyricQualityRanker.score(doc);
    }

    private static LyricsDocument spicy(String type, boolean packed, boolean poisoned) {
        return spicyProvider(type, packed, poisoned, "Spicy Lyrics");
    }

    private static LyricsDocument spicyProvider(String type, boolean packed, boolean poisoned, String provider) {
        LyricsDocument doc = doc(type, "spicy_api", provider);
        doc.spicyPackedPayload = packed;
        doc.spicyPoisoned = poisoned;
        return doc;
    }

    private static LyricsDocument nativeDoc(String type) {
        return doc(type, "spotify_native_model", "Musixmatch");
    }

    private static LyricsDocument lrclib(String type) {
        return doc(type, "lrclib", "LRCLIB");
    }

    private static LyricsDocument doc(String type, String fetchSource, String provider) {
        LyricsDocument doc = new LyricsDocument();
        doc.type = type;
        doc.fetchSource = fetchSource;
        doc.provider = provider;
        LyricsLine line = new LyricsLine();
        line.text = "hello";
        doc.lines.add(line);
        return doc;
    }
}
