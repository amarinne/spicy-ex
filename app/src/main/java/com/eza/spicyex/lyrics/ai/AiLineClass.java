package com.eza.spicyex.lyrics.ai;

/**
 * What kind of row this is, decided during enumeration and never revisited.
 *
 * <p>Deliberately narrow: only rows the classifier is certain about leave {@link #ORDINARY}.
 * Anything ambiguous stays ordinary, because a misclassified lyric line is a line the model is
 * asked the wrong question about, while a misclassified ad-lib costs nothing.
 */
public enum AiLineClass {
    /** A normal lyric line, a name, or a code-switched phrase. Sent; unchanged output allowed. */
    ORDINARY("ordinary"),
    /** An interjection. Sent; unchanged output allowed. */
    ADLIB("adlib"),
    /** A section heading, a musical note, or an empty row. Never sent; passed through locally. */
    STRUCTURAL("structural");

    /** The token as it appears on the wire and in identity payloads. */
    public final String token;

    AiLineClass(String token) {
        this.token = token;
    }
}
