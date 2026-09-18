package com.eza.spicyex.lyrics.session;

import com.eza.spicyex.lyrics.ScriptClassifier;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DetectionArtifactTest {
    @Test
    public void rowsAreAddressedByRowIdAndMergedPerRow() {
        DetectionResult original = DetectionResult.detected("r0#a", "hello", 
                ScriptClassifier.ScriptClass.LATIN, "en", 0.9);
        DetectionResult delta = DetectionResult.detected("r1#b", "bonjour", 
                ScriptClassifier.ScriptClass.LATIN, "fr", 0.8);
        DetectionArtifact first = new DetectionArtifact("digest", DetectionArtifact.DETECTOR_POLICY_ID,
                Collections.singletonList(original), false);
        DetectionArtifact second = new DetectionArtifact("digest", DetectionArtifact.DETECTOR_POLICY_ID,
                Collections.singletonList(delta), false);

        DetectionArtifact merged = first.merged(second);

        assertEquals(2, merged.size());
        assertEquals("en", merged.result("r0#a").language);
        assertEquals("fr", merged.result("r1#b").language);
        assertNull(merged.result("missing"));
    }

    @Test
    public void deltaOverwritesSameRowWithoutTouchingOthers() {
        DetectionResult stale = DetectionResult.unknown("r0#a", "hello");
        DetectionResult fresh = DetectionResult.detected("r0#a", "hello",
                ScriptClassifier.ScriptClass.LATIN, "en", 0.7);
        DetectionArtifact base = new DetectionArtifact("digest", DetectionArtifact.DETECTOR_POLICY_ID,
                Collections.singletonList(stale), true);
        DetectionArtifact delta = new DetectionArtifact("digest", DetectionArtifact.DETECTOR_POLICY_ID,
                Collections.singletonList(fresh), false);

        DetectionArtifact merged = base.merged(delta);

        assertEquals(1, merged.size());
        assertTrue(merged.result("r0#a").hasLanguage());
        assertFalse(merged.partial);
    }

    @Test
    public void mergingADifferentDigestIsANoOp() {
        DetectionResult row = DetectionResult.detected("r0#a", "hello",
                ScriptClassifier.ScriptClass.LATIN, "en", 0.9);
        DetectionArtifact base = new DetectionArtifact("digest", DetectionArtifact.DETECTOR_POLICY_ID,
                Collections.singletonList(row), false);
        DetectionArtifact other = new DetectionArtifact("other", DetectionArtifact.DETECTOR_POLICY_ID,
                Arrays.asList(row), false);

        assertEquals(base.size(), base.merged(other).size());
        assertEquals("digest", base.merged(other).canonicalDigest);
    }

    @Test
    public void unknownAndScriptOnlyResultsAreNotLanguages() {
        assertFalse(DetectionResult.unknown("r", "x").hasLanguage());
        assertFalse(DetectionResult.scriptOnly("r", "x",
                ScriptClassifier.ScriptClass.INDIC).hasLanguage());
        assertTrue(DetectionResult.detected("r", "x",
                ScriptClassifier.ScriptClass.LATIN, "en", 1.0).hasLanguage());
    }
}
