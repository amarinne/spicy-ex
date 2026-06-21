package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
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
}
