package com.eza.spicyex.settings.registry;

import com.eza.spicyex.Settings;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The panel's visibility matrix: which row shows under which stored state.
 *
 * <p>These rules used to live in the panel's own policy class. They are registry data now, so the
 * panel and this test read one source.
 */
public class PanelVisibilityTest {
    @Test
    public void translationRowsFollowTheMasterSwitch() {
        assertFalse(visible(Settings.TRANSLATION_TARGET, context(true)));
        UiContext on = context(true, Settings.TRANSLATION_ENABLED, true);
        assertTrue(visible(Settings.TRANSLATION_TARGET, on));
        assertTrue(visible(Settings.TRANSLATION_BRIGHTNESS, on));
    }

    @Test
    public void pipOptionsFollowMasterSwitch() {
        UiContext off = context(true);
        assertTrue(visible(Settings.PIP_ENABLED, off));
        assertFalse(visible(Settings.PIP_SHAPE, off));
        assertFalse(visible(Settings.PIP_CONTROLS, off));
        assertFalse(visible(Settings.PIP_FOCUS, off));
        assertFalse(visible(Settings.PIP_LEAVE_SPOTIFY, off));
        assertFalse(visible(Settings.PIP_ALBUM_ART, off));

        UiContext on = context(true, Settings.PIP_ENABLED, true);
        assertTrue(visible(Settings.PIP_SHAPE, on));
        assertTrue(visible(Settings.PIP_CONTROLS, on));
        assertTrue(visible(Settings.PIP_FOCUS, on));
        assertTrue(visible(Settings.PIP_LEAVE_SPOTIFY, on));
        assertTrue(visible(Settings.PIP_ALBUM_ART, on));
    }

    @Test
    public void androidAutoOptionsFollowMasterSwitch() {
        UiContext off = context(true);
        assertTrue(visible(Settings.AUTO_ENABLED, off));
        assertFalse(visible(Settings.AUTO_SECONDARY_TEXT, off));
        UiContext on = context(true, Settings.AUTO_ENABLED, true);
        assertTrue(visible(Settings.AUTO_ENABLED, on));
        assertTrue(visible(Settings.AUTO_SECONDARY_TEXT, on));
    }

    // --- AI nesting ---

    @Test
    public void aiFamilyNestsUnderMasterSwitch() {
        UiContext off = context(true, Settings.AI_ENABLED, false);
        assertTrue(visible(Settings.AI_ENABLED, off));
        assertFalse(visible(Settings.AI_PROVIDER, off));
        UiContext on = context(true, Settings.AI_ENABLED, true);
        assertTrue(visible(Settings.AI_PROVIDER, on));
        assertTrue(visible(Settings.AI_TRANSLATION_MODE, on));
        assertTrue(visible(Settings.AI_TRANSLATION_PIPELINE, on));
        assertTrue(visible(Settings.AI_PRONUNCIATION_MODE, on));
        assertTrue(visible(Settings.AI_PRONUNCIATION_SOURCE, on));
        assertTrue(visible(Settings.AI_BUTTON_BEHAVIOR, on));
    }

    @Test
    public void deepseekReasoningNeedsDeepseekProviderAndEnabled() {
        UiContext wrong = context(true, Settings.AI_ENABLED, true, Settings.AI_PROVIDER, "gemini");
        assertFalse(visible(Settings.AI_DEEPSEEK_REASONING, wrong));
        UiContext right = context(true, Settings.AI_ENABLED, true, Settings.AI_PROVIDER, "deepseek");
        assertTrue(visible(Settings.AI_DEEPSEEK_REASONING, right));
    }

    /**
     * The DeepSeek reasoning row needs the AI switch as well as the provider. A stored DeepSeek
     * provider with AI off must not bring the row back.
     */
    @Test
    public void deepseekReasoningNeedsTheAiSwitchAsWellAsTheProvider() {
        UiContext off = context(true, Settings.AI_ENABLED, false, Settings.AI_PROVIDER, "deepseek");
        assertTrue(visible(Settings.AI_ENABLED, off));
        assertFalse("the provider row follows the AI switch", visible(Settings.AI_PROVIDER, off));
        assertFalse("the DeepSeek reasoning row needs the AI switch",
                visible(Settings.AI_DEEPSEEK_REASONING, off));
    }

    // --- Translation / transliteration ---

    @Test
    public void languageControlsReplaceDownloadWhenPackIsReady() {
        UiContext missing = context(false);
        assertTrue(visibleAction("download_language_models", missing));
        assertTrue(visible(Settings.TRANSLITERATION_ENABLED, missing));
        assertTrue(visible(Settings.TRANSLATION_ENABLED, missing));
        assertTrue(visible(Settings.CHINESE_CHARACTER_FORM, missing));

        UiContext translationOn = context(false, Settings.TRANSLATION_ENABLED, true);
        assertTrue(visible(Settings.TRANSLATION_TARGET, translationOn));
        assertTrue(visible(Settings.TRANSLATION_BRIGHTNESS, translationOn));

        UiContext ready = context(true);
        assertFalse(visibleAction("download_language_models", ready));
        assertTrue(visible(Settings.TRANSLITERATION_ENABLED, ready));
        assertTrue(visible(Settings.TRANSLATION_ENABLED, ready));
    }

    @Test
    public void bundledChineseCharacterFormDoesNotNeedTransliterationMasterSwitch() {
        UiContext packReady = context(true, Settings.TRANSLITERATION_ENABLED, false);
        assertTrue(visible(Settings.CHINESE_CHARACTER_FORM, packReady));
        UiContext noPack = context(false, Settings.TRANSLITERATION_ENABLED, false);
        assertTrue(visible(Settings.CHINESE_CHARACTER_FORM, noPack));
    }

