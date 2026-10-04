package com.eza.spicyex.lyrics.blend;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;
import com.eza.spicyex.lyrics.catalog.CatalogDelivery;
import com.eza.spicyex.lyrics.catalog.CatalogSource.MatchMethod;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import org.junit.Test;
import static org.junit.Assert.*;

public class B992SyncUpgradeSafetyTest {
    @Test public void clockDriftCannotRescaleAuthoredDonorIntervals() {
        LyricsDocument[] docs = documents();
        for (int i = 0; i < docs[1].lines.size(); i++) shift(docs[1].lines.get(i), -300L * i);
        SyncUpgradeEngine.Result result = upgrade(docs);
        assertEquals(0, result.upgradedRows);
        assertEquals("Line", result.renderDocument().type);
        assertEquals(400, docs[1].lines.get(2).syllables.get(0).endMs
                - docs[1].lines.get(2).syllables.get(0).startMs);
    }

    @Test public void wholeLineSpansCannotAdvertiseAWordTimingUpgrade() {
        LyricsDocument[] docs = documents();
        for (LyricsLine row : docs[1].lines) {
            row.syllables.clear();
            row.syllables.add(span(row.text, row.startMs, row.startMs + 900));
        }
        SyncUpgradeEngine.Result result = upgrade(docs);
        assertEquals(0, result.upgradedRows);
        assertNull(result.provenance);
        assertTrue(result.renderDocument().lines.get(0).syllables.isEmpty());
    }

    @Test public void invalidMappedRowCannotCorroborateAnOtherwiseIsolatedNeighbor() {
        LyricsDocument[] docs = documents();
        docs[0].lines.remove(2); docs[1].lines.remove(2);
        docs[0].lines.get(0).endMs = docs[0].lines.get(0).startMs + 500;
        SyncUpgradeEngine.Result result = upgrade(docs);
        assertEquals(0, result.upgradedRows);
        assertEquals("mapped_interval_out_of_bounds", result.decisions.get(0).reason);
        assertEquals("uncorroborated_timing", result.decisions.get(1).reason);
    }

    @Test public void noisyCorroboratedOnsetsNeverAdvanceTheAnchor() {
        LyricsDocument[] docs = documents();
        // The old mean offset began the second row 50 ms before its authoritative onset.
        docs[0].lines.remove(2); docs[1].lines.remove(2);
        shift(docs[1].lines.get(1), -100);
        SyncUpgradeEngine.Result result = upgrade(docs);
        assertEquals(2, result.upgradedRows);
        assertEquals(10100, result.document.lines.get(0).derivedSyllables.get(0).startMs);
        assertEquals(25000, result.document.lines.get(1).derivedSyllables.get(0).startMs);
        assertEquals(400, result.document.lines.get(0).derivedSyllables.get(0).totalMs);
        shift(docs[1].lines.get(1), -21);
        assertEquals(0, upgrade(docs).upgradedRows);
    }

    @Test public void onsetUncertaintyNeverExtendsTimingPastTheAnchorEnd() {
        LyricsDocument[] docs = documents();
        docs[0].lines.remove(2); docs[1].lines.remove(2);
        // The old 120 ms row allowance accepted a word ending 50 ms after the anchor end.
        docs[0].lines.get(0).endMs = 10850;
        SyncUpgradeEngine.Result result = upgrade(docs);
        assertEquals(0, result.upgradedRows);
        assertEquals("mapped_interval_out_of_bounds", result.decisions.get(0).reason);
        assertEquals(10850, result.renderDocument().lines.get(0).endMs);
    }

    @Test public void missingRepeatedChorusNeverReusesAnOccurrenceOrNearestTimestamp() {
        LyricsDocument[] docs = documents();
        LyricsLine repeatedAnchor = LyricsLine.copyOf(docs[0].lines.get(0));
        repeatedAnchor.startMs = 55000; repeatedAnchor.endMs = 58000;
        docs[0].lines.add(repeatedAnchor);
        SyncUpgradeEngine.Result result = upgrade(docs);
        assertEquals("ambiguous_match", result.decisions.get(0).reason);
        assertEquals("ambiguous_match", result.decisions.get(3).reason);
        assertTrue(result.document.lines.get(0).derivedSyllables.isEmpty());
        assertTrue(result.document.lines.get(3).derivedSyllables.isEmpty());
        assertEquals(55000, result.renderDocument().lines.get(3).startMs);
        assertEquals(2, result.upgradedRows);
    }

    @Test public void asyncCompositionCannotOverwriteNewlyAuthoredAnchorSpans() {
        LyricsDocument[] docs = documents();
        SyncUpgradeEngine.Result result = upgrade(docs);
        assertEquals(3, result.upgradedRows);
        LyricsDocument newer = LyricsDocument.copyOf(docs[0]);
        newer.lines.get(0).syllables.add(span("Hello sunshine", 10000, 11500));
        LyricsDocument render = result.renderDocument(newer);
        assertNull(render.syncUpgradeProvenance);
        assertEquals(1, render.lines.get(0).syllables.size());
        assertEquals(11500, render.lines.get(0).syllables.get(0).endMs);
        assertTrue(render.lines.get(1).syllables.isEmpty());
    }

