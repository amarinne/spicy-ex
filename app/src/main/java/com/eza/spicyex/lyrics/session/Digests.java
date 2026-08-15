package com.eza.spicyex.lyrics.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

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
}
