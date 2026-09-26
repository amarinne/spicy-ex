package com.eza.spicyex.lyrics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Request signing for NetEase Cloud Music's "eapi" endpoints.
 *
 * <p>The word-level ("YRC", 逐字) lyric endpoint is only reachable this way - the plain web API
 * returns line-level LRC and nothing else - so this is what the difference between a NetEase track
 * rendering as a karaoke fill and rendering as a plain synced line comes down to.
 *
 * <p>Ported from Lyricify-Lyrics-Helper's {@code EapiHelper}
 * (github.com/WXRIW/Lyricify-Lyrics-Helper). The scheme is a fixed, publicly known AES-128-ECB key
 * plus an MD5 digest over the request: the payload is
 * {@code <path>-36cd479b6b5-<json>-36cd479b6b5-<md5 of "nobody<path>use<json>md5forencrypt">},
 * AES-encrypted and sent hex-uppercased as a single {@code params} form field.
 */
final class NeteaseEapi {

    /** Publicly known fixed key; not a secret and not user data. */
    private static final byte[] KEY = "e82ckenh8dichen8".getBytes(StandardCharsets.US_ASCII);
    private static final String SEPARATOR = "-36cd479b6b5-";

    /** The client the signed request claims to be; the endpoint rejects a plain browser UA. */
    static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 9; PCT-AL10) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/70.0.3538.64 "
            + "HuaweiBrowser/10.0.3.311 Mobile Safari/537.36";

    private NeteaseEapi() {
    }

    /**
     * Cookie header the endpoint expects alongside a signed request. Deliberately carries no
     * account: {@code MUSIC_U} is left empty, so nothing here identifies a user and no login state
     * is involved - this is the anonymous client profile the API accepts.
     */
    static String cookieHeader() {
        long nowMs = System.currentTimeMillis();
        String requestId = nowMs + "_" + String.format(Locale.ROOT, "%04d",
                (int) (Math.random() * 1000));
        return "__csrf=; appver=8.0.0"
                + "; buildver=" + (nowMs / 1000L)
                + "; channel=; deviceId=; mobilename=; resolution=1920x1080"
                + "; os=android; osver="
                + "; requestId=" + requestId
                + "; versioncode=140; MUSIC_U=";
    }

    /**
     * The {@code params} form value for a signed call.
     *
     * @param apiPath the endpoint path as the digest must see it - the request URL with the host
     *                and its leading {@code /e} replaced by {@code /}, e.g.
     *                {@code /api/song/lyric/v1} for
     *                {@code https://interface3.music.163.com/eapi/song/lyric/v1}
     * @param json    the request body object, already serialised (must include the {@code header}
     *                field the API expects)
     * @return the hex-uppercased ciphertext, or null if this device cannot do AES/MD5 at all
     */
    static String params(String apiPath, String json) {
        try {
            String digest = md5Hex("nobody" + apiPath + "use" + json + "md5forencrypt");
            String payload = apiPath + SEPARATOR + json + SEPARATOR + digest;
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"));
            return hex(cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8)), true);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The {@code header} object every eapi request carries inside its own body. */
    static String headerJson() {
        long nowMs = System.currentTimeMillis();
        return "{\"__csrf\":\"\",\"appver\":\"8.0.0\",\"buildver\":\"" + (nowMs / 1000L) + "\","
                + "\"channel\":\"\",\"deviceId\":\"\",\"mobilename\":\"\","
                + "\"resolution\":\"1920x1080\",\"os\":\"android\",\"osver\":\"\","
                + "\"requestId\":\"" + nowMs + "_0000\",\"versioncode\":\"140\",\"MUSIC_U\":\"\"}";
    }

    private static String md5Hex(String value) throws Exception {
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        return hex(md5.digest(value.getBytes(StandardCharsets.UTF_8)), false);
    }

    private static String hex(byte[] bytes, boolean upper) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format(Locale.ROOT, upper ? "%02X" : "%02x", b & 0xFF));
        }
        return out.toString();
    }
}