    @Test public void unchangedRowTextCannotAuthorizeTimingForADifferentProviderItem() {
        LyricsDocument[] docs = documents();
        SyncUpgradeEngine.Result result = upgrade(docs);
        LyricsDocument newer = LyricsDocument.copyOf(docs[0]);
        newer.catalogDelivery = new CatalogDelivery(SourceId.APPLE, "other-apple-item",
                MatchMethod.EXACT_PROVIDER_MAPPING, true);
        newer.fetchSource = "apple_music"; newer.provider = "Apple Music";
        // Use an Apple anchor with matching row text and row timing on both sides.
        docs[0].fetchSource = "apple_music"; docs[0].provider = "Apple Music";
        docs[0].catalogDelivery = new CatalogDelivery(SourceId.APPLE, "original-apple-item",
                MatchMethod.EXACT_PROVIDER_MAPPING, true);
        result = upgrade(docs);
        assertEquals(3, result.upgradedRows);
        LyricsDocument render = result.renderDocument(newer);
        assertNull(render.syncUpgradeProvenance);
        assertTrue(render.lines.get(0).syllables.isEmpty());
    }

    @Test public void acceptedOffsetPreservesEveryAuthoredDurationAndComposition() {
        LyricsDocument[] docs = documents();
        SyncUpgradeEngine.Result result = upgrade(docs);
        assertEquals(3, result.upgradedRows);
        LyricsDocument composed = LyricsDocument.copyOf(docs[0]);
        composed.lines.get(0).translatedText = "Current translation";
        composed.lines.get(0).romanizedText = "Current reading";
        LyricsDocument render = result.renderDocument(composed);
        assertEquals("Current translation", render.lines.get(0).translatedText);
        assertEquals("Current reading", render.lines.get(0).romanizedText);
        for (int i = 0; i < render.lines.size(); i++) {
            assertEquals("offset", result.decisions.get(i).transform);
            assertEquals(1, result.decisions.get(i).slope, 0);
            for (int j = 0; j < render.lines.get(i).syllables.size(); j++) {
                SyllableSegment before = docs[1].lines.get(i).syllables.get(j);
                SyllableSegment after = render.lines.get(i).syllables.get(j);
                assertEquals(before.endMs - before.startMs, after.endMs - after.startMs);
            }
        }
        assertTrue(docs[0].lines.get(0).syllables.isEmpty());
        assertEquals(9500, docs[1].lines.get(0).syllables.get(0).startMs);
    }

    private static SyncUpgradeEngine.Result upgrade(LyricsDocument[] docs) {
        return SyncUpgradeEngine.upgrade("target", 90000, docs[0], docs[1], 1000000);
    }

    private static LyricsDocument[] documents() {
        LyricsDocument anchor = new LyricsDocument(), donor = new LyricsDocument();
        anchor.trackId = donor.trackId = "target";
        anchor.durationMs = 90000; donor.durationMs = 95000;
        anchor.type = "Line"; donor.type = "Word";
        anchor.fetchSource = "spotify_native"; anchor.provider = "Spotify";
        anchor.catalogDelivery = new CatalogDelivery(SourceId.SPOTIFY_NATIVE, "target",
                MatchMethod.EXACT_SPOTIFY_ID, true);
        donor.fetchSource = "netease"; donor.provider = "NetEase";
        donor.catalogDelivery = new CatalogDelivery(SourceId.NETEASE, "donor",
                MatchMethod.STRONG_SEARCH, true);
        String[] texts = {"Hello sunshine", "Welcome home", "Stay forever"};
        for (int i = 0; i < texts.length; i++) {
            LyricsLine a = new LyricsLine(), d = new LyricsLine();
            a.text = d.text = texts[i];
            a.startMs = 10000 + i * 15000; a.endMs = a.startMs + 3000;
            d.startMs = a.startMs - 500; d.endMs = d.startMs + 3000;
            String[] words = texts[i].split(" ");
            for (int j = 0; j < words.length; j++) {
                d.syllables.add(span(words[j], d.startMs + j * 500, d.startMs + j * 500 + 400));
            }
            anchor.lines.add(a); donor.lines.add(d);
        }
        return new LyricsDocument[]{anchor, donor};
    }

    private static SyllableSegment span(String text, long start, long end) {
        SyllableSegment span = new SyllableSegment();
        span.text = text; span.startMs = start; span.endMs = end;
        span.totalMs = end - start; span.boundaryAfter = true;
        return span;
    }

    private static void shift(LyricsLine row, long delta) {
        row.startMs += delta; row.endMs += delta;
        for (SyllableSegment span : row.syllables) { span.startMs += delta; span.endMs += delta; }
    }
}
