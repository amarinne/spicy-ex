package com.eza.spicyex.lyrics.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Stable content digests for canonical and derived-layer identity. */
public final class Digests {
    /** ASCII unit separator: cannot occur in lyric text, so digest payloads stay unambiguous. */
    static final char SEP = 0x1f;

    private Digests() {
    }

    /** Full-length hex SHA-256, or a stable hashCode fallback if the algorithm is unavailable. */
    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(nz(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (Throwable ignored) {
            return "h" + Integer.toHexString(nz(value).hashCode());
        }
    }

    /** Short digest used inside stable row identifiers. */
    public static String shortHash(String value) {
        String full = sha256(value);
        return full.length() <= 8 ? full : full.substring(0, 8);
    }

    static String nz(String value) {
        return value == null ? "" : value;
    }

    // --- canonical serialization -------------------------------------------

    /**
     * Canonical JSON for identity digests: object keys sorted, array order preserved, text
     * normalized to NFC, and no insignificant whitespace.
     *
     * <p>Written to produce the same bytes as the desktop fork's {@code canonicalSerialize} for the
     * same input, so a digest computed on either side describes the same document. JavaScript's
     * {@code undefined} has no Java counterpart; the contract's "optional fields explicit as null"
     * rule is therefore carried by the callers, which put every optional key with an explicit null
     * value rather than leaving it out.
     *
     * @param value nested {@link Map} / {@link List} / {@link String} / integral {@link Number} /
     *              {@link Boolean} / null
     * @throws IllegalArgumentException on any other value type, which cannot be canonicalized
     */
    public static String canonicalJson(Object value) {
        StringBuilder out = new StringBuilder(128);
        appendCanonical(out, value, "$");
        return out.toString();
    }

    /** Full lowercase 64-hex SHA-256 over {@link #canonicalJson}. */
    public static String canonicalSha256(Object value) {
        return sha256(canonicalJson(value));
    }

    /** Unicode NFC, the normalization every digest input passes through. */
    public static String nfc(String value) {
        String text = nz(value);
        return Normalizer.isNormalized(text, Normalizer.Form.NFC)
                ? text : Normalizer.normalize(text, Normalizer.Form.NFC);
    }

    /**
     * Appends {@code value} as a JSON string literal, escaping exactly what JSON requires and
     * leaving every other character as itself. Shared so the identity digest and the wire request
     * cannot drift into two different escapings of the same text.
     */
    public static void appendJsonString(StringBuilder out, String value) {
        String text = nz(value);
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        out.append('"');
    }

    private static void appendCanonical(StringBuilder out, Object value, String path) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            appendJsonString(out, nfc((String) value));
        } else if (value instanceof Boolean) {
            out.append(((Boolean) value) ? "true" : "false");
        } else if (value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) {
            out.append(((Number) value).longValue());
        } else if (value instanceof List) {
            out.append('[');
            List<?> items = (List<?>) value;
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) out.append(',');
                appendCanonical(out, items.get(i), path + "[" + i + "]");
            }
            out.append(']');
        } else if (value instanceof Map) {
            Map<?, ?> source = (Map<?, ?>) value;
            List<String> keys = new ArrayList<>(source.size());
            for (Object key : source.keySet()) {
                if (!(key instanceof String)) {
                    throw new IllegalArgumentException("non-string key at " + path);
                }
                keys.add((String) key);
            }
            Collections.sort(keys);
            out.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) out.append(',');
                String key = keys.get(i);
                appendJsonString(out, nfc(key));
                out.append(':');
                appendCanonical(out, source.get(key), path + "." + key);
            }
            out.append('}');
        } else {
            throw new IllegalArgumentException(
                    "unsupported canonical value at " + path + ": " + value.getClass().getName());
        }
    }
}
