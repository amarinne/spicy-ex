package com.eza.spicyex.lyrics.ai;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Source contracts for wiring a JVM test cannot reach.
 *
 * <p>Every contract this file once held now has a home a JVM test can run: the lanes in
 * {@link com.eza.spicyex.lyrics.LyricsMeaningLaneBehaviorTest} and
 * {@link com.eza.spicyex.lyrics.LyricsSoundLaneBehaviorTest}, the host rules in
 * {@link com.eza.spicyex.hooks.SoundToggleHostDecisionTest} and
 * {@link com.eza.spicyex.hooks.TranslationVisibilityHostDecisionTest}, and the key row in
 * {@link com.eza.spicyex.ApiKeyRowStateTest}. What remains is the wiring those tests deliberately
 * do not execute: a secure dialog cannot be constructed off-device, so that one guard stays.
 */
public final class AiLaneWiringContractTest {

    /** Plaintext must reach a secure dialog and nothing else; that dialog cannot exist on the JVM. */
    @Test
    public void apiKeyRevealShowsPlaintextOnlyInASecureDialog() throws Exception {
        String source = read("src/main/java/com/eza/spicyex/AiSettingsRows.java");

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
