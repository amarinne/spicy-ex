package com.eza.spicyex.lyrics.ai;

/** One accepted row of output: the requested id and the text the model returned for it. */
public final class AiResponseItem {
    public final String id;
    public final String text;

    public AiResponseItem(String id, String text) {
        this.id = AiText.nz(id);
        this.text = AiText.nz(text);
    }
}
