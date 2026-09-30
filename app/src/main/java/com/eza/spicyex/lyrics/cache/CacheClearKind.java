package com.eza.spicyex.lyrics.cache;

/** User-requested cache invalidation routed through the live lyrics session. */
public enum CacheClearKind {
    TRANSLATION,
    TRANSLITERATION,
    AI,
    LYRICS_RESPONSE
}
