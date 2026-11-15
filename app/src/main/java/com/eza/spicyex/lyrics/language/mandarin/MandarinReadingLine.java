package com.eza.spicyex.lyrics.language.mandarin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.List;

/** Plain-data layer 1 of Mandarin eza式: one syllable per Han code point, exact ranges. */
public final class MandarinReadingLine {
    public static final String SCHEMA = "cjk-labs.zh.mandarin-reading.v1";

    private final String text;
    private final List<MandarinToken> tokens;

    MandarinReadingLine(String text, List<MandarinToken> tokens) {
        int length = MandarinG2pM.points(text).length;
        for (MandarinToken token : tokens) {
            List<MandarinSyllable> syllables = token.syllables();
            for (int at = 0; at < syllables.size(); at++) {
                MandarinSyllable syllable = syllables.get(at);
                if (!syllable.hasContext()) continue;
                int position = token.start() + at;
                // Segmentation has no reading authority: phrase evidence may span several
                // tokens and model evidence spans the whole line.
                if (syllable.contextStart() < 0
                        || syllable.contextStart() > position
                        || syllable.contextEnd() <= position
                        || syllable.contextEnd() > length) {
                    throw new IllegalArgumentException(
                            "Mandarin context range must contain its syllable inside the line.");
                }
            }
        }
        this.text = text;
        this.tokens = Collections.unmodifiableList(tokens);
    }

    public String text() {
        return text;
    }

    public List<MandarinToken> tokens() {
        return tokens;
    }

    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.addProperty("schema", SCHEMA);
        out.addProperty("text", text);
        JsonArray values = new JsonArray();
        for (MandarinToken token : tokens) values.add(token.toJson());
        out.add("tokens", values);
        return out;
    }
}
