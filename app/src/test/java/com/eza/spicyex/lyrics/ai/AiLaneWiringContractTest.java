package com.eza.spicyex.lyrics.ai;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Small source contracts for Android-coupled wiring that pure runner tests cannot instantiate. */
public final class AiLaneWiringContractTest {

    @Test
    public void soundNoWorkPathKeepsDisplayedBaselineForExactPaidReuse() throws Exception {
        String source = read("src/main/java/com/eza/spicyex/lyrics/LyricsSoundLane.java");
        String compact = source.replaceAll("\\s+", " ");

        assertTrue(compact.contains("startAiGapFill(run, id, generation, snapshot, "
                + "displayedSound, displayedSound, settings,"));
    }

    @Test
    public void apiKeyRevealIsTransientAndNeverReplacesTheMaskedRow() throws Exception {
        String source = read("src/main/java/com/eza/spicyex/AiSettingsRows.java");

        assertTrue(source.contains("revealKeySecurely()"));
        assertTrue(source.contains("AiCredentialStore.mask("));
        assertTrue(source.contains(".secure()"));
        assertFalse(source.contains("revealKey ?"));
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile()) file = new File("app/" + path);
        assertTrue(file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
