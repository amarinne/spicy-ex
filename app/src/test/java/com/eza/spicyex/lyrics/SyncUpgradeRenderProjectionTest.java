package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.blend.SyncUpgradeEngine;
import com.eza.spicyex.lyrics.catalog.CatalogDelivery;
import com.eza.spicyex.lyrics.catalog.CatalogSource.MatchMethod;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalSpanMapping;
import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingUnit;
import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingUnitKind;
import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;
import com.eza.spicyex.lyrics.reading.ReadingModels.TextRange;
import com.eza.spicyex.lyrics.reading.ReadingModels.TimedReadingUnit;
import com.eza.spicyex.lyrics.providers.SpicyOrgPolicy;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Uses the production row-planning and text-projection helpers without mounting Android views. */
public class SyncUpgradeRenderProjectionTest {
    @Test public void timelineAndBothSurfacePoliciesKeepAnchorGlyphsReadingsCreditsAndSilentTail() {
        LyricsDocument[] job = documents();
        SyncUpgradeEngine.Result result = SyncUpgradeEngine.upgrade("target", 90000, job[0], job[1], 1000000);
        assertEquals(3, result.upgradedRows);
        LyricsDocument composed = LyricsDocument.copyOf(job[0]);
        composed.lines.get(0).romanizedText = "Authoritative reading";
        composed.lines.get(0).translatedText = "Authoritative translation";
        composed.readingFromAi = true;
        LyricsDocument render = result.renderDocument(composed);
        LyricTimeline.applySyncedRows(render);
        AppliedLine row = render.appliedLines.stream().filter(r -> !r.dotLine).findFirst().get();
        assertEquals("[HELLO, sunshine!]", row.text);
        assertEquals("Authoritative reading", LyricsRowViewFactory.displayReading(row));
        assertEquals("Authoritative translation", row.translatedText);
        assertEquals(13000, LyricTimeline.fillEndMs(row));
        assertEquals(10900, row.words.get(1).endMs);
        assertEquals("Hello", row.words.get(0).sourceText);
        assertTrue(render.readingFromAi);
        assertTrue(SpicyOrgPolicy.isRestricted(render));
        assertEquals(job[0].spicyOrgRawPayload, render.spicyOrgRawPayload);
        assertEquals(job[0].spicyOrgFetchedAtMs, render.syncUpgradeProvenance.spicyOrgFetchedAtMs);
        for (boolean wrap : new boolean[]{true, false}) {
            LyricsSurfaceRowPlanner.SurfacePolicy policy = new LyricsSurfaceRowPlanner.SurfacePolicy(
                    1f, true, true, "romaji", true, false, false, false,
                    false, "Medium", "spotify", 1f, false, wrap, false);
            LyricsSurfaceRowPlanner.RowPlan plan = LyricsSurfaceRowPlanner.plan(row, render, policy);
            List<String> chunks = new ArrayList<>();
            for (SyllableSegment span : plan.line.words) chunks.add(span.text);
            assertTrue(TimedTextRowProjection.exactlyReconstructs(chunks, plan.line.text));
            StringBuilder mountedText = new StringBuilder();
            for (TimedTextRowProjection.Chunk chunk : TimedTextRowProjection.project(chunks, plan.line.text)) {
                mountedText.append(chunk.text);
                if (chunk.spaceAfter) mountedText.append(' ');
            }
            assertEquals("[HELLO, sunshine!]", mountedText.toString());
            assertEquals("Authoritative reading", LyricsRowViewFactory.displayReading(plan.line));
            assertEquals("Authoritative translation", plan.line.translatedText);
        }
        assertTrue(job[0].lines.get(0).syllables.isEmpty());
        assertEquals(9500, job[1].lines.get(0).syllables.get(0).startMs);
    }

