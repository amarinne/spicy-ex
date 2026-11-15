package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricResponseReader;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SelectionMode;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class LocalLyricsImportTest {
    private static final SpotifyTrack TRACK = new SpotifyTrack("Song", "Artist", "Album",
            "spotify:track:track1", 0, "", 0, "", 10000, false);
    private static final CatalogPolicy POLICY = new CatalogPolicy(Arrays.asList(SourceId.APPLE,
            SourceId.AMLL), false);
    private static final String XML = "<tt xml:lang=\"en\"><body><div>"
            + "<p begin=\"00:01.000\" end=\"00:04.000\">"
            + "<span begin=\"1s\" end=\"2s\">Hello</span> "
            + "<span begin=\"2s\" end=\"3s\">world</span>"
            + "<span ttm:role=\"x-translation\" xml:lang=\"vi\">Xin chào</span>"
            + "</p></div></body></tt>";

    private static CatalogCandidate read(String xml) throws Exception {
        return LocalLyricsImport.read(TRACK,
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), 1000);
    }

    private static CatalogState imported(CatalogState state, CatalogCandidate candidate) {
        CatalogChange change = CatalogDecisions.importLocal(state, POLICY, candidate,
                CatalogTrack.of(TRACK, 1000));
        assertTrue(change.accepted);
        return CatalogDecisionsTest.apply(state, change);
    }

    @Test public void fileBecomesLocalAndReplaysTimingAndTranslation() throws Exception {
        CatalogCandidate candidate = read(XML);
        assertEquals(SourceId.LOCAL, candidate.sourceId);
        assertEquals(CatalogSource.MatchMethod.USER_IMPORT, candidate.matchMethod);
        CatalogState saved = imported(CatalogState.empty("track1"), candidate);
        assertEquals(SelectionMode.MANUAL, saved.selection.mode);
        CatalogCandidate replay = CatalogDecisions.render(saved, POLICY).winner;
        assertEquals(candidate.candidateId, replay.candidateId);
        LyricsDocument doc = LyricsCatalog.decode(replay).document;
        assertEquals("Local", doc.provider);
        assertEquals("local_ttml", doc.fetchSource);
        assertEquals(SourceId.LOCAL, CatalogSource.inferSourceId(doc.fetchSource, doc.provider));
        assertEquals("Hello world", doc.lines.get(0).text);
        assertEquals(1000, doc.lines.get(0).syllables.get(0).startMs);
        assertEquals(3000, doc.lines.get(0).syllables.get(1).endMs);
        assertEquals("Xin chào", doc.lines.get(0).providerTranslatedText);
        assertEquals(XML, CatalogCodec.inflate(candidate.rawPayload));
    }

    @Test public void exactByteBudgetAndUtf16BomAreAccepted() throws Exception {
        int padding = LyricResponseReader.LYRIC_BYTES - XML.getBytes(StandardCharsets.UTF_8).length - 7;
        CatalogCandidate full = read(XML + "<!--" + " ".repeat(padding) + "-->");
        assertEquals(LyricResponseReader.LYRIC_BYTES,
                CatalogCodec.inflate(full.rawPayload).getBytes(StandardCharsets.UTF_8).length);
        CatalogCandidate utf16 = LocalLyricsImport.read(TRACK,
                new ByteArrayInputStream(XML.getBytes(StandardCharsets.UTF_16)), 1000);
        assertEquals(read(XML).candidateId, utf16.candidateId);
    }

    @Test public void replacementOnlyReplacesLocalAndPinsNewFile() throws Exception {
        CatalogState state = CatalogDecisionsTest.success(CatalogState.empty("track1"), POLICY,
                CatalogDecisionsTest.apple("apple"));
        state = imported(state, read(XML));
        CatalogCandidate replacement = read(XML.replace("Hello", "Goodbye"));
        state = imported(state, replacement);
        assertEquals(2, state.candidates.size());
        assertNotNull(state.candidate(CatalogDecisionsTest.apple("apple").candidateId));
        assertEquals(replacement.candidateId, state.selection.candidateId);
        // Re-importing identical content must not delete the row written by the transaction.
        CatalogChange same = CatalogDecisions.importLocal(state, POLICY, replacement, null);
        assertTrue(same.deleteCandidateIds.isEmpty());
        assertEquals(2, CatalogDecisionsTest.apply(state, same).candidates.size());
    }

    @Test public void invalidOrOversizeFileLeavesPreviousPinIntact() throws Exception {
        CatalogState state = imported(CatalogState.empty("track1"), read(XML));
        for (String bad : Arrays.asList("", "<html/>", "<tt><p></p></tt>",
                "<tt><p>broken", "<tt><p begin=\"garbage\">Hello</p></tt>",
                "<tt><p begin=\"3s\" end=\"2s\">Hello</p></tt>",
                "<tt><p dur=\"2s\">Hello</p></tt>",
                "<tt>" + "<div>".repeat(65) + "<p>Deep</p>" + "</div>".repeat(65) + "</tt>", "<!DOCTYPE tt [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><tt><p>&x;</p></tt>")) {
            try {
                state = imported(state, read(bad));
                fail("Invalid input must fail before writing");
            } catch (Exception expected) { }
        }
        final boolean[] closed = {false};
        InputStream tooLarge = new InputStream() {
            int remaining = LyricResponseReader.LYRIC_BYTES + 1;
            @Override public int read() { return remaining-- > 0 ? ' ' : -1; }
            @Override public void close() { closed[0] = true; }
        };
        try {
            LocalLyricsImport.read(TRACK, tooLarge, 1000);
            fail("Oversize input must fail");
        } catch (IOException expected) { }
        assertTrue(closed[0]);
        assertEquals(1, state.candidates.size());
        assertEquals(read(XML).candidateId, state.selection.candidateId);
    }

    @Test public void otherTrackCannotReceiveAnImportedCandidate() throws Exception {
        CatalogChange change = CatalogDecisions.importLocal(CatalogState.empty("other"), POLICY,
                read(XML), null);
        assertFalse(change.accepted);
        assertTrue(change.writesNothing());
    }

    @Test public void removalReturnsToAutoAndKeepsRemoteLyrics() throws Exception {
        CatalogCandidate apple = CatalogDecisionsTest.apple("apple");
        CatalogState state = CatalogDecisionsTest.success(CatalogState.empty("track1"), POLICY, apple);
        state = imported(state, read(XML));
        state = CatalogDecisionsTest.apply(state,
                CatalogDecisions.remove(state, POLICY, state.selection.candidateId));
        assertEquals(SelectionMode.AUTO, state.selection.mode);
        assertEquals(apple.candidateId, CatalogDecisions.render(state, POLICY).winner.candidateId);
        assertEquals(1, state.candidates.size());
    }

    @Test public void localIsNeverAcquiredAndPinStopsAutomaticRequests() throws Exception {
        CatalogPolicy all = new CatalogPolicy(Arrays.asList(SourceId.values()), true);
        assertFalse(all.enabled(SourceId.LOCAL));
        assertFalse(AcquisitionPlanner.refreshAll(all).scope.allows(SourceId.LOCAL));
        assertFalse(AcquisitionPlanner.refreshAllInOrder(all).scope.allows(SourceId.LOCAL));
        assertTrue(new AcquisitionScope(Collections.singletonList(SourceId.LOCAL), true).isEmpty());
        CatalogState state = imported(CatalogState.empty("track1"), read(XML));
        assertFalse(AcquisitionPlanner.plan(state, all,
                CatalogDecisions.render(state, all), 2000, true).fetches());
    }

    @Test public void pickerOffersImportAndOnlyShowsStoredLocal() throws Exception {
        assertFalse(CatalogPickerModel.build(Collections.emptyList(), Collections.emptyMap(), null, null)
                .stream().anyMatch(row -> row.kind == CatalogPickerModel.RowKind.SOURCE
                        && row.sourceId == SourceId.LOCAL));
        CatalogState saved = imported(CatalogState.empty("track1"), read(XML));
        java.util.List<CatalogPickerModel.Row> rows = CatalogPickerModel.build(saved.candidates,
                saved.statuses(), saved.selection, CatalogDecisions.autoResolution(saved, POLICY), POLICY);
        assertTrue(rows.stream().anyMatch(row -> row.kind == CatalogPickerModel.RowKind.ACTION_IMPORT));
        assertTrue(rows.stream().anyMatch(row -> row.sourceId == SourceId.LOCAL
                && row.kind == CatalogPickerModel.RowKind.SOURCE && row.selected && !row.checkable));
    }
}
