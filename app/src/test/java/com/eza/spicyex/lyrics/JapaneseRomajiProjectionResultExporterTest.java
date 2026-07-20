package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.Assume;
import org.junit.Test;

/** Opt-in deterministic export of exact mobile romaji projection observations. */
public class JapaneseRomajiProjectionResultExporterTest {
    @Test
    public void exportWhenPathProvided() throws Exception {
        String outputPath = System.getenv("JP_PROJECTION_RESULTS_OUTPUT");
        String implementationVersion = System.getenv("JP_PROJECTION_IMPLEMENTATION_VERSION");
        Assume.assumeTrue(outputPath != null && implementationVersion != null);
        JapaneseRomajiProjectionRunner.Run run = JapaneseRomajiProjectionRunner.run(implementationVersion);
        assertTrue(String.join("\n", run.problems), run.problems.isEmpty());
        String json = JapaneseReadingPolicyRunner.canonicalJson(run.product) + "\n";
        Files.write(Path.of(outputPath), json.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
    }
}
