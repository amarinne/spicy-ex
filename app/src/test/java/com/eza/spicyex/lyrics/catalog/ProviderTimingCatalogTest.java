package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;
import com.eza.spicyex.lyrics.catalog.CatalogSource.*;
import com.eza.spicyex.lyrics.session.CanonicalSourceCodec;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class ProviderTimingCatalogTest {
    private static LyricsDocument document(String source, long wordEnd) {
        LyricsDocument doc = new LyricsDocument();
        doc.fetchSource = source;
        doc.type = "Word";
        doc.durationMs = 10000;
        LyricsLine line = new LyricsLine();
        line.text = "hello";
        line.startMs = 1000;
        line.endMs = 2000;
        SyllableSegment word = new SyllableSegment();
        word.text = word.sourceText = "hello";
        word.startMs = 1000;
        word.endMs = wordEnd;
        line.syllables.add(word);
        doc.lines.add(line);
        return doc;
    }

    private static CatalogCandidate candidate(SourceId source, LyricsDocument doc, String id) {
        return new CatalogCandidate(id, "track", source, id, MatchMethod.STRONG_SEARCH,
                0.8, 0, TimingLevel.WORD, true, true, false, false, false, false, false,
                id, CanonicalSourceCodec.encode(doc, 1, id, 0), "[]", new byte[0], 2, 1, 0);
    }

    @Test public void invalidCachedTimingCannotRenderOrHoldIncumbency() {
        CatalogCandidate invalid = candidate(SourceId.QQ, document("qq_music", 9000), "bad");
        CatalogCandidate valid = candidate(SourceId.NETEASE, document("netease", 2000), "good");
        assertNull(LyricsCatalog.decode(invalid));
        assertEquals(valid, CatalogResolver.resolveWithIncumbent(Arrays.asList(invalid, valid),
                CatalogSelection.auto("track"), invalid).winner);
        CatalogSelection pin = new CatalogSelection("track", SelectionMode.MANUAL,
                invalid.candidateId, invalid.sourceId, invalid.providerItemId, invalid.canonicalDigest);
        CatalogResolver.Resolution result = CatalogResolver.resolve(Arrays.asList(invalid, valid), pin);
        assertEquals(valid, result.winner);
        assertTrue(result.temporary);
        assertEquals("bad", pin.candidateId);
        CatalogState pinnedState = new CatalogState("track", Arrays.asList(invalid, valid),
                null, pin, null, true);
        CatalogResolver.Resolution delivered = CatalogDecisions.render(pinnedState,
                new CatalogPolicy(Arrays.asList(SourceId.QQ, SourceId.NETEASE), false));
        assertEquals(valid, delivered.winner);
        assertTrue(delivered.temporary);
        assertEquals(pin, pinnedState.selection);
    }

    @Test public void invalidAvailableCacheIsEligibleForAcquisitionAgain() {
        CatalogCandidate invalid = candidate(SourceId.QQ, document("qq_music", 9000), "bad");
        CatalogState state = new CatalogState("track", Collections.singletonList(invalid),
                Collections.singletonMap(SourceId.QQ, new ProviderRecord(SourceId.QQ,
                        ProviderStatus.AVAILABLE, 1000, 1000, 1000, 0)), null, null, true);
        CatalogPolicy policy = new CatalogPolicy(Collections.singletonList(SourceId.QQ), false);
        assertTrue(AcquisitionPlanner.plan(state, policy, CatalogResolver.resolve(
                state.candidates, state.selection), 2000, false).fetches());
        assertEquals(1, state.candidates.size());
    }

    @Test public void otherProvidersRetainTheirCurrentTimingContract() {
        CatalogCandidate apple = candidate(SourceId.APPLE, document("remote", 9000), "apple");
        assertNotNull(LyricsCatalog.decode(apple));
        assertEquals(apple, CatalogResolver.resolve(Collections.singletonList(apple), null).winner);
    }

    @Test public void cachedArtificialPunctuationIsAttachedWithoutChangingWordTiming() {
        LyricsDocument doc = document("netease", 2000);
        doc.lines.get(0).text = "hello!";
        SyllableSegment punctuation = new SyllableSegment();
        punctuation.text = punctuation.sourceText = "!";
        punctuation.startMs = 2000;
        punctuation.endMs = 2001;
        doc.lines.get(0).syllables.add(punctuation);
        CanonicalSourceCodec.Record record = CanonicalSourceCodec.decode(
                CanonicalSourceCodec.encode(doc, 1, "d", 0));
        assertNotNull(record);
        assertEquals("hello!", record.document.lines.get(0).text);
        assertEquals(1, record.document.lines.get(0).syllables.size());
        assertEquals(2000, record.document.lines.get(0).syllables.get(0).endMs);
    }
    @Test public void reversedCachedPunctuationIsRejectedBeforeAttachment() {
        LyricsDocument doc = document("netease", 2000);
        SyllableSegment mark = new SyllableSegment();
        mark.text = mark.sourceText = "!";
        mark.startMs = 2000;
        mark.endMs = 1999;
        doc.lines.get(0).syllables.add(mark);
        assertNull(CanonicalSourceCodec.decode(CanonicalSourceCodec.encode(doc, 1, "d", 0)));
    }

}
