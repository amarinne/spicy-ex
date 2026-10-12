package com.eza.spicyex.lyrics.session;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.ai.AiChunkFailure;
import com.eza.spicyex.lyrics.ai.AiFailureReason;
import com.eza.spicyex.lyrics.ai.AiLayerFailures;
import com.eza.spicyex.lyrics.ai.AiLayerPanelPolicy;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class AiFailureIndicatorProjectionTest {
    @Test public void googleFailureUsesOrdinaryRetryWithoutClaimingAiFailed() {
        LyricsDocument canonical = document();
        LyricSession session = LyricSession.of(CanonicalBase.fromDocument("track", canonical), 1);
        for (String detail : new String[]{"SocketTimeoutException", "parse_empty", "partial_parse", "cooldown"}) {
            LayerState failed = session.meaning.processing(LayerAuthority.MACHINE, "google", "", "run")
                    .failed(new LayerFailure(LayerFailure.Reason.UNAVAILABLE, detail, 0));
            LyricsDocument projected = LegacyDocumentComposer.compose(canonical, session.withMeaning(failed));
            assertTrue(projected.translationFailed);
            assertEquals("", projected.translationAiFailureToken);
            assertEquals(AiLayerPanelPolicy.Destination.COMPOSER,
                    AiLayerPanelPolicy.destination(false, projected.translationAiFailureToken, false));
        }
    }

    @Test public void googleFallbackDoesNotHideRealAiFailureOrUnknownDelivery() {
        LyricsDocument canonical = document();
        LyricSession session = LyricSession.of(CanonicalBase.fromDocument("track", canonical), 1);
        MeaningArtifact fallback = new MeaningArtifact(session.base.digest, "google",
                new LayerProvenance(LayerAuthority.MACHINE, "google", "google", 0),
                Collections.singletonList(new MeaningEntry(session.base.rows.get(0).rowId, "one", "en")), false);
        for (AiFailureReason reason : AiFailureReason.values()) {
            LayerState settled = session.meaning.processing(LayerAuthority.AI, "ai", "", "run")
                    .settled(fallback, AiLayerFailures.toLayerFailure(AiChunkFailure.of(reason)));
            LyricsDocument projected = LegacyDocumentComposer.compose(canonical, session.withMeaning(settled));
            assertEquals(LayerAuthority.MACHINE, settled.authority);
            assertEquals("one", projected.lines.get(0).translatedText);
            assertEquals(AiLayerFailures.token(reason), projected.translationAiFailureToken);
            assertEquals(AiLayerPanelPolicy.Destination.FAILURE,
                    AiLayerPanelPolicy.destination(false, projected.translationAiFailureToken, false));
        }
    }

    @Test public void googleRefreshFailureKeepsFallbackWithoutRedAiStatus() {
        LyricsDocument canonical = document();
        LyricSession session = LyricSession.of(CanonicalBase.fromDocument("track", canonical), 1);
        MeaningArtifact fallback = new MeaningArtifact(session.base.digest, "google",
                new LayerProvenance(LayerAuthority.MACHINE, "google", "google", 0),
                Collections.singletonList(new MeaningEntry(session.base.rows.get(0).rowId, "one", "en")), false);
        LayerState failed = session.meaning.withArtifact(LayerStatus.READY, fallback, "")
                .failed(new LayerFailure(LayerFailure.Reason.MALFORMED, "partial_parse", 0));
        LyricsDocument projected = LegacyDocumentComposer.compose(canonical, session.withMeaning(failed));
        assertEquals("one", projected.lines.get(0).translatedText);
        assertFalse(projected.translationFailed);
        assertEquals("", projected.translationAiFailureToken);
    }

    private static LyricsDocument document() {
        LyricsDocument document = new LyricsDocument();
        LyricsLine line = new LyricsLine();
        line.text = "ichi";
        document.lines.add(line);
        return document;
    }
}
