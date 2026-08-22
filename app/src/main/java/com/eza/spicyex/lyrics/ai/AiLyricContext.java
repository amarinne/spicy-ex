package com.eza.spicyex.lyrics.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Track metadata sent as reference context.
 *
 * <p>Reference only: it may steady a translation's register or disambiguate a title drop, and it is
 * never a source of lyric text. It is part of both the request and the document digest, so a record
 * produced while one album's context was attached is not reused when a different one is.
 */
public final class AiLyricContext {
    public static final AiLyricContext EMPTY = new AiLyricContext(null, null, null);

    /** Normalized, or null when absent — never an empty string. */
    public final String title;
    public final List<String> artists;
    public final String album;

    public AiLyricContext(String title, List<String> artists, String album) {
        this.title = normalizeText(title);
        List<String> names = new ArrayList<>();
        if (artists != null) {
            for (String artist : artists) {
                String normalized = normalizeText(artist);
                if (normalized != null) names.add(normalized);
            }
        }
        this.artists = Collections.unmodifiableList(names);
        this.album = normalizeText(album);
    }

    /** Null-safe: an absent context is the empty one, not a null reference. */
    public static AiLyricContext normalize(AiLyricContext context) {
        return context == null ? EMPTY : context;
    }

    private static String normalizeText(String value) {
        if (value == null) return null;
        String text = AiText.collapseWhitespace(AiText.trim(AiText.nfc(value)));
        return text.isEmpty() ? null : text;
    }

    /** Digest payload, with absent fields explicit as null. */
    public Map<String, Object> toDigestMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", title);
        out.put("artists", new ArrayList<Object>(artists));
        out.put("album", album);
        return out;
    }

    /** Wire form, in the contract's fixed key order. */
    void appendJson(StringBuilder out) {
        out.append("{\"title\":");
        appendNullable(out, title);
        out.append(",\"artists\":[");
        for (int i = 0; i < artists.size(); i++) {
            if (i > 0) out.append(',');
            com.eza.spicyex.lyrics.session.Digests.appendJsonString(out, artists.get(i));
        }
        out.append("],\"album\":");
        appendNullable(out, album);
        out.append('}');
    }

    private static void appendNullable(StringBuilder out, String value) {
        if (value == null) out.append("null");
        else com.eza.spicyex.lyrics.session.Digests.appendJsonString(out, value);
    }
}
