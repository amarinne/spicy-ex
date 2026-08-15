package com.eza.spicyex.lyrics.session;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Preferred session publication unit: a snapshot plus what changed.
 *
 * <p>A layer change names the rows it touched so a renderer can update those rows without
 * rebuilding the lyric timeline, remounting the window, or losing active-row and scroll state.
 * The compatibility composer may still fold an event into a whole {@code LyricsDocument} during
 * migration, but processors emit events first.
 */
public final class SessionEvent {
    public enum Kind { BASE_CHANGED, SOUND_CHANGED, MEANING_CHANGED, STATE_CHANGED }

    public final Kind kind;
    public final LyricSession session;
    /** Rows whose derived text changed. Empty means "not row-scoped" (base or state change). */
    public final Set<String> changedRowIds;

    private SessionEvent(Kind kind, LyricSession session, Set<String> changedRowIds) {
        this.kind = kind;
        this.session = session;
        this.changedRowIds = changedRowIds == null
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(changedRowIds));
    }

    public static SessionEvent baseChanged(LyricSession session) {
        return new SessionEvent(Kind.BASE_CHANGED, session, null);
    }

    public static SessionEvent stateChanged(LyricSession session) {
        return new SessionEvent(Kind.STATE_CHANGED, session, null);
    }

    public static SessionEvent layerChanged(LyricSession session, LayerKind layer, Set<String> rows) {
        return new SessionEvent(layer == LayerKind.MEANING ? Kind.MEANING_CHANGED : Kind.SOUND_CHANGED,
                session, rows);
    }

    public boolean isRowScoped() {
        return !changedRowIds.isEmpty();
    }
}
