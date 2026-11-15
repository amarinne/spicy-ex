package com.eza.spicyex.lyrics.ai;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the API-key reveal's secure-dialog wiring until the Android harness can exercise it.
 * Pure key-row state tests do not construct the dialog or verify its window protection.
 */
public final class AiLaneWiringContractTest {

    /** Plaintext must reach a secure dialog and nothing else; that dialog cannot exist on the JVM. */
    @Test
    public void apiKeyRevealShowsPlaintextOnlyInASecureDialog() throws Exception {
        String source = read("src/main/java/com/eza/spicyex/settings/panel/AiSettingsRows.java");

        assertTrue(source.contains("new PanelDialog(context,"));
        assertTrue(source.contains(".secure()"));
        assertTrue(source.contains("dialog.secretValue(secret);"));
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile()) file = new File("app/" + path);
        assertTrue(file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
