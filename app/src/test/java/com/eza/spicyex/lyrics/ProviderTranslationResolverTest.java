package com.eza.spicyex.lyrics;

import com.eza.spicyex.lyrics.session.DetectionResult;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProviderTranslationResolverTest {
    @Test
    public void declaredProviderLanguageMustMatchTarget() {
        assertEquals("Hello", ProviderTranslationResolver.resolve("你好", "Hello", "en", "en"));
        assertEquals("Hello", ProviderTranslationResolver.resolve("你好", "Hello", "en-US", "en"));
        assertEquals("", ProviderTranslationResolver.resolve("你好", "Hello", "en", "vi"));
        assertEquals("Xin chào", ProviderTranslationResolver.resolve("你好", "Xin chào", "vi", "vi"));
    }

    @Test
    public void unlabelledLatinProviderTextRequiresItsOwnDetection() {
        TextDetectionLookup english = text -> DetectionResult.detected("", text,
                ScriptClassifier.ScriptClass.LATIN, "en", 0.9);

        assertEquals("Hello", ProviderTranslationResolver.resolve("你好", "Hello", "", "en", english));
        // No evidence, no acceptance — not even for an English target.
        assertEquals("", ProviderTranslationResolver.resolve("你好", "Hello", "", "en",
                TextDetectionLookup.NONE));
        assertEquals("", ProviderTranslationResolver.resolve("你好", "Hello", "", "fr", english));
        // Accented text does not establish a language either.
        assertEquals("", ProviderTranslationResolver.resolve(
                "this song", "Esta canción ya está en español", "", "es"));
    }

    @Test
    public void foreignLatinTextIsNotAcceptedAsEnglishWithoutEnglishDetection() {
        TextDetectionLookup spanish = spanishLookup();

        assertEquals("", ProviderTranslationResolver.resolve(
                "this song", "Esta canción ya está en español", "", "en", spanish));
        assertEquals("", ProviderTranslationResolver.resolve(
                "this song", "Esta canción ya está en español", "", "en", TextDetectionLookup.NONE));
        assertEquals("Esta canción ya está en español", ProviderTranslationResolver.resolve(
                "this song", "Esta canción ya está en español", "", "en",
                text -> DetectionResult.detected("", text, ScriptClassifier.ScriptClass.LATIN, "en", 0.9)));
    }

    @Test
    public void accentedProviderTextIsNotAcceptedForAnUnrelatedTarget() {
        TextDetectionLookup spanish = spanishLookup();

        // Regression probes: Spanish text with Vietnamese selected, and English text supplied
        // for a Spanish target, must not be accepted on accents or on the source lyric's language.
        assertEquals("", ProviderTranslationResolver.resolve(
                "this song", "Esta canción ya está en español", "", "vi", spanish));
        assertEquals("", ProviderTranslationResolver.resolve(
                "esta canción", "Hello my friend", "", "es", spanish));
    }

    @Test
    public void providerDetectionOfTheTranslationTextDecides() {
        TextDetectionLookup spanish = spanishLookup();
        TextDetectionLookup englishForHola = text -> "Hola amigo".equals(text)
                ? DetectionResult.detected("", text, ScriptClassifier.ScriptClass.LATIN, "en", 0.9)
                : null;

        assertEquals("Esta canción ya está en español", ProviderTranslationResolver.resolve(
                "this song", "Esta canción ya está en español", "", "es", spanish));
        assertEquals("Hola amigo", ProviderTranslationResolver.resolve(
                "hello friend", "Hola amigo", "", "es", spanish));
        assertEquals("", ProviderTranslationResolver.resolve(
                "hello friend", "Hola amigo", "", "es", englishForHola));
        assertEquals("", ProviderTranslationResolver.resolve(
                "hello friend", "Hola amigo", "", "es", TextDetectionLookup.NONE));
    }

    @Test
    public void providerResolutionNeverInvokesTheDetector() {
        long before = LatinLanguageGate.detectionCountForTest();
        TextDetectionLookup spanish = spanishLookup();

        assertEquals("Esta canción ya está en español", ProviderTranslationResolver.resolve(
                "this song", "Esta canción ya está en español", "", "es", spanish));
        assertEquals("", ProviderTranslationResolver.resolve(
                "this song", "Esta canción ya está en español", "", "vi", spanish));

        assertEquals(before, LatinLanguageGate.detectionCountForTest());
    }

    /** Detection that only vouches for the two Spanish strings used by these tests. */
    private static TextDetectionLookup spanishLookup() {
        return text -> text != null && (text.startsWith("Esta") || text.startsWith("Hola"))
                ? DetectionResult.detected("", text, ScriptClassifier.ScriptClass.LATIN, "es", 0.9)
                : null;
    }

    @Test
    public void chineseVariantsDoNotCrossTargets() {
        assertEquals("这首歌", ProviderTranslationResolver.resolve("this song", "这首歌", "zh", "zh"));
        assertEquals("", ProviderTranslationResolver.resolve("this song", "這首歌", "zh", "zh"));
        assertEquals("這首歌", ProviderTranslationResolver.resolve("this song", "這首歌", "zh", "zh-TW"));
        assertEquals("", ProviderTranslationResolver.resolve("this song", "这首歌", "zh-Hans", "zh-TW"));
    }

    @Test
    public void providerTranslationFieldsSurviveCopies() {
        LyricsLine source = new LyricsLine();
        source.providerTranslatedText = "Hello";
        source.providerTranslationLanguage = "en";
        BackgroundLine background = new BackgroundLine();
        background.providerTranslatedText = "Background";
        background.providerTranslationLanguage = "en";
        source.backgroundLines.add(background);

        LyricsLine copy = LyricsLine.copyOf(source);
        assertEquals("Hello", copy.providerTranslatedText);
        assertEquals("en", copy.providerTranslationLanguage);
        assertEquals("Background", copy.backgroundLines.get(0).providerTranslatedText);
        assertEquals("en", copy.backgroundLines.get(0).providerTranslationLanguage);
    }

    @Test
    public void matchingProviderTranslationSatisfiesGeneratedWorkForThatLine() {
        LyricsDocument document = new LyricsDocument();
        LyricsLine line = new LyricsLine();
        line.text = "你好";
        line.providerTranslatedText = "Hello";
        line.providerTranslationLanguage = "en";
        document.lines.add(line);

        assertEquals(1, ProviderTranslationResolver.applyTranslations(document, "en"));
        assertTrue(LyricsDocumentProcessor.hasDisplayedTranslation(document));
        assertFalse(LyricsDocumentProcessor.hasGeneratedTranslationWork(document, "zh", "en"));
    }

    @Test
    public void failedOrPartialGeneratedPassIsNotComplete() {
        assertFalse(LyricsMeaningLane.translationPassComplete(true,
                Arrays.asList(1, 2), new HashSet<>(Collections.singletonList(1))));
        assertTrue(LyricsMeaningLane.translationPassComplete(true,
                Arrays.asList(1, 2), new HashSet<>(Arrays.asList(1, 2))));
        assertTrue(LyricsMeaningLane.translationPassComplete(false,
                Arrays.asList(1, 2), Collections.emptySet()));
    }
}
