package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.session.DetectionResult;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpicyProcessingDetectionTest {
    @Test
    public void validDetectionSuppressesTranslationWithoutCallingTheDetector() {
        long before = LatinLanguageGate.detectionCountForTest();
        DetectionResult english = DetectionResult.detected("r0#a", "hello my friend",
                ScriptClassifier.ScriptClass.LATIN, "en", 0.9);

        SpicyProcessing.ProcessingFlags flags =
                SpicyProcessing.flagsFor("hello my friend", "en", "en", english);

        assertFalse(flags.translationPending);
        assertFalse(flags.processingPending);
        org.junit.Assert.assertEquals(before, LatinLanguageGate.detectionCountForTest());
    }

    @Test
    public void differentDetectedLanguageStillNeedsTranslation() {
        DetectionResult french = DetectionResult.detected("r0#a", "bonjour mon ami",
                ScriptClassifier.ScriptClass.LATIN, "fr", 0.9);

        SpicyProcessing.ProcessingFlags flags =
                SpicyProcessing.flagsFor("bonjour mon ami", "en", "en", french);

        assertTrue(flags.translationPending);
    }

    @Test
    public void matchingDetectionOverridesAWrongSourceHint() {
        DetectionResult french = DetectionResult.detected("r0#a", "bonjour mon ami",
                ScriptClassifier.ScriptClass.LATIN, "fr", 0.9);

        SpicyProcessing.ProcessingFlags flags =
                SpicyProcessing.flagsFor("bonjour mon ami", "en", "fr", french);

        assertFalse(flags.translationPending);
    }

    @Test
    public void missingDetectionFallsBackWithoutCallingTheDetector() {
        long before = LatinLanguageGate.detectionCountForTest();

        boolean pending = SpicyProcessing.shouldTranslateLine(
                "bonjour mon ami comment vas tu ce matin", "en", "en");

        assertTrue(pending);
        org.junit.Assert.assertEquals(before, LatinLanguageGate.detectionCountForTest());
    }

    @Test
    public void punctuationRowsProduceNoDetectorWork() {
        long before = LatinLanguageGate.detectionCountForTest();

        SpicyProcessing.ProcessingFlags flags =
                SpicyProcessing.flagsFor("♪ ♪", "en", "en", null);

        assertFalse(flags.translationPending);
        org.junit.Assert.assertEquals(before, LatinLanguageGate.detectionCountForTest());
    }
}
