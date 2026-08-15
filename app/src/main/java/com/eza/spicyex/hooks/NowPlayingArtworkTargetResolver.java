package com.eza.spicyex.hooks;

import java.util.ArrayList;
import java.util.List;

/** Pure target and lifecycle decisions for the album-cover lyric overlay. */
final class NowPlayingArtworkTargetResolver {
    enum Kind { COVER, CANVAS, AMBIGUOUS, NONE }
    enum OpenAction { ARTWORK, FULLSCREEN }
    enum TrackChangeAction { KEEP_ARTWORK, CLOSE_ARTWORK, TRANSFER_FULLSCREEN, NONE }
    enum PendingCanvasAction { WAIT, CANCEL, LAUNCH_FULLSCREEN }

    static final class Candidate {
        final boolean video;
        final int left;
        final int top;
        final int right;
        final int bottom;

        Candidate(boolean video, int left, int top, int right, int bottom) {
            this.video = video;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    static final class Resolution {
        final Kind kind;
        final Candidate cover;

        Resolution(Kind kind, Candidate cover) {
            this.kind = kind;
            this.cover = cover;
        }
    }

    static final class UnavailableGrace {
        final boolean defer;
        final boolean armed;
        final int retriesRemaining;

        UnavailableGrace(boolean defer, boolean armed, int retriesRemaining) {
            this.defer = defer;
            this.armed = armed;
            this.retriesRemaining = retriesRemaining;
        }
    }

    private NowPlayingArtworkTargetResolver() {
    }

    static Resolution resolve(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return new Resolution(Kind.NONE, null);
        for (Candidate candidate : candidates) {
            if (candidate != null && candidate.video) return new Resolution(Kind.CANVAS, null);
        }
        List<Candidate> uniqueCovers = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.video || width(candidate) <= 0 || height(candidate) <= 0) continue;
            boolean duplicate = false;
            for (Candidate existing : uniqueCovers) {
                if (sameBounds(existing, candidate)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) uniqueCovers.add(candidate);
        }
        if (uniqueCovers.size() == 1) return new Resolution(Kind.COVER, uniqueCovers.get(0));
        if (uniqueCovers.size() > 1) return new Resolution(Kind.AMBIGUOUS, null);
        return new Resolution(Kind.NONE, null);
    }

    static OpenAction openAction(String configuredTarget, Resolution resolution) {
        return "Artwork".equals(configuredTarget)
                && resolution != null
                && resolution.kind == Kind.COVER
                ? OpenAction.ARTWORK
                : OpenAction.FULLSCREEN;
    }

    static TrackChangeAction trackChangeAction(
            boolean artworkVisible,
            boolean stayInLyrics,
            Kind previousStableTarget,
            Kind nextTarget
    ) {
        if (!artworkVisible) return TrackChangeAction.NONE;
        if (!stayInLyrics) return TrackChangeAction.CLOSE_ARTWORK;
        if (previousStableTarget == Kind.COVER && nextTarget == Kind.CANVAS) {
            return TrackChangeAction.TRANSFER_FULLSCREEN;
        }
        if (nextTarget == Kind.COVER) return TrackChangeAction.KEEP_ARTWORK;
        if (nextTarget == Kind.AMBIGUOUS || nextTarget == Kind.NONE) {
            return TrackChangeAction.CLOSE_ARTWORK;
        }
        return TrackChangeAction.NONE;
    }

    static UnavailableGrace unavailableGrace(
            Kind previousStableTarget,
            Kind nextTarget,
            boolean armed,
            int retriesRemaining
    ) {
        boolean unavailable = nextTarget == Kind.NONE || nextTarget == Kind.AMBIGUOUS;
        if (previousStableTarget != Kind.COVER || !unavailable) {
            return new UnavailableGrace(false, false, 0);
        }
        if (!armed) return new UnavailableGrace(true, true, 2);
        if (retriesRemaining > 0) {
            return new UnavailableGrace(true, true, retriesRemaining - 1);
        }
        return new UnavailableGrace(false, true, 0);
    }

    static PendingCanvasAction pendingCanvasAction(
            boolean pending,
            String originTrackId,
            String currentTrackId,
            boolean stayInLyrics,
            long elapsedMs,
            long settleMs,
            Kind currentTarget
    ) {
        if (!pending) return PendingCanvasAction.CANCEL;
        if (!originTrackId.equals(currentTrackId)) {
            return stayInLyrics ? PendingCanvasAction.LAUNCH_FULLSCREEN : PendingCanvasAction.CANCEL;
        }
        if (elapsedMs < settleMs) return PendingCanvasAction.WAIT;
        return currentTarget == Kind.CANVAS
                ? PendingCanvasAction.LAUNCH_FULLSCREEN
                : PendingCanvasAction.CANCEL;
    }

    private static int width(Candidate candidate) {
        return candidate.right - candidate.left;
    }

    private static int height(Candidate candidate) {
        return candidate.bottom - candidate.top;
    }

    private static boolean sameBounds(Candidate left, Candidate right) {
        return left.left == right.left && left.top == right.top
                && left.right == right.right && left.bottom == right.bottom;
    }
}
