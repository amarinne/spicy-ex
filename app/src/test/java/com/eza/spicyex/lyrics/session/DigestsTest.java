package com.eza.spicyex.lyrics.session;

import static org.junit.Assert.assertEquals;

import com.eza.spicyex.lyrics.LyricCaches;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Random;

import org.junit.Test;

public class DigestsTest {
    @Test
    public void hexPreservesEveryByteAndLeadingZeroes() {
        byte[] bytes = new byte[256];
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
            expected.append(String.format(Locale.ROOT, "%02x", bytes[i]));
        }
        assertEquals(expected.toString(), Digests.hex(bytes));
        assertEquals("", Digests.hex(new byte[0]));
    }

    @Test
    public void sha256MatchesKnownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Digests.sha256(null));
        assertEquals(Digests.sha256(null), Digests.sha256(""));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Digests.sha256("abc"));
        assertEquals("ba7816bf", Digests.shortHash("abc"));
    }

    @Test
    public void persistedCacheKeysAndCanonicalIdsKeepLegacyEncoding() throws Exception {
        Method cacheHash = LyricCaches.class.getDeclaredMethod("sha256", String.class);
        cacheHash.setAccessible(true);
        Random random = new Random(622);
        for (int sample = 0; sample < 200; sample++) {
            StringBuilder input = new StringBuilder("\u541b\u306e\u58f0|\ud83c\udfb5|");
            for (int i = 0; i < sample; i++) input.append((char) random.nextInt(65536));
            String value = sample == 0 ? null : input.toString();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder legacy = new StringBuilder();
            for (byte b : digest) legacy.append(String.format(Locale.ROOT, "%02x", b));
            assertEquals(legacy.toString(), Digests.sha256(value));
            assertEquals(legacy.toString(), cacheHash.invoke(null, value));
        }
    }
}
