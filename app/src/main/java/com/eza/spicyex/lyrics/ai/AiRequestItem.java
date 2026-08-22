package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.Digests;

/** One row on the wire: id, class, voice hint, canonical source, and optional baseline. */
public final class AiRequestItem {
    public final String id;
    public final AiLineClass lineClass;
    public final AiVoiceHint voice;
    public final String source;
    /** Deterministic/Google baseline, or the latest accepted output on a revision. Null omits it. */
    public final String previous;

    public AiRequestItem(String id, AiLineClass lineClass, AiVoiceHint voice, String source,
                         String previous) {
        this.id = AiText.nz(id);
        this.lineClass = lineClass == null ? AiLineClass.ORDINARY : lineClass;
        this.voice = voice;
        this.source = AiText.nz(source);
        this.previous = previous;
    }

    /** Bytes counted against the chunk's source budget: canonical text plus any baseline. */
    public int sourceUtf8Bytes() {
        return AiText.utf8Bytes(source) + (previous == null ? 0 : AiText.utf8Bytes(previous));
    }

    /**
     * Appends this item in the contract's fixed key order — {@code id}, {@code c}, {@code v},
     * {@code s}, then {@code p} only when present. These bytes are what a cache record stores and
     * a resume replays, so the order is part of the contract rather than a formatting choice.
     */
    void appendJson(StringBuilder out) {
        out.append("{\"id\":");
        Digests.appendJsonString(out, id);
        out.append(",\"c\":");
        Digests.appendJsonString(out, lineClass.token);
        out.append(",\"v\":");
        if (voice == null) out.append("null");
        else Digests.appendJsonString(out, voice.token);
        out.append(",\"s\":");
        Digests.appendJsonString(out, source);
        if (previous != null) {
            out.append(",\"p\":");
            Digests.appendJsonString(out, previous);
        }
        out.append('}');
    }
}