    @Test public void staleAnchorReadingOwnersUseExactReconstructionGateAndRetainWholeLineReading() {
        LyricsDocument[] job = documents();
        SyncUpgradeEngine.Result result = SyncUpgradeEngine.upgrade("target", 90000, job[0], job[1], 1000000);
        LyricsDocument composed = LyricsDocument.copyOf(job[0]);
        LyricsLine source = composed.lines.get(0);
        TextRange full = new TextRange(0, source.text.codePointCount(0, source.text.length()));
        String authoritative = "A composed authoritative reading";
        source.romanizedText = authoritative;
        source.readingRenderPlan = new RenderPlan("anchor-line",
                Collections.singletonList(new CanonicalSpanMapping("line", full)),
                Collections.singletonList(new ReadingUnit(full, authoritative, ReadingUnitKind.TRANSFORMED,
                        "whole-line", Collections.singletonList("line"))),
                Collections.singletonList(new TimedReadingUnit("line", full, authoritative, "whole-line")),
                authoritative, null);
        LyricsDocument render = result.renderDocument(composed);
        LyricTimeline.applySyncedRows(render);
        AppliedLine row = render.appliedLines.stream().filter(r -> !r.dotLine).findFirst().get();
        Map<String, TimedReadingUnit> byId = new HashMap<>();
        for (TimedReadingUnit unit : row.readingRenderPlan.timedReadingUnits) byId.put(unit.spanId, unit);
        List<String> candidateReading = new ArrayList<>();
        for (int i = 0; i < row.words.size(); i++) {
            candidateReading.add(LyricsRowViewFactory.romanizedWordText(row, row.words.get(i), i,
                    byId, new LyricsRowViewFactory.Options(), (r, span, text) -> "local fallback"));
        }
        // The production builder also requires this exact gate for attached and timed readings.
        // An old timed owner alone must not authorize a different local reading.
        assertFalse(TimedTextRowProjection.exactlyReconstructs(candidateReading,
                LyricsRowViewFactory.displayReading(row)));
        assertEquals(authoritative, LyricsRowViewFactory.displayReading(row));
        assertEquals("[HELLO, sunshine!]", row.text);
        assertEquals(2, row.words.size());
    }

    private static LyricsDocument[] documents() {
        LyricsDocument anchor = new LyricsDocument(), donor = new LyricsDocument();
        anchor.trackId = donor.trackId = "target";
        anchor.durationMs = 90000; donor.durationMs = 95000;
        anchor.type = "Line"; donor.type = "Word";
        anchor.fetchSource = "spicy_org"; anchor.provider = "Spicy Lyrics";
        anchor.spicyOrgSource = "spicy_lyrics"; anchor.spicyOrgFetchedAtMs = 999000;
        anchor.spicyOrgUploader = "Uploader"; anchor.spicyOrgRawPayload = "original-marked-payload";
        anchor.catalogDelivery = new CatalogDelivery(SourceId.SPICY_ORG, "target", MatchMethod.EXACT_SPOTIFY_ID, true);
        donor.fetchSource = "qq_music"; donor.provider = "QQ Music";
        donor.catalogDelivery = new CatalogDelivery(SourceId.QQ, "qq-1", MatchMethod.STRONG_SEARCH, true);
        String[] texts = {"Hello sunshine", "Welcome home", "Stay forever"};
        for (int i = 0; i < texts.length; i++) {
            LyricsLine a = new LyricsLine(), d = new LyricsLine();
            a.text = i == 0 ? "[HELLO, sunshine!]" : texts[i]; d.text = texts[i];
            a.startMs = 10000 + i * 15000; a.endMs = a.startMs + 3000;
            d.startMs = a.startMs - 500; d.endMs = d.startMs + 3000;
            String[] words = texts[i].split(" ");
            for (int j = 0; j < words.length; j++) {
                SyllableSegment span = new SyllableSegment();
                span.text = words[j]; span.startMs = d.startMs + j * 500;
                span.endMs = span.startMs + 400; span.totalMs = 400; span.boundaryAfter = true;
                d.syllables.add(span);
            }
            anchor.lines.add(a); donor.lines.add(d);
        }
        return new LyricsDocument[]{anchor, donor};
    }
}
