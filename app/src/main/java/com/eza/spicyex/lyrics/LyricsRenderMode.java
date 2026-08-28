package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.session.LyricsSourcePolicy;

/** Shared visual timing mode. Documents without trusted timing never receive karaoke dimming. */
public final class LyricsRenderMode {
    private LyricsRenderMode() {
    }

    /**
     * True when every lyric row must remain fully lit. Unknown timing fails closed here because a
     * synthetic or malformed timeline must never make arbitrary static lines look unsung.
     */
    public static boolean isStatic(LyricsDocument document) {
        return document == null || !LyricsSourcePolicy.isSynced(document.type);
    }

    /** Preserve timing trust when a surface renders a projected document. */
    static void copyTimingType(LyricsDocument source, LyricsDocument projection) {
        if (projection == null) return;
        projection.type = source == null ? "Unknown" : source.type;
    }
}
