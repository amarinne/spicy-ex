package com.eza.spicyex.lyrics;

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
    public void unknownLatinProviderTranslationDefaultsOnlyToEnglish() {
        assertEquals("Hello", ProviderTranslationResolver.resolve("你好", "Hello", "", "en"));
        assertEquals("", ProviderTranslationResolver.resolve("你好", "Hello", "", "fr"));
        assertEquals("Esta canción ya está en español",
                ProviderTranslationResolver.resolve("this song", "Esta canción ya está en español", "", "es"));
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
