package com.eza.spicyex.lyrics.language.mandarin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.List;

/** One token of the portable Mandarin reading contract. */
public final class MandarinToken {
    private final String text;
    private final int start;
    private final int end;
    private final String kind;
    private final String tag;
    private final List<String> subwords;
    private final List<MandarinSyllable> syllables;

    MandarinToken(String text, int start, int end, String kind, String tag,
                  List<String> subwords, List<MandarinSyllable> syllables) {
        this.text = text;
        this.start = start;
        this.end = end;
        this.kind = kind;
        this.tag = tag;
        this.subwords = subwords == null
                ? Collections.emptyList() : Collections.unmodifiableList(subwords);
        this.syllables = syllables == null
                ? Collections.emptyList() : Collections.unmodifiableList(syllables);
    }

    public String text() {
        return text;
    }

    /** Code-point range in the source line. */
    public int start() {
        return start;
    }

    public int end() {
        return end;
    }

    public String kind() {
        return kind;
    }

    /** jieba part-of-speech tag, or {@code x} when the token is not a dictionary word. */
    public String tag() {
        return tag;
    }

    public List<String> subwords() {
        return subwords;
    }

    public List<MandarinSyllable> syllables() {
        return syllables;
    }

    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.addProperty("text", text);
        JsonObject range = new JsonObject();
        range.addProperty("start", start);
        range.addProperty("end", end);
        out.add("range", range);
        out.addProperty("kind", kind);
        out.addProperty("tag", tag);
        JsonArray pieces = new JsonArray();
        for (String subword : subwords) pieces.add(subword);
        out.add("subwords", pieces);
        JsonArray values = new JsonArray();
        for (MandarinSyllable syllable : syllables) values.add(syllable.toJson());
        out.add("syllables", values);
        return out;
    }
}
