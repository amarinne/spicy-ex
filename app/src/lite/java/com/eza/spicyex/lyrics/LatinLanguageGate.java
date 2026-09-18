package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.session.DetectionResult;

/**
 * Lite flavor seam: no language models ship in this build, so detection is a no-op.
 *
 * <p>The session and gates still run; rows simply carry no detected language and fall back to the
 * script/hint logic that Lite has always used.
 */
public final class LatinLanguageGate {
    private LatinLanguageGate() {
    }

    public static DetectionResult detect(String text, DetectionResult known) {
        return null;
    }

    public static DetectionResult detect(String text) {
        return null;
    }

    public static void trimMemory() {
    }
}
