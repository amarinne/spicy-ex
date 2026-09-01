package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Exact cross-product conformance gate for the real Full-flavor Japanese pipeline. */
public class JapaneseReadingPolicyConformanceTest {
    @Test
    public void vendoredLabV16RunsEveryCaseExactly() throws Exception {
        JapaneseReadingPolicyRunner.Run run = JapaneseReadingPolicyRunner.run("test");
        assertEquals("1.6.0", run.manifest.get("policyVersion").getAsString());
        assertEquals("1.6.0", run.manifest.get("corpusVersion").getAsString());
        assertEquals(65, run.conformance.getAsJsonArray("cases").size());
        assertEquals(65, run.product.getAsJsonArray("results").size());
        assertTrue(String.join("\n", run.problems), run.problems.isEmpty());
    }
}
