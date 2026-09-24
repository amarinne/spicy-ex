package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.session.DetectionResult;

/**
 * Flavor-facing language-detection seam.
 *
 * <p>The real work lives in {@link LanguageDetectorManager}: one lazy compact CharSoup model
 * with use-counted release on memory trim. This class only exposes the
 * shared entry points used from the main source set.
 */
public final class LatinLanguageGate {
    private LatinLanguageGate() {
    }

    /** Detect one canonical row; reuse {@code known} when it already carries a usable result. */
    public static DetectionResult detect(String text, DetectionResult known) {
        return LanguageDetectorManager.shared().detect(text, known);
    }

    public static DetectionResult detect(String text) {
        return LanguageDetectorManager.shared().detect(text);
    }

    /** Drops resident detector models. Persistent detection records are untouched. */
    public static void trimMemory() {
        LanguageDetectorManager.shared().trimMemory();
    }

    /** Exposed for tests: how many detector configurations have been created. */
    static long detectionCountForTest() {
        return LanguageDetectorManager.shared().detectionCount();
    }
}
