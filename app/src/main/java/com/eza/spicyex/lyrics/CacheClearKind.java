package com.eza.spicyex.lyrics;

/** User-requested cache invalidation routed through the live lyrics session. */
public enum CacheClearKind {
    TRANSLATION,
    TRANSLITERATION,
    LYRICS_RESPONSE
}
