package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

public class GoogleEnhancerTest {
    @Test
    public void parsesMarkedBatchTranslation() {
        String body = "[[[\"[[SPX_000]] First translated\\n[[SPX_001]] Second translated\",null,null,null]]]";

        Map<Integer, String> parsed = GoogleEnhancer.parseBatchTranslation(body);

        assertEquals("First translated", parsed.get(0));
        assertEquals("Second translated", parsed.get(1));
    }

    @Test
    public void sameTextIgnoresWhitespaceOnlyDifferences() {
        assertTrue(GoogleEnhancer.sameText(" hello   world ", "hello world"));
    }

    @Test
    public void sameTextIgnoresFormattingOnlyDifferences() {
        assertTrue(GoogleEnhancer.sameText("Teach me how to say good night…", "teach me how to say good night"));
        assertTrue(GoogleEnhancer.sameText("Hello — world!", "hello world"));
        assertTrue(GoogleEnhancer.sameText("it’s ok", "it's ok"));
    }

    @Test
    public void hidesRomanizationEchoesFromTranslation() {
        assertFalse(GoogleEnhancer.shouldDisplayTranslation("Алдадыңбы,", "Aldadynby,"));
        assertFalse(GoogleEnhancer.shouldDisplayTranslation("Чалбадыңбы,", "Chalbadynby,"));
        assertFalse(GoogleEnhancer.shouldDisplayTranslation("中国", "zhong guo"));
    }

    @Test
    public void showsRealTranslations() {
        assertTrue(GoogleEnhancer.shouldDisplayTranslation("Алдадыңбы,", "did you cheat"));
        assertTrue(GoogleEnhancer.shouldDisplayTranslation("Больше не радуешь лучами,", "You no longer please with rays,"));
        assertTrue(GoogleEnhancer.shouldDisplayTranslation("中国", "China"));
    }
}
