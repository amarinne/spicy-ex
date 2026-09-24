package com.eza.spicyex.lyrics;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

import static com.eza.spicyex.lyrics.LyricUtils.isBlank;

/** Exact compact request contract pinned to the requested official 6.3.15 protocol.
 *
 * <p>Retired: the spicylyrics.org remote is no longer queried. The live remote path is the
 * Apple Music (Lenerd) endpoint in {@link LyricsRepository}. This contract is kept only as a
 * byte-exact pin covered by tests.
 */
@Deprecated
final class SpicyLyricsRequestContract {
    static final String UPSTREAM_VERSION = "6.3.15";
    static final String SPICY_QUERY_URL = "https://api.spicylyrics.org/query";
    static final String SPICY_ORIGIN = "https://xpui.app.spotify.com";
    static final String SPICY_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Spotify/1.2.63 Chrome/132.0.6834.210 Electron/34.3.1 Safari/537.36";
    private static final MediaType JSON = MediaType.get("application/json");

    private SpicyLyricsRequestContract() {
    }

    /**
     * UTF-8 bytes of the exact compact lyrics query body:
     * {@code {"queries":[{"operation":"lyrics","variables":{"id":"<trackId>","auth":"SpicyLyrics-WebAuth"}}],"client":{"version":"6.3.15"}}}
     * Insertion order is fixed: queries → operation → variables → id → auth, then client → version.
     */
    static byte[] buildLyricsQueryBytes(String trackId) {
        StringBuilder out = new StringBuilder(160);
        out.append("{\"queries\":[{\"operation\":\"lyrics\",\"variables\":{\"id\":\"");
        appendJsonString(out, trackId);
        out.append("\",\"auth\":\"SpicyLyrics-WebAuth\"}}],\"client\":{\"version\":\"");
        appendJsonString(out, UPSTREAM_VERSION);
        out.append("\"}}");
        return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Full lyrics request: exact upstream 6.3.15 bytes plus the upstream header set. */
    static Request buildLyricsRequest(String trackId, String accessToken) {
        RequestBody body = RequestBody.create(buildLyricsQueryBytes(trackId), JSON);
        Request.Builder builder = new Request.Builder()
                .url(SPICY_QUERY_URL)
                .post(body)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Content-Type", "application/json")
                .header("X-mode", "2")
                .header("Origin", SPICY_ORIGIN)
                .header("Referer", SPICY_ORIGIN + "/")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "cross-site")
                .header("SpicyLyrics-Version", UPSTREAM_VERSION)
                .header("User-Agent", SPICY_USER_AGENT);
        if (hasUsableToken(true, accessToken)) builder.header("SpicyLyrics-WebAuth", "Bearer " + accessToken);
        return builder.build();
    }

    /** Same safe behavior as before: sendToken, nonblank, and not the {@code 0} sentinel. */
    static boolean hasUsableToken(boolean sendToken, String accessToken) {
        return sendToken && !isBlank(accessToken) && !"0".equals(accessToken);
    }

    /**
     * JS {@code JSON.stringify}-compatible string escaping: quote/backslash escapes, short
     * escapes for {@code \b \f \n \r \t}, lowercase four-hex-digit escapes (u00xx style) for
     * remaining controls, lone UTF-16 surrogates as lowercase ud800-style escapes, paired
     * surrogates kept as literal characters. {@code < > &} and non-ASCII stay unescaped
     * (unlike Gson's default).
     */
    private static void appendJsonString(StringBuilder out, String value) {
        if (value == null) value = "";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        appendUnicodeEscape(out, c);
                    } else if (Character.isHighSurrogate(c)) {
                        if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                            out.append(c).append(value.charAt(++i));
                        } else {
                            appendUnicodeEscape(out, c);
                        }
                    } else if (Character.isLowSurrogate(c)) {
                        appendUnicodeEscape(out, c);
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
    }

    private static void appendUnicodeEscape(StringBuilder out, char c) {
        out.append("\\u");
        String hex = Integer.toHexString(c);
        for (int pad = hex.length(); pad < 4; pad++) out.append('0');
        out.append(hex);
    }
}
