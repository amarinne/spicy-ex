package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SpicyOrgAttribution;
import com.eza.spicyex.lyrics.SyllableSegment;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CatalogSingleSourceTest {
    private static final String ID = "aaaaaaaaaaaaaaaaaaaaaa";
    private final long now = System.currentTimeMillis();
    private final SpotifyTrack track = new SpotifyTrack("Synthetic", "Fixture", "", "spotify:track:" + ID,
            0, "", 0, "", 90000, false);

    @Test public void cachedWordSourceDoesNotChangeTheSelectedLineSource() {
        FakeAndroidContext context = new FakeAndroidContext();
        CatalogPolicy before = CatalogPolicy.read(context);
        context.getSharedPreferences(com.eza.spicyex.SpotifyPlusConfig.PREFS_NAME, 0)
                .edit().putBoolean("lyrics_sync_upgrade", true).commit();
        assertEquals(before, CatalogPolicy.read(context));
        CatalogState state = state(false);
        CatalogCandidate anchor = state.candidates.get(0), donor = state.candidates.get(1);
        String beforeAnchor = anchor.normalizedDocument, beforeDonor = donor.normalizedDocument;
        LyricsCatalog.View selected = view(state);
        assertEquals(anchor.candidateId, selected.seatCandidateId());
        assertEquals("Line", selected.document.type);
        assertTrue(selected.document.lines.get(0).syllables.isEmpty());
        assertEquals(10000, selected.document.lines.get(0).startMs);
        assertEquals("Spotify", SpicyOrgAttribution.sourceLabel(selected.document));
        assertEquals(beforeAnchor, anchor.normalizedDocument);
        assertEquals(beforeDonor, donor.normalizedDocument);
        assertFalse(selected.plan.scope.sources.contains(CatalogSource.SourceId.NETEASE));
        CatalogSelection pin = new CatalogSelection(ID, CatalogSource.SelectionMode.MANUAL,
                donor.candidateId, donor.sourceId, donor.providerItemId, donor.canonicalDigest);
        LyricsCatalog.View direct = view(new CatalogState(ID, state.candidates, state.providers, pin, null, true));
        assertEquals(donor.candidateId, direct.seatCandidateId());
        assertFalse(direct.document.lines.get(0).syllables.isEmpty());
        assertEquals(9500, direct.document.lines.get(0).syllables.get(0).startMs);
        assertEquals("NetEase", SpicyOrgAttribution.sourceLabel(direct.document));
        assertEquals(beforeDonor, donor.normalizedDocument);
    }

    @Test public void gracePeriodKeepsOrgClockAndProviderCredits() {
        CatalogState state = state(true);
        LyricsCatalog.View view = view(state);
        LyricsDocument render = view.document;
        assertEquals(now - 22L * 86400000L, render.spicyOrgFetchedAtMs);
        assertEquals("spicy_org", render.fetchSource);
        assertEquals("marked raw response", render.spicyOrgRawPayload);
        assertEquals("Apple Music", SpicyOrgAttribution.sourceLabel(render));
        assertEquals("Lyrics from Apple Music", SpicyOrgAttribution.credits(render).get(0).label);
        assertEquals(1, SpicyOrgAttribution.credits(render).size());
        assertTrue(SpicyOrgAttribution.credits(render, false).isEmpty());
        assertEquals("Apple Music", render.provider);
        assertEquals(Collections.singletonList(CatalogSource.SourceId.SPICY_ORG), view.plan.scope.sources);
    }

    @Test public void communityKeepsContributorLinksWithoutRepeatingSources() {
        LyricsCatalog.View view = view(state(true));
        LyricsDocument render = view.document;
        render.spicyOrgSource = "spicy_lyrics";
        render.spicyOrgUploader = "uploader";
        render.spicyOrgUploaderUrl = "https://spicylyrics.org/uid/1";
        render.spicyOrgMaker = "maker";
        render.spicyOrgMakerUrl = "https://spicylyrics.org/uid/2";
        List<SpicyOrgAttribution.Credit> card = SpicyOrgAttribution.credits(render);
        List<SpicyOrgAttribution.Credit> footer = SpicyOrgAttribution.credits(render, false);
        assertEquals("Spicy Lyrics", SpicyOrgAttribution.sourceLabel(render));
        assertEquals(3, card.size());
        assertEquals("Lyrics from Spicy Lyrics", card.get(0).label);
        assertEquals(2, footer.size());
        assertEquals("uploaded by uploader", footer.get(0).label);
        assertEquals(render.spicyOrgUploaderUrl, footer.get(0).url);
        assertEquals("made by maker", footer.get(1).label);
        assertEquals(render.spicyOrgMakerUrl, footer.get(1).url);
    }

    @Test public void unreadableAvailablePrimaryCannotMaskReadableBackupOrBlockRepair() {
        CatalogState good = state(false);
        CatalogCandidate broken = new CatalogCandidate("broken", ID, CatalogSource.SourceId.APPLE,
                ID, CatalogSource.MatchMethod.EXACT_SPOTIFY_ID, .9, 0, CatalogSource.TimingLevel.LINE,
                true, true, false, false, false, false, false, "broken", "not-json", "[]", null, 1, 1, now);
        Map<CatalogSource.SourceId, ProviderRecord> records = new EnumMap<>(CatalogSource.SourceId.class);
        records.putAll(good.providers);
        records.put(broken.sourceId, ProviderRecord.notChecked(broken.sourceId).succeeded(now));
        CatalogState state = new CatalogState(ID, Arrays.asList(broken, good.candidates.get(0)), records,
                null, null, true);
        LyricsCatalog.View view = LyricsCatalog.view(ID, state, new CatalogPolicy(Arrays.asList(
                CatalogSource.SourceId.APPLE, CatalogSource.SourceId.SPOTIFY_NATIVE), false),
                now, true, true, 1L, "", 90000);
        assertEquals(CatalogSource.SourceId.SPOTIFY_NATIVE, view.seat().sourceId);
        assertNotNull(view.document);
        assertEquals(Collections.singletonList(CatalogSource.SourceId.APPLE), view.plan.scope.sources);
        assertEquals(2, state.candidates.size());
        assertEquals(CatalogSource.ProviderStatus.AVAILABLE, state.provider(broken.sourceId).status);
    }

    @Test public void removingAPrimaryDoesNotImmediatelyRedownloadItAsCorrupt() {
        CatalogState good = state(false);
        Map<CatalogSource.SourceId, ProviderRecord> records = new EnumMap<>(CatalogSource.SourceId.class);
        records.putAll(good.providers);
        records.put(CatalogSource.SourceId.APPLE,
                ProviderRecord.notChecked(CatalogSource.SourceId.APPLE).succeeded(now));
        CatalogState removed = new CatalogState(ID, good.candidates, records, null, null, true);
        LyricsCatalog.View view = LyricsCatalog.view(ID, removed, new CatalogPolicy(Arrays.asList(
                CatalogSource.SourceId.APPLE, CatalogSource.SourceId.SPOTIFY_NATIVE), false),
                now, true, true, 1L, "", 90000);
        assertEquals(CatalogSource.SourceId.SPOTIFY_NATIVE, view.seat().sourceId);
        assertFalse(view.plan.fetches());
        assertEquals(CatalogSource.ProviderStatus.AVAILABLE,
                view.state.provider(CatalogSource.SourceId.APPLE).status);
    }

    private LyricsCatalog.View view(CatalogState state) {
        return LyricsCatalog.view(ID, state, new CatalogPolicy(Arrays.asList(CatalogSource.SourceId.SPICY_ORG,
                CatalogSource.SourceId.SPOTIFY_NATIVE, CatalogSource.SourceId.NETEASE), false),
                now, true, true, 1L, "", 90000);
    }

    private CatalogState state(boolean org) {
        LyricsDocument anchor = new LyricsDocument(), donor = new LyricsDocument();
        anchor.trackId = donor.trackId = ID;
        anchor.durationMs = 90000; donor.durationMs = 95000;
        anchor.provider = org ? "Apple Music" : "Spotify"; donor.provider = "NetEase";
        anchor.fetchSource = org ? "spicy_org" : "spotify_native"; donor.fetchSource = "netease";
        anchor.type = "Line"; donor.type = "Syllable"; anchor.songWriters = "Writer";
        if (org) {
            anchor.spicyOrgSource = "apple_music";
            anchor.spicyOrgFetchedAtMs = now - 22L * 86400000L;
            anchor.spicyOrgRawPayload = "marked raw response";
        }
        String[] texts = {"Hello sunshine", "Welcome home", "Stay forever"};
        for (int i = 0; i < texts.length; i++) {
            long start = 10000 + i * 15000;
            LyricsLine a = new LyricsLine(), d = new LyricsLine();
            a.text = i == 0 ? "[HELLO, sunshine!]" : texts[i]; d.text = texts[i];
            a.startMs = start; a.endMs = start + 3000;
            d.startMs = start - 500; d.endMs = d.startMs + 3000;
            String[] words = texts[i].split(" ");
            for (int j = 0; j < words.length; j++) {
                SyllableSegment span = new SyllableSegment(); span.text = words[j];
                span.startMs = d.startMs + j * 500; span.endMs = span.startMs + 400;
                span.totalMs = 400; span.boundaryAfter = true; d.syllables.add(span);
            }
            anchor.lines.add(a); donor.lines.add(d);
        }
        CatalogSource.SourceId primary = org ? CatalogSource.SourceId.SPICY_ORG : CatalogSource.SourceId.SPOTIFY_NATIVE;
        CatalogCandidate a = CatalogAdapters.buildCandidate(primary, track, anchor,
                CatalogSource.MatchMethod.EXACT_SPOTIFY_ID, ID, "", 1, now);
        CatalogCandidate d = CatalogAdapters.buildCandidate(CatalogSource.SourceId.NETEASE, track, donor,
                CatalogSource.MatchMethod.STRONG_SEARCH, "donor", "", 1, now);
        assertNotNull(a); assertNotNull(d);
        Map<CatalogSource.SourceId, ProviderRecord> providers = new EnumMap<>(CatalogSource.SourceId.class);
        providers.put(primary, ProviderRecord.notChecked(primary).succeeded(now));
        providers.put(d.sourceId, ProviderRecord.notChecked(d.sourceId).succeeded(now));
        return new CatalogState(ID, Arrays.asList(a, d), providers, null, null, true);
    }
}
