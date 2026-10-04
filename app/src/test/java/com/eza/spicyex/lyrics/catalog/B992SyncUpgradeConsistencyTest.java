package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SpicyOrgAttribution;
import com.eza.spicyex.lyrics.SyllableSegment;
import com.eza.spicyex.lyrics.blend.SyncUpgradeEngine;
import com.eza.spicyex.lyrics.catalog.CatalogSource.MatchMethod;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class B992SyncUpgradeConsistencyTest {
    private static final String ID = "aaaaaaaaaaaaaaaaaaaaaa";
    private static final long NOW = 1800000000000L;
    private static final SpotifyTrack TRACK = new SpotifyTrack("Synthetic", "Fixture", "",
            "spotify:track:" + ID, 0, "", 0, "", 90000, false);

    @Test public void candidateArrivalOrderAndCachedReplayKeepConfiguredDonorAndAnchorIdentity() {
        CatalogCandidate anchor = candidate(SourceId.APPLE, "apple-item", false);
        CatalogCandidate netease = candidate(SourceId.NETEASE, "netease-item", false);
        CatalogCandidate qq = candidate(SourceId.QQ, "qq-item", false);
        List<List<CatalogCandidate>> arrivals = Arrays.asList(
                Arrays.asList(anchor, netease, qq), Arrays.asList(anchor, qq, netease),
                Arrays.asList(netease, anchor, qq), Arrays.asList(netease, qq, anchor),
                Arrays.asList(qq, anchor, netease), Arrays.asList(qq, netease, anchor));
        for (List<CatalogCandidate> candidates : arrivals) {
            CatalogState state = state(candidates);
            for (SourceId preferred : Arrays.asList(SourceId.NETEASE, SourceId.QQ)) {
                CatalogPolicy policy = policy(preferred);
                LyricsCatalog.View fresh = view(state, policy);
                LyricsCatalog.View cached = view(state, policy);
                assertEquals(anchor.candidateId, fresh.seatCandidateId());
                assertEquals(anchor.candidateId, cached.seatCandidateId());
                assertNotNull(fresh.timingProjection);
                assertEquals(preferred.id, fresh.timingProjection.syncUpgradeProvenance.donorSource);
                assertEquals(fresh.timingProjection.syncUpgradeProvenance.donorCandidateId,
                        cached.timingProjection.syncUpgradeProvenance.donorCandidateId);
                LyricsDocument first = SyncUpgradeEngine.renderProjection(fresh.document, fresh.timingProjection);
                LyricsDocument replay = SyncUpgradeEngine.renderProjection(cached.document, cached.timingProjection);
                assertEquals("Apple Music + " + (preferred == SourceId.NETEASE ? "NetEase" : "QQ Music"),
                        SpicyOrgAttribution.sourceLabel(first));
                assertEquals("Apple Music", first.provider);
                assertEquals(SpicyOrgAttribution.sourceLabel(first), SpicyOrgAttribution.sourceLabel(replay));
                for (int i = 0; i < first.lines.size(); i++) {
                    assertEquals(fresh.document.lines.get(i).text, first.lines.get(i).text);
                    for (int j = 0; j < first.lines.get(i).syllables.size(); j++) {
                        assertEquals(first.lines.get(i).syllables.get(j).startMs,
                                replay.lines.get(i).syllables.get(j).startMs);
                        assertEquals(first.lines.get(i).syllables.get(j).endMs,
                                replay.lines.get(i).syllables.get(j).endMs);
                    }
                }
                assertEquals(anchor.normalizedDocument, fresh.seat().normalizedDocument);
            }
        }
    }

    @Test public void fullLineDonorCannotWinByInflatingTheAcceptedRowCount() {
        CatalogCandidate anchor = candidate(SourceId.APPLE, "apple-item", false);
        CatalogCandidate lineDonor = candidate(SourceId.NETEASE, "line-donor", true);
        CatalogCandidate wordDonor = candidate(SourceId.QQ, "word-donor", false);
        LyricsCatalog.View view = view(state(Arrays.asList(anchor, lineDonor, wordDonor)), policy(SourceId.NETEASE));
        assertNotNull(view.timingProjection);
        assertEquals(wordDonor.candidateId, view.timingProjection.syncUpgradeProvenance.donorCandidateId);
        assertEquals(anchor.candidateId, view.seatCandidateId());
        assertEquals(3, view.timingProjection.syncUpgradeProvenance.upgradedRows);
    }

    private static LyricsCatalog.View view(CatalogState state, CatalogPolicy policy) {
        return LyricsCatalog.view(ID, state, policy, NOW, true, true, 1L, "", 90000);
    }

    private static CatalogPolicy policy(SourceId preferred) {
        return new CatalogPolicy(Arrays.asList(SourceId.APPLE, preferred,
                preferred == SourceId.NETEASE ? SourceId.QQ : SourceId.NETEASE), true, false, true);
    }

    private static CatalogState state(List<CatalogCandidate> candidates) {
        Map<SourceId, ProviderRecord> records = new EnumMap<>(SourceId.class);
        for (CatalogCandidate candidate : candidates) {
            records.put(candidate.sourceId, ProviderRecord.notChecked(candidate.sourceId).succeeded(NOW));
        }
        return new CatalogState(ID, candidates, records, null, null, true);
    }

    private static CatalogCandidate candidate(SourceId source, String item, boolean wholeLine) {
        boolean anchor = source == SourceId.APPLE;
        LyricsDocument doc = new LyricsDocument();
        doc.trackId = ID; doc.durationMs = 90000;
        doc.type = anchor ? "Line" : "Word";
        doc.fetchSource = anchor ? "apple_music" : source == SourceId.NETEASE ? "netease" : "qq_music";
        doc.provider = anchor ? "Apple Music" : source == SourceId.NETEASE ? "NetEase" : "QQ Music";
        String[] texts = {"Hello sunshine", "Welcome home", "Stay forever"};
        for (int i = 0; i < texts.length; i++) {
            LyricsLine row = new LyricsLine(); row.text = texts[i];
            row.startMs = 10000 + i * 15000 - (anchor ? 0 : 500); row.endMs = row.startMs + 3000;
            if (!anchor) {
                String[] parts = wholeLine ? new String[]{row.text} : row.text.split(" ");
                for (int j = 0; j < parts.length; j++) {
                    SyllableSegment span = new SyllableSegment();
                    span.text = parts[j]; span.startMs = row.startMs + j * 500;
                    span.endMs = span.startMs + 400; span.totalMs = 400; span.boundaryAfter = true;
                    row.syllables.add(span);
                }
            }
            doc.lines.add(row);
        }
        CatalogCandidate candidate = CatalogAdapters.buildCandidate(source, TRACK, doc,
                anchor ? MatchMethod.EXACT_PROVIDER_MAPPING : MatchMethod.STRONG_SEARCH, item, "", 1, NOW);
        assertNotNull(candidate);
        return candidate;
    }
}
