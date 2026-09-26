package com.eza.spicyex.lyrics.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Candidate payload codecs. The normalized document reuses
 * {@link com.eza.spicyex.lyrics.session.CanonicalSourceCodec}: provider translations travel
 * inside it, generated reading/translation text never does.
 *
 * <p>Provider transliteration rides a separate slot because the document model reuses
 * {@code romanizedText} for generated Sound output; without the slot a reparse could not tell a
 * provider baseline from a stale generated string.
 */
public final class CatalogCodec {
    /** Bump when the transliteration slot or raw-payload envelope changes shape. */
    public static final int CODEC_VERSION = 1;

    private CatalogCodec() {
    }

    public static String encodeProviderTransliteration(List<String> lines) {
        JsonArray out = new JsonArray();
        if (lines != null) {
            for (String line : lines) out.add(line == null ? "" : line);
        }
        return out.toString();
    }

    /** Never null: corrupt or foreign payloads decode to an empty baseline. */
    public static List<String> decodeProviderTransliteration(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonArray()) return Collections.emptyList();
            List<String> out = new ArrayList<>();
            for (JsonElement element : parsed.getAsJsonArray()) {
                try {
                    out.add(element.isJsonNull() ? "" : element.getAsString());
                } catch (Throwable ignored) {
                    out.add("");
                }
            }
            return Collections.unmodifiableList(out);
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    /** Compresses a raw provider payload for the {@code raw_payload} blob. Never null. */
    public static byte[] deflate(String payload) {
        if (payload == null || payload.isEmpty()) return new byte[0];
        try {
            byte[] input = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(input.length / 2 + 16);
            try (DeflaterOutputStream zip = new DeflaterOutputStream(buffer)) {
                zip.write(input);
            }
            return buffer.toByteArray();
        } catch (IOException ignored) {
            return new byte[0];
        }
    }

    /** Restores {@link #deflate} output; corrupt input yields "" rather than throwing. */
    public static String inflate(byte[] compressed) {
        if (compressed == null || compressed.length == 0) return "";
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(compressed.length * 2);
            try (InflaterInputStream zip =
                         new InflaterInputStream(new ByteArrayInputStream(compressed))) {
                byte[] chunk = new byte[4096];
                int read;
                while ((read = zip.read(chunk)) >= 0) buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }
}
