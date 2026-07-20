package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Exact conformance gate for licensed mobile romaji projection behavior. */
public class JapaneseRomajiProjectionConformanceTest {
    @Test
    public void vendoredProjectionAuthorityRunsEveryCaseExactly() throws Exception {
        JapaneseRomajiProjectionRunner.Run run = JapaneseRomajiProjectionRunner.run("test");
        assertEquals("1.1.0", run.manifest.get("projectionVersion").getAsString());
        assertEquals(44, run.projection.getAsJsonArray("cases").size());
        assertEquals(44, run.product.getAsJsonArray("results").size());
        assertTrue(String.join("\n", run.problems), run.problems.isEmpty());
    }
}
