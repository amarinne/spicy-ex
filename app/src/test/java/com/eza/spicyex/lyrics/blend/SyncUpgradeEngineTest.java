package com.eza.spicyex.lyrics.blend;

import com.eza.spicyex.lyrics.BackgroundLine;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;
import com.eza.spicyex.lyrics.catalog.CatalogCandidate;
import com.eza.spicyex.lyrics.catalog.CatalogDelivery;
import com.eza.spicyex.lyrics.catalog.CatalogSource.MatchMethod;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import com.eza.spicyex.lyrics.catalog.CatalogSource.TimingLevel;
import com.eza.spicyex.lyrics.providers.SpicyOrgPolicy;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class SyncUpgradeEngineTest {
    private static final long NOW = 1800000000000L;
    private static final String ID = "synthetic";
    private static final String[] TEXTS = {"Hello sunshine", "Welcome home", "Stay forever"};

    @Test public void prototypeOffsetsRemainSupportedButClockDriftFallsBack() throws Exception {
        for (String fixture : new String[]{"offset", "drift", "missing-verse"}) {
            JsonObject job;
            try (InputStreamReader reader = new InputStreamReader(getClass().getResourceAsStream(
                    "/blend-sync/" + fixture + ".json"), StandardCharsets.UTF_8)) {
                job = JsonParser.parseReader(reader).getAsJsonObject();
            }
            LyricsDocument anchor = fixtureDocument(job.getAsJsonObject("anchor"), false);
            LyricsDocument donor = fixtureDocument(job.getAsJsonObject("donor"), true);
            SyncUpgradeEngine.Result result = blend(anchor, donor);
            assertEquals(fixture, fixture.equals("drift") ? 0 : fixture.equals("missing-verse") ? 4 : 3,
                    result.upgradedRows);
            assertEquals(fixture, "", result.rejectionReason);
            assertEquals(fixture, job.getAsJsonObject("anchor").getAsJsonArray("lines").size(), result.document.lines.size());
            if (fixture.equals("drift")) {
                assertTrue(result.document.lines.get(0).derivedSyllables.isEmpty());
            }
            if (fixture.equals("missing-verse")) {
                assertEquals("no_exact_match", result.decisions.get(2).reason);
                assertEquals(5500, result.decisions.get(3).offsetMs, .01);
            }
        }
    }

    @Test public void preservesAnchorAndAuthoredDonorAndSilentTail() {
        LyricsDocument[] job = job(TEXTS, 500, 1);
        job[0].provider = "Musixmatch";
        job[0].songWriters = "Songwriter";
        SyncUpgradeEngine.Result result = blend(job[0], job[1]);
        assertEquals(3, result.upgradedRows);
        assertTrue(job[0].lines.get(0).syllables.isEmpty());
        assertTrue(job[0].lines.get(0).derivedSyllables.isEmpty());
        assertEquals(9500, job[1].lines.get(0).syllables.get(0).startMs);
        assertEquals(10400, job[1].lines.get(0).syllables.get(1).endMs);
        LyricsLine row = result.document.lines.get(0);
        assertEquals(13000, row.endMs);
        assertTrue(row.syllables.isEmpty());
        assertEquals(10900, row.derivedSyllables.get(1).endMs);
        assertEquals("Musixmatch", result.document.provider);
        assertEquals("Songwriter", result.document.songWriters);
        assertEquals("Line", result.document.type);
        assertEquals("unknown_version", result.provenance.donorIdentity);
        assertEquals(64, result.provenance.donorDigest.length());
        LyricsDocument render = result.renderDocument();
        assertEquals("Syllable", render.type);
        assertEquals(2, render.lines.get(0).syllables.size());
        assertTrue(render.lines.get(0).syllables.get(0).boundaryProvenance.startsWith("sync_upgrade_v1:"));
        render.lines.get(0).syllables.get(0).startMs = 1;
        assertEquals(10000, row.derivedSyllables.get(0).startMs);
    }

    @Test public void clockDriftAndWrongEditCannotRescaleProviderTiming() {
        LyricsDocument[] job = job(TEXTS, 500, 1.02);
        SyncUpgradeEngine.Result result = blend(job[0], job[1]);
        assertEquals(0, result.upgradedRows);
        job[0].lines.remove(2); job[1].lines.remove(2);
        assertEquals(0, blend(job[0], job[1]).upgradedRows);
        job = job(TEXTS, 500, 1.2);
        assertEquals(0, blend(job[0], job[1]).upgradedRows);
    }

    @Test public void splitAndMergedDonorWrappingUsesActualSegmentStart() {
        LyricsDocument[] job = job(TEXTS, 500, 1);
        LyricsLine original = job[1].lines.remove(0);
        LyricsLine first = row("(Hello)", 9500, 9900);
        first.syllables.add(original.syllables.get(0));
        LyricsLine second = row("sunshine", 10000, 12500);
        second.syllables.add(original.syllables.get(1));
        job[1].lines.add(0, second); job[1].lines.add(0, first);
        assertEquals(3, blend(job[0], job[1]).upgradedRows);
        job = job(TEXTS, 500, 1);
        first = job[1].lines.get(0); second = job[1].lines.remove(1);
        first.text += " " + second.text; first.endMs = second.endMs;
        first.syllables.addAll(second.syllables);
        SyncUpgradeEngine.Result result = blend(job[0], job[1]);
        assertEquals(3, result.upgradedRows);
        assertEquals(25000, result.document.lines.get(1).derivedSyllables.get(0).startMs);
    }

    @Test public void syllableWithinWordPreservesEachInterval() {
        LyricsDocument[] job = job(TEXTS, 500, 1);
        LyricsLine row = job[1].lines.get(0);
        row.syllables.remove(0);
        row.syllables.add(0, segment("lo", 9650, 9900, true));
        row.syllables.add(0, segment("Hel", 9500, 9650, false));
        SyncUpgradeEngine.Result result = blend(job[0], job[1]);
        assertEquals(3, result.upgradedRows);
        assertEquals(3, result.document.lines.get(0).derivedSyllables.size());
        assertEquals("Hel", result.document.lines.get(0).derivedSyllables.get(0).text);
        assertEquals("lo ", result.document.lines.get(0).derivedSyllables.get(1).text);
    }

    @Test public void preservesPunctuationCaseAndCodepointRanges() {
        LyricsDocument[] job = job(TEXTS, 500, 1);
        job[0].lines.get(0).text = "[HELLO, sunshine!]";
        SyncUpgradeEngine.Result result = blend(job[0], job[1]);
        StringBuilder text = new StringBuilder();
        for (SyllableSegment span : result.document.lines.get(0).derivedSyllables) text.append(span.text);
        assertEquals("[HELLO, sunshine!]", text.toString());
        assertEquals("Hello", result.document.lines.get(0).derivedSyllables.get(0).sourceText);
        assertEquals(0, result.document.lines.get(0).derivedSyllables.get(0).canonicalStartCp);
    }

    @Test public void lexicalSpacesRemainSemanticIncludingKorean() {
        LyricsDocument[] job = job(new String[]{"Now here", "Welcome home", "Stay forever"}, 500, 1);
        LyricsLine row = job[1].lines.get(0);
        row.text = "Nowhere"; row.syllables.clear(); row.syllables.add(segment("Nowhere", 9500, 10400, true));
        assertEquals("no_exact_match", blend(job[0], job[1]).decisions.get(0).reason);
        assertEquals(2, blend(job[0], job[1]).upgradedRows);
        job = job(new String[]{"아버지가 방에 들어가신다", "Welcome home", "Stay forever"}, 500, 1);
        row = job[1].lines.get(0);
        row.text = "아버지 가방에 들어가신다";
        row.syllables.clear();
        row.syllables.add(segment("아버지", 9500, 9900, true));
        row.syllables.add(segment("가방에", 10000, 10400, true));
        row.syllables.add(segment("들어가신다", 10500, 10900, true));
        assertEquals("no_exact_match", blend(job[0], job[1]).decisions.get(0).reason);
        assertEquals(2, blend(job[0], job[1]).upgradedRows);
    }

    @Test public void cjkDisplaySpacesAndSupplementaryLettersKeepRanges() {
        LyricsDocument[] job = job(new String[]{"今夜は 星空を見よう", "明日は晴れる", "ここで待って"}, 500, 1);
        job[0].lines.get(0).text = "今夜は星空を見よう";
        for (int i = 1; i < 3; i++) {
            LyricsLine row = job[1].lines.get(i);
            row.syllables.clear();
            row.syllables.add(segment(row.text.substring(0, 3), row.startMs, row.startMs + 400, false));
            row.syllables.add(segment(row.text.substring(3), row.startMs + 500, row.startMs + 900, true));
        }
        assertEquals(3, blend(job[0], job[1]).upgradedRows);
        job = job(new String[]{"\uD801\uDC00hello sunshine", "Welcome home", "Stay forever"}, 500, 1);
        job[1].lines.get(0).text = "\uD801\uDC28hello sunshine";
        job[1].lines.get(0).syllables.get(0).text = "\uD801\uDC28hello";
        SyncUpgradeEngine.Result result = blend(job[0], job[1]);
        assertEquals(3, result.upgradedRows);
        assertEquals(7, result.document.lines.get(0).derivedSyllables.get(0).canonicalEndCp);
    }

    @Test public void casefoldExpansionCannotSplitOneAnchorCodepoint() {
        LyricsDocument[] job = job(new String[]{"Straße heute", "Welcome home", "Stay forever"}, 500, 1);
        LyricsLine row = job[1].lines.get(0);
        row.text = "Strasse heute"; row.syllables.clear();
        row.syllables.add(segment("Stra", 9500, 9600, false));
        row.syllables.add(segment("s", 9600, 9700, false));
        row.syllables.add(segment("se", 9700, 9900, true));
        row.syllables.add(segment("heute", 10000, 10400, true));
        assertEquals("unrepresentable_text_boundary", blend(job[0], job[1]).decisions.get(0).reason);
    }

    @Test public void boundariesInsideAuthoredDonorSegmentReject() {
        LyricsDocument[] job = job(TEXTS, 500, 1);
        LyricsLine row = job[1].lines.get(0);
        row.text += " again"; row.syllables.clear(); row.syllables.add(segment(row.text, 9500, 10400, true));
        assertEquals("no_exact_match", blend(job[0], job[1]).decisions.get(0).reason);
    }

    @Test public void repeatedDonorOrAnchorChorusNeverUsesNearestTimestamp() {
        LyricsDocument[] job = job(TEXTS, 500, 1);
        LyricsLine duplicate = LyricsLine.copyOf(job[1].lines.get(0));
        duplicate.startMs += 60000; duplicate.endMs += 60000;
        for (SyllableSegment span : duplicate.syllables) { span.startMs += 60000; span.endMs += 60000; }
        job[1].lines.add(duplicate);
        assertEquals("ambiguous_match", blend(job[0], job[1]).decisions.get(0).reason);
        job = job(new String[]{"Hello sunshine", "Hello sunshine", "Stay forever"}, 500, 1);
        job[1].lines.remove(1);
        SyncUpgradeEngine.Result result = blend(job[0], job[1]);
        assertEquals("ambiguous_match", result.decisions.get(0).reason);
        assertEquals("ambiguous_match", result.decisions.get(1).reason);
    }

    @Test public void donorOnlyVocalsNeitherInsertNorCorroborateAcrossGap() {
        LyricsDocument[] job = job(TEXTS, 500, 1);
        LyricsLine gap = row("[la la la]", 14000, 17000);
        gap.syllables.add(segment("la", 14000, 14400, true));
        gap.syllables.add(segment("la", 14500, 14900, true));
        gap.syllables.add(segment("la", 15000, 15400, true));
        job[1].lines.add(1, gap);
        SyncUpgradeEngine.Result result = blend(job[0], job[1]);
        assertEquals(2, result.upgradedRows);
        assertEquals(3, result.document.lines.size());
        assertEquals("uncorroborated_timing", result.decisions.get(0).reason);
        assertTrue(result.document.lines.get(0).derivedSyllables.isEmpty());
    }

    @Test public void rolesInterludesOverlapAndPunctuationAreBarriers() {
        for (int mode = 0; mode < 5; mode++) {
            LyricsDocument[] job = job(TEXTS, 500, 1);
            LyricsLine row = job[1].lines.get(1);
            if (mode == 0) row.interlude = true;
            if (mode == 1) row.syllables.get(0).bgWord = true;
            if (mode == 2) row.backgroundLines.add(new BackgroundLine());
            if (mode == 3) row.oppositeAligned = true;
            if (mode == 4) row.syllables.get(0).endMs = 25400;
            assertEquals("mode " + mode, 0, blend(job[0], job[1]).upgradedRows);
        }
        LyricsDocument[] job = job(TEXTS, 500, 1);
        LyricsLine row = job[1].lines.get(0);
        row.text = "Hello, sunshine";
        row.syllables.get(0).boundaryAfter = false;
        row.syllables.add(1, segment(",", 9900, 9950, true));
        assertEquals("no_exact_match", blend(job[0], job[1]).decisions.get(0).reason);
    }

    @Test public void mappedEndsRejectWithoutClippingAndNextRowNeverSuppliesWordEnd() {
        LyricsDocument[] job = job(TEXTS, 500, 1);
        job[0].lines.get(0).endMs = 10500;
        SyncUpgradeEngine.Result result = blend(job[0], job[1]);
        assertEquals("mapped_interval_out_of_bounds", result.decisions.get(0).reason);
        assertTrue(result.document.lines.get(0).derivedSyllables.isEmpty());
        assertEquals(10400, job[1].lines.get(0).syllables.get(1).endMs);
        job = job(TEXTS, 500, 1);
        assertEquals(10900, blend(job[0], job[1]).document.lines.get(0).derivedSyllables.get(1).endMs);
        assertEquals(25000, job[0].lines.get(1).startMs);
    }

    @Test public void lowInformationNeverSuppliesTemporalControl() {
        LyricsDocument[] job = job(new String[]{"la la la la", "Welcome home", "Stay forever"}, 500, 1);
        SyncUpgradeEngine.Result result = blend(job[0], job[1]);
        assertEquals("low_information", result.decisions.get(0).reason);
        assertEquals(2, result.upgradedRows);
    }

    @Test public void wrongIdentityWeakKaraokeDurationAndHealthReject() {
        LyricsDocument[] job = job(TEXTS, 500, 1);
        job[0].trackId = "other";
        assertEquals("track_identity_mismatch", blend(job[0], job[1]).rejectionReason);
        job = job(TEXTS, 500, 1);
        job[0].durationMs = 92000;
        assertEquals("unhealthy_anchor", blend(job[0], job[1]).rejectionReason);
        for (MatchMethod method : new MatchMethod[]{MatchMethod.WEAK, MatchMethod.KARAOKE_SUBSTITUTION, MatchMethod.STRONG_SEARCH}) {
            job = job(TEXTS, 500, 1);
            job[0].catalogDelivery = new CatalogDelivery(SourceId.SPOTIFY_NATIVE, ID, method, true);
            assertEquals("unverified_anchor_identity", blend(job[0], job[1]).rejectionReason);
        }
        for (MatchMethod method : new MatchMethod[]{MatchMethod.WEAK, MatchMethod.KARAOKE_SUBSTITUTION}) {
            job = job(TEXTS, 500, 1);
            job[1].catalogDelivery = new CatalogDelivery(SourceId.NETEASE, "donor-1", method, true);
            assertEquals("unverified_donor_identity", blend(job[0], job[1]).rejectionReason);
        }
        job = job(TEXTS, 500, 1);
        job[1].spicyPoisoned = true;
        assertEquals("unhealthy_donor", blend(job[0], job[1]).rejectionReason);
    }

    @Test public void appleRequiresExactBindingAndOrgRetainsOriginCreditMarksAndExpiry() {
        LyricsDocument[] job = job(TEXTS, 500, 1);
        job[0].fetchSource = "apple_music";
        job[0].catalogDelivery = new CatalogDelivery(SourceId.APPLE, "apple:123", MatchMethod.EXACT_PROVIDER_MAPPING, true);
        assertEquals(3, blend(job[0], job[1]).upgradedRows);
        job[0].catalogDelivery = new CatalogDelivery(SourceId.APPLE, "apple:123", MatchMethod.STRONG_SEARCH, true);
        assertEquals(0, blend(job[0], job[1]).upgradedRows);
        job = job(TEXTS, 500, 1);
        LyricsDocument anchor = job[0];
        anchor.fetchSource = "spicy_org"; anchor.provider = "Apple Music";
        anchor.spicyOrgSource = "apple_music"; anchor.spicyOrgFetchedAtMs = NOW - 123;
        anchor.spicyOrgUploader = "Uploader"; anchor.spicyOrgUploaderUrl = "https://spicylyrics.org/u/1";
        anchor.spicyOrgRawPayload = "{marked original response}";
        anchor.catalogDelivery = new CatalogDelivery(SourceId.SPICY_ORG, ID, MatchMethod.EXACT_SPOTIFY_ID, true);
        SyncUpgradeEngine.Result result = blend(anchor, job[1]);
        assertEquals(3, result.upgradedRows);
        assertEquals(anchor.spicyOrgFetchedAtMs, result.provenance.spicyOrgFetchedAtMs);
        assertEquals(anchor.spicyOrgRawPayload, result.renderDocument().spicyOrgRawPayload);
        assertEquals(anchor.spicyOrgUploaderUrl, result.renderDocument().spicyOrgUploaderUrl);
        assertTrue(SpicyOrgPolicy.expires(result.renderDocument(), anchor.spicyOrgFetchedAtMs + SpicyOrgPolicy.RETENTION_MS));
        anchor.spicyOrgFetchedAtMs = NOW - SpicyOrgPolicy.RETENTION_MS;
        assertEquals("invalid_spicy_org_origin", blend(anchor, job[1]).rejectionReason);
    }

    @Test public void catalogMetadataWorksForDecodedDocumentsAndCarriesExactProvenance() {
        LyricsDocument[] job = job(TEXTS, 500, 1);
        job[0].catalogDelivery = null; job[1].catalogDelivery = null;
        CatalogCandidate anchor = candidate(SourceId.SPOTIFY_NATIVE, "anchor-candidate", "anchor-digest", ID, MatchMethod.EXACT_SPOTIFY_ID, true);
        CatalogCandidate donor = candidate(SourceId.NETEASE, "donor-candidate", "donor-digest", "donor-1", MatchMethod.STRONG_SEARCH, true);
        SyncUpgradeEngine.Result result = SyncUpgradeEngine.upgrade(ID, 90000, job[0], job[1], anchor, donor, NOW);
        assertEquals(3, result.upgradedRows);
        assertEquals("anchor-candidate", result.provenance.anchorCandidateId);
        assertEquals("anchor-digest", result.provenance.anchorDigest);
        assertEquals("donor-candidate", result.provenance.donorCandidateId);
        assertEquals("donor-digest", result.provenance.donorDigest);
        assertEquals("netease", result.provenance.donorSource);
        donor = candidate(SourceId.NETEASE, "donor-candidate", "donor-digest", "donor-1", MatchMethod.STRONG_SEARCH, false);
        assertEquals("unhealthy_donor", SyncUpgradeEngine.upgrade(ID, 90000, job[0], job[1], anchor, donor, NOW).rejectionReason);
    }

    @Test public void overlayPreservesComposedLanguageAndRejectsChangedRowIdentity() {
        LyricsDocument[] job = job(TEXTS, 500, 1);
        SyncUpgradeEngine.Result result = blend(job[0], job[1]);
        LyricsDocument composed = LyricsDocument.copyOf(job[0]);
        composed.lines.get(0).romanizedText = "Reading";
        composed.lines.get(0).translatedText = "Translation";
        composed.includesTranslation = true;
        composed.readingFromAi = true;
        LyricsDocument overlay = result.renderDocument(composed);
        assertEquals("Reading", overlay.lines.get(0).romanizedText);
        assertEquals("Translation", overlay.lines.get(0).translatedText);
        assertTrue(overlay.readingFromAi);
        assertEquals(2, overlay.lines.get(0).syllables.size());
        assertTrue(composed.lines.get(0).syllables.isEmpty());
        composed.lines.get(1).text = "Changed words";
        overlay = result.renderDocument(composed);
        assertTrue(overlay.lines.get(0).syllables.isEmpty());
        assertNull(overlay.syncUpgradeProvenance);
        composed = LyricsDocument.copyOf(job[0]);
        composed.lines.get(1).startMs++;
        assertTrue(result.renderDocument(composed).lines.get(0).syllables.isEmpty());
    }

    @Test public void authoredAnchorTimingRemainsUnmodified() {
        LyricsDocument[] job = job(TEXTS, 500, 1);
        job[0].lines.get(1).syllables.add(segment("Welcome home", 25000, 26000, true));
        SyncUpgradeEngine.Result result = blend(job[0], job[1]);
        assertEquals("anchor_has_authored_timing", result.decisions.get(1).reason);
        assertEquals(0, result.upgradedRows);
        assertEquals(26000, result.document.lines.get(1).syllables.get(0).endMs);
    }

    @Test public void noisyOnsetsNeverMapBeforeTheAnchorAndClockDriftFallsBack() {
        LyricsDocument[] job = job(new String[]{"Hello sunshine", "Welcome home"}, 500, 1);
        for (int i = 0; i < 2; i++) {
            LyricsLine a = job[0].lines.get(i), d = job[1].lines.get(i);
            a.startMs = i * 10000; a.endMs = a.startMs + 3000;
            d.startMs = a.startMs + (i == 0 ? 100 : 200); d.endMs = d.startMs + 3000;
            for (int j = 0; j < d.syllables.size(); j++) {
                d.syllables.get(j).startMs = d.startMs + j * 500;
                d.syllables.get(j).endMs = d.startMs + j * 500 + 400;
            }
        }
        SyncUpgradeEngine.Result result = blend(job[0], job[1]);
        assertEquals(2, result.upgradedRows);
        assertEquals(0, result.document.lines.get(0).derivedSyllables.get(0).startMs);
        assertEquals(10100, result.document.lines.get(1).derivedSyllables.get(0).startMs);
        job = job(TEXTS, 500, 1.02);
        for (int i = 0; i < 3; i++) {
            LyricsLine a = job[0].lines.get(i), d = job[1].lines.get(i);
            a.startMs = 10000 + i * 4500; a.endMs = a.startMs + 3000;
            long start = (long) Math.rint((a.startMs - 500) / 1.03);
            d.startMs = start; d.endMs = start + 3000;
            for (int j = 0; j < d.syllables.size(); j++) {
                d.syllables.get(j).startMs = start + j * 500;
                d.syllables.get(j).endMs = start + j * 500 + 400;
            }
        }
        assertEquals(0, blend(job[0], job[1]).upgradedRows);
    }

    @Test public void malformedAndDerivedInputsRejectAndWordDonorStaysWord() {
        LyricsDocument[] job = job(TEXTS, 500, 1);
        job[1].type = "Word";
        SyncUpgradeEngine.Result result = blend(job[0], job[1]);
        assertEquals("Word", result.renderDocument().type);
        LyricsDocument derived = result.renderDocument();
        assertEquals("derived_input_not_provider_candidate", blend(derived, job[1]).rejectionReason);
        job = job(TEXTS, 500, 1);
        job[0].lines.get(0).syllables = null;
        result = blend(job[0], job[1]);
        assertEquals("invalid_row_interval", result.rejectionReason);
        assertNull(result.document);
        job = job(TEXTS, 500, 1);
        job[1].lines.get(0).syllables.get(0).endMs = 9500;
        assertEquals("invalid_segment_interval", blend(job[0], job[1]).rejectionReason);
    }

    private static CatalogCandidate candidate(SourceId source, String id, String digest, String item,
                                               MatchMethod method, boolean healthy) {
        return new CatalogCandidate(id, ID, source, item, method, 1, 0, TimingLevel.WORD,
                true, healthy, false, false, false, false, false, digest, "", "", null, 1, 1, NOW);
    }

    private static SyncUpgradeEngine.Result blend(LyricsDocument anchor, LyricsDocument donor) {
        return SyncUpgradeEngine.upgrade(ID, 90000, anchor, donor, NOW);
    }

    private static LyricsDocument[] job(String[] texts, long shift, double slope) {
        LyricsDocument anchor = document(false), donor = document(true);
        for (int i = 0; i < texts.length; i++) {
            long start = 10000 + i * 15000;
            long donorStart = (long) Math.rint((start - shift) / slope);
            anchor.lines.add(row(texts[i], start, start + 3000));
            LyricsLine d = row(texts[i], donorStart, donorStart + 3000);
            String[] words = texts[i].split(" ");
            for (int j = 0; j < words.length; j++) d.syllables.add(segment(words[j], donorStart + j * 500, donorStart + j * 500 + 400, true));
            donor.lines.add(d);
        }
        return new LyricsDocument[]{anchor, donor};
    }

    private static LyricsDocument document(boolean donor) {
        LyricsDocument d = new LyricsDocument();
        d.trackId = ID; d.durationMs = donor ? 95000 : 90000;
        d.type = donor ? "Syllable" : "Line";
        d.fetchSource = donor ? "netease" : "spotify_native";
        d.provider = donor ? "NetEase" : "Spotify";
        d.catalogDelivery = new CatalogDelivery(donor ? SourceId.NETEASE : SourceId.SPOTIFY_NATIVE,
                donor ? "donor-1" : ID, donor ? MatchMethod.STRONG_SEARCH : MatchMethod.EXACT_SPOTIFY_ID, true);
        return d;
    }

    private static LyricsLine row(String text, long start, long end) {
        LyricsLine row = new LyricsLine(); row.text = text; row.startMs = start; row.endMs = end; return row;
    }

    private static SyllableSegment segment(String text, long start, long end, boolean boundary) {
        SyllableSegment span = new SyllableSegment(); span.text = text; span.startMs = start; span.endMs = end;
        span.totalMs = end - start; span.boundaryAfter = boundary; return span;
    }

    private static LyricsDocument fixtureDocument(JsonObject json, boolean donor) {
        LyricsDocument document = document(donor);
        document.durationMs = json.get("durationMs").getAsLong();
        for (com.google.gson.JsonElement value : json.getAsJsonArray("lines")) {
            JsonObject r = value.getAsJsonObject();
            LyricsLine row = row(r.get("text").getAsString(), r.get("startMs").getAsLong(), r.get("endMs").getAsLong());
            if (r.has("interlude")) row.interlude = r.get("interlude").getAsBoolean();
            if (r.has("segments")) for (com.google.gson.JsonElement element : r.getAsJsonArray("segments")) {
                JsonObject s = element.getAsJsonObject();
                row.syllables.add(segment(s.get("text").getAsString(), s.get("startMs").getAsLong(), s.get("endMs").getAsLong(), s.get("boundaryAfter").getAsBoolean()));
            }
            document.lines.add(row);
        }
        return document;
    }
}
