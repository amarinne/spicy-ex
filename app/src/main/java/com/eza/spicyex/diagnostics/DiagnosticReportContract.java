package com.eza.spicyex.diagnostics;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Shared intake bounds and Spicy EX v2 category policy. */
public final class DiagnosticReportContract {
    public static final int DESCRIPTION_BYTES = 4_000;
    public static final int CLIENT_BODY_BYTES = 384 * 1024;
    public static final int MEDIA_METADATA_BYTES = 512;
    public static final int LYRIC_LINE_BYTES = 8 * 1024;
    public static final long DRAFT_TTL_MS = 30L * 60L * 1000L;
    public static final String DATA_POLICY_URL =
            "https://github.com/amarinne/spicy-ex/blob/main/DIAGNOSTIC_DATA_POLICY.md";
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> CATEGORIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "missing_wrong_lyrics", "timing", "translation", "transliteration_romanization",
            "fullscreen_renderer", "now_playing_card", "hyperglow_bridge", "crash_restart", "other"
    )));

    private DiagnosticReportContract() {
    }

    public static String newReportId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return reportIdFromBytes(bytes);
    }

    static String reportIdFromBytes(byte[] bytes) {
        if (bytes == null || bytes.length != 16) throw new IllegalArgumentException("128-bit input required");
        BigInteger value = new BigInteger(1, bytes);
        char[] encoded = new char[26];
        Arrays.fill(encoded, '0');
        BigInteger radix = BigInteger.valueOf(32L);
        for (int index = encoded.length - 1; index >= 0; index--) {
            BigInteger[] division = value.divideAndRemainder(radix);
            encoded[index] = ALPHABET.charAt(division[1].intValue());
            value = division[0];
        }
        return "R1-" + new String(encoded);
    }

    public static boolean validReportId(String value) {
        if (value == null || value.length() != 29 || !value.startsWith("R1-")) return false;
        for (int i = 3; i < value.length(); i++) {
            if (ALPHABET.indexOf(value.charAt(i)) < 0) return false;
        }
        return true;
    }

    public static boolean validCategory(String value) {
        return CATEGORIES.contains(value);
    }

    public static boolean validDescription(String value) {
        return value != null && !value.trim().isEmpty() && utf8Bytes(value) <= DESCRIPTION_BYTES;
    }

    public static int utf8Bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    public static String utf8Prefix(String value, int maxBytes) {
        if (value == null || maxBytes <= 0) return "";
        int index = 0;
        int bytes = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            int nextBytes = codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
            if (bytes + nextBytes > maxBytes) break;
            bytes += nextBytes;
            index += Character.charCount(codePoint);
        }
        return value.substring(0, index);
    }
}
