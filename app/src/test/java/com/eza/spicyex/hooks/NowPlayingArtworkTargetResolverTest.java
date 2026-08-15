package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class NowPlayingArtworkTargetResolverTest {
    @Test
    public void uniqueCoverAllowsArtworkButCanvasAlwaysFallsBack() {
        NowPlayingArtworkTargetResolver.Candidate cover = cover(10, 20, 210, 220);
        NowPlayingArtworkTargetResolver.Resolution coverResolution =
                NowPlayingArtworkTargetResolver.resolve(Collections.singletonList(cover));

        assertEquals(NowPlayingArtworkTargetResolver.Kind.COVER, coverResolution.kind);
        assertEquals(NowPlayingArtworkTargetResolver.OpenAction.ARTWORK,
                NowPlayingArtworkTargetResolver.openAction("Artwork", coverResolution));
        assertEquals(NowPlayingArtworkTargetResolver.OpenAction.FULLSCREEN,
                NowPlayingArtworkTargetResolver.openAction("Fullscreen", coverResolution));

        NowPlayingArtworkTargetResolver.Resolution canvas = NowPlayingArtworkTargetResolver.resolve(
                Arrays.asList(cover, new NowPlayingArtworkTargetResolver.Candidate(true, 0, 0, 200, 200)));
        assertEquals(NowPlayingArtworkTargetResolver.Kind.CANVAS, canvas.kind);
        assertEquals(NowPlayingArtworkTargetResolver.OpenAction.FULLSCREEN,
                NowPlayingArtworkTargetResolver.openAction("Artwork", canvas));
    }

    @Test
    public void distinctCoversAreAmbiguousButDuplicateBoundsAreOneTarget() {
        assertEquals(NowPlayingArtworkTargetResolver.Kind.AMBIGUOUS,
                NowPlayingArtworkTargetResolver.resolve(Arrays.asList(
                        cover(0, 0, 200, 200), cover(220, 0, 420, 200))).kind);
        assertEquals(NowPlayingArtworkTargetResolver.Kind.COVER,
                NowPlayingArtworkTargetResolver.resolve(Arrays.asList(
                        cover(0, 0, 200, 200), cover(0, 0, 200, 200))).kind);
    }

    @Test
    public void trackChangeMatrixIsEdgeTriggeredAndStayAware() {
        assertEquals(NowPlayingArtworkTargetResolver.TrackChangeAction.CLOSE_ARTWORK,
                NowPlayingArtworkTargetResolver.trackChangeAction(true, false,
                        NowPlayingArtworkTargetResolver.Kind.COVER,
                        NowPlayingArtworkTargetResolver.Kind.COVER));
        assertEquals(NowPlayingArtworkTargetResolver.TrackChangeAction.KEEP_ARTWORK,
                NowPlayingArtworkTargetResolver.trackChangeAction(true, true,
                        NowPlayingArtworkTargetResolver.Kind.COVER,
                        NowPlayingArtworkTargetResolver.Kind.COVER));
        assertEquals(NowPlayingArtworkTargetResolver.TrackChangeAction.TRANSFER_FULLSCREEN,
                NowPlayingArtworkTargetResolver.trackChangeAction(true, true,
                        NowPlayingArtworkTargetResolver.Kind.COVER,
                        NowPlayingArtworkTargetResolver.Kind.CANVAS));
        assertEquals(NowPlayingArtworkTargetResolver.TrackChangeAction.NONE,
                NowPlayingArtworkTargetResolver.trackChangeAction(true, true,
                        NowPlayingArtworkTargetResolver.Kind.CANVAS,
                        NowPlayingArtworkTargetResolver.Kind.CANVAS));
        assertEquals(NowPlayingArtworkTargetResolver.TrackChangeAction.CLOSE_ARTWORK,
                NowPlayingArtworkTargetResolver.trackChangeAction(true, true,
                        NowPlayingArtworkTargetResolver.Kind.COVER,
                        NowPlayingArtworkTargetResolver.Kind.NONE));
    }

    @Test
    public void firstUnavailableAfterCoverArmsBoundedGraceWithoutTrackChange() {
        NowPlayingArtworkTargetResolver.UnavailableGrace first =
                NowPlayingArtworkTargetResolver.unavailableGrace(
                        NowPlayingArtworkTargetResolver.Kind.COVER,
                        NowPlayingArtworkTargetResolver.Kind.NONE, false, 0);
        assertTrue(first.defer);
        assertEquals(2, first.retriesRemaining);
        NowPlayingArtworkTargetResolver.UnavailableGrace second =
                NowPlayingArtworkTargetResolver.unavailableGrace(
                        NowPlayingArtworkTargetResolver.Kind.COVER,
                        NowPlayingArtworkTargetResolver.Kind.AMBIGUOUS,
                        first.armed, first.retriesRemaining);
        NowPlayingArtworkTargetResolver.UnavailableGrace third =
                NowPlayingArtworkTargetResolver.unavailableGrace(
                        NowPlayingArtworkTargetResolver.Kind.COVER,
                        NowPlayingArtworkTargetResolver.Kind.NONE,
                        second.armed, second.retriesRemaining);
        NowPlayingArtworkTargetResolver.UnavailableGrace exhausted =
                NowPlayingArtworkTargetResolver.unavailableGrace(
                        NowPlayingArtworkTargetResolver.Kind.COVER,
                        NowPlayingArtworkTargetResolver.Kind.NONE,
                        third.armed, third.retriesRemaining);
        assertTrue(second.defer);
        assertTrue(third.defer);
        assertFalse(exhausted.defer);
    }

    @Test
    public void pendingCanvasTransferIsTrackAndSettleAware() {
        assertEquals(NowPlayingArtworkTargetResolver.PendingCanvasAction.CANCEL,
                NowPlayingArtworkTargetResolver.pendingCanvasAction(
                        true, "A", "B", false, 10, 400,
                        NowPlayingArtworkTargetResolver.Kind.CANVAS));
        assertEquals(NowPlayingArtworkTargetResolver.PendingCanvasAction.LAUNCH_FULLSCREEN,
                NowPlayingArtworkTargetResolver.pendingCanvasAction(
                        true, "A", "B", true, 10, 400,
                        NowPlayingArtworkTargetResolver.Kind.NONE));
        assertEquals(NowPlayingArtworkTargetResolver.PendingCanvasAction.WAIT,
                NowPlayingArtworkTargetResolver.pendingCanvasAction(
                        true, "A", "A", true, 399, 400,
                        NowPlayingArtworkTargetResolver.Kind.CANVAS));
        assertEquals(NowPlayingArtworkTargetResolver.PendingCanvasAction.LAUNCH_FULLSCREEN,
                NowPlayingArtworkTargetResolver.pendingCanvasAction(
                        true, "A", "A", true, 400, 400,
                        NowPlayingArtworkTargetResolver.Kind.CANVAS));
        assertEquals(NowPlayingArtworkTargetResolver.PendingCanvasAction.CANCEL,
                NowPlayingArtworkTargetResolver.pendingCanvasAction(
                        true, "A", "A", true, 400, 400,
                        NowPlayingArtworkTargetResolver.Kind.COVER));
    }

    private static NowPlayingArtworkTargetResolver.Candidate cover(
            int left, int top, int right, int bottom
    ) {
        return new NowPlayingArtworkTargetResolver.Candidate(false, left, top, right, bottom);
    }
}
