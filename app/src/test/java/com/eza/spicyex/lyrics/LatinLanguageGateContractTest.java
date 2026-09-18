package com.eza.spicyex.lyrics;

import com.eza.spicyex.BuildConfig;
import com.eza.spicyex.lyrics.session.DetectionResult;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Flavor contract for the detection seam.
 *
 * <p>Full ships Lingua behind {@link LatinLanguageGate}; Lite ships the same signatures as no-ops.
 * The assertion follows the build capability rather than the source set, so the same test is
 * correct if the Lite test source set ever compiles. Model accuracy is exercised by the detector
 * manager's unit tests and by on-device validation; this test only pins the flavor contract.
 */
public class LatinLanguageGateContractTest {
    @Test
    public void detectionFollowsFlavorCapability() {
        DetectionResult result = LatinLanguageGate.detect("hello my friend, how are you today");

        if (BuildConfig.TRANSLITERATION_AVAILABLE) {
            assertNotNull(result);
            LatinLanguageGate.trimMemory();
        } else {
            assertNull(result);
            assertNull(LatinLanguageGate.detect("hello my friend", null));
        }
    }
}