    @Test
    public void japaneseReadingNeedsDownloadedLanguageModel() {
        UiContext hasModel = context(true, Settings.TRANSLITERATION_ENABLED, true);
        assertTrue(visible(Settings.JAPANESE_READING_MODE, hasModel));

        UiContext missingModel = context(false, Settings.TRANSLITERATION_ENABLED, true);
        assertFalse(visible(Settings.JAPANESE_READING_MODE, missingModel));
        assertFalse(visible(Settings.FURIGANA_BRIGHTNESS, missingModel));
        assertFalse(visible(Settings.FURIGANA_POSITION_PERCENT, missingModel));
    }

    @Test
    public void readingFamilyNeedsMasterSwitch() {
        UiContext on = context(true, Settings.TRANSLITERATION_ENABLED, true);
        assertTrue(visible(Settings.CHINESE_MODE, on));
        assertTrue(visible(Settings.KOREAN_ROMANIZATION, on));
        assertTrue(visible(Settings.CHINESE_TONES, on));
        assertTrue(visible(Settings.CYRILLIC_MODE, on));
        assertTrue(visible(Settings.CYRILLIC_KEEP_SIGNS, on));
        assertTrue(visible(Settings.ALIGNED_PER_WORD_ROMAJI, on));

        UiContext disabled = context(true, Settings.TRANSLITERATION_ENABLED, false);
        assertFalse(visible(Settings.JAPANESE_READING_MODE, disabled));
        assertFalse(visible(Settings.CHINESE_MODE, disabled));
        assertFalse(visible(Settings.KOREAN_ROMANIZATION, disabled));
        assertFalse(visible(Settings.CYRILLIC_MODE, disabled));
        assertFalse(visible(Settings.CYRILLIC_KEEP_SIGNS, disabled));
        assertFalse(visible(Settings.CHINESE_TONES, disabled));
        assertFalse(visible(Settings.ALIGNED_PER_WORD_ROMAJI, disabled));
    }

    /**
     * The furigana design rows have never rendered: the panel's applied state never carried the
     * Japanese reading mode, so the guard that shows the rows never matched. The registry keeps
     * them out of the panel until the knob pass decides whether they should exist.
     */
    @Test
    public void theFuriganaDesignRowsHaveNeverRendered() {
        UiContext furiganaOnly = context(true, Settings.TRANSLITERATION_ENABLED, true,
                Settings.JAPANESE_READING_MODE, "furigana_only");
        assertTrue(visible(Settings.JAPANESE_READING_MODE, furiganaOnly));
        assertFalse(visible(Settings.FURIGANA_BRIGHTNESS, furiganaOnly));
        assertFalse(visible(Settings.FURIGANA_POSITION_PERCENT, furiganaOnly));
    }

    // --- Other rows ---

    /**
     * The auto-resume delay row has never rendered: the panel's applied state never carried the
     * auto-resume switch, so the guard that shows the row never matched. The registry keeps the
     * row out of the panel until the knob pass decides whether it should exist.
     */
    @Test
    public void theAutoResumeDelayRowHasNeverRendered() {
        UiContext followOn = context(true, Settings.AUTO_RESUME_FOLLOW, true);
        assertTrue(visible(Settings.AUTO_RESUME_FOLLOW, followOn));
        assertFalse(visible(Settings.AUTO_RESUME_FOLLOW_DELAY_SECONDS, followOn));
    }

    @Test
    public void theClientKeyRowShowsBeforeTheSourceIsEnabled() {
        assertTrue(visible(Settings.SPICY_ORG_CLIENT_KEY, context(true)));
    }

    @Test
    public void ordinaryRowsAlwaysRender() {
        UiContext context = context(true);
        assertTrue(visible(Settings.TAP_SEEK_MODE, context));
        assertTrue(visible(Settings.CACHE_SIZE, context));
        assertTrue(visible(Settings.UI_LANGUAGE, context));
    }

    @Test
    public void theAppleSectionShowsOnlyUnderTheAppleStyle() {
        UiContext wash = context(true, Settings.ANIMATION_STYLE, "Gradient wash");
        UiContext spotlight = context(true, Settings.ANIMATION_STYLE, "Spotlight");
        UiContext apple = context(true, Settings.ANIMATION_STYLE, "Apple Music");
        assertFalse(visible(Settings.APPLE_COMPACT_TEXT, wash));
        assertFalse(visible(Settings.APPLE_COMPACT_TEXT, spotlight));
        assertTrue(visible(Settings.APPLE_COMPACT_TEXT, apple));
        assertFalse("the Apple section disappears with its row",
                ControlRegistry.visiblePanelSections(wash).contains(Settings.APPLE));
        assertTrue(ControlRegistry.visiblePanelSections(apple).contains(Settings.APPLE));
    }

    private static UiContext context(boolean languagePackReady, Object... settings) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < settings.length; i += 2) {
            values.put(((Settings.Setting<?>) settings[i]).key, settings[i + 1]);
        }
        return new UiContext(false, false, false, new SettingsSnapshot(values), languagePackReady);
    }

    private static boolean visible(Settings.Setting<?> setting, UiContext context) {
        for (ControlSpec entry : ControlRegistry.visiblePanelControls(context)) {
            if (entry.setting == setting) return true;
        }
        return false;
    }

    /** A row that stores nothing is looked up by the id it renders under. */
    private static boolean visibleAction(String compositeId, UiContext context) {
        for (ControlSpec entry : ControlRegistry.visiblePanelControls(context)) {
            if (compositeId.equals(entry.compositeId)) return true;
        }
        return false;
    }
}
