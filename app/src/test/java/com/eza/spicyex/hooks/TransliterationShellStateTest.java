package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.Settings;
import com.eza.spicyex.lyrics.language.ChineseCharacterForm;
import com.eza.spicyex.lyrics.language.KoreanDisplayMode;
import com.eza.spicyex.settings.SettingsWriter;
import com.eza.spicyex.settings.TypedStore;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** The shell's reading state: initial reads, show/hide transitions, and the cycle write. */
public class TransliterationShellStateTest {
    @Test
    public void readsTheMountFlagsAndEveryLastCycleMode() {
        FakeStore store = new FakeStore();
        store.set(Settings.NATIVE_SPICY_ROMANIZATION, true);
        store.set(Settings.NATIVE_SPICY_TRANSLATION, true);
        store.set(Settings.LAST_JAPANESE_CYCLE_MODE, "furigana_romaji");
        store.set(Settings.LAST_CHINESE_CYCLE_MODE, "jyutping");
        store.set(Settings.LAST_KOREAN_CYCLE_MODE, KoreanDisplayMode.VN_PRONUNCIATION.value);
        store.set(Settings.LAST_CYRILLIC_CYCLE_MODE, "Ukrainian");

        TransliterationShellState state = TransliterationShellState.read(store);

        assertTrue(state.showRomanization());
        assertTrue(state.showTranslation());
        assertEquals("furigana_romaji", state.japaneseMode());
        assertEquals("jyutping", state.chineseMode());
        assertEquals(KoreanDisplayMode.VN_PRONUNCIATION.value, state.koreanMode());
        assertEquals("Ukrainian", state.cyrillicMode());
        assertEquals(6, store.reads);
    }

    @Test
    public void unsetValuesKeepTheirSchemaDefaults() {
        FakeStore store = new FakeStore();

        TransliterationShellState state = TransliterationShellState.read(store);

        assertFalse(state.showRomanization());
        assertFalse(state.showTranslation());
        assertEquals(Settings.LAST_JAPANESE_CYCLE_MODE.defaultValue, state.japaneseMode());
        assertEquals(Settings.LAST_CHINESE_CYCLE_MODE.defaultValue, state.chineseMode());
        assertEquals(Settings.LAST_KOREAN_CYCLE_MODE.defaultValue, state.koreanMode());
        assertEquals(Settings.LAST_CYRILLIC_CYCLE_MODE.defaultValue, state.cyrillicMode());
        assertEquals(ChineseCharacterForm.ORIGINAL, state.characterForm());
    }

    @Test
    public void showAndHideTransitionsStayInTheState() {
        TransliterationShellState state = TransliterationShellState.read(new FakeStore());

        state.setShowRomanization(true);
        state.setShowTranslation(true);
        assertTrue(state.showRomanization());
        assertTrue(state.showTranslation());

        assertFalse(state.toggleTranslation());
        assertFalse(state.showTranslation());
        assertTrue(state.toggleTranslation());
        assertTrue(state.showTranslation());

        state.setShowRomanization(false);
        state.setShowTranslation(false);
        assertFalse(state.showRomanization());
        assertFalse(state.showTranslation());
    }

    @Test
    public void cycleWritePersistsEveryShellOwnedKeyAndRoundTrips() {
        FakeStore store = new FakeStore();
        store.set(Settings.TRANSLITERATION_ENABLED, false);
        store.set(Settings.TRANSLATION_ENABLED, false);
        TransliterationShellState state = TransliterationShellState.read(store);

        state.setShowRomanization(true);
        state.setShowTranslation(true);
        state.setModes("furigana_only", "cantonese", KoreanDisplayMode.WORD_TRANSLIT.value,
                "Ukrainian");
        state.write(new SettingsWriter(store));

        assertEquals(6, store.writes);
        TransliterationShellState written = TransliterationShellState.read(store);
        assertTrue(written.showRomanization());
        assertTrue(written.showTranslation());
        assertEquals("furigana_only", written.japaneseMode());
        assertEquals("jyutping", written.chineseMode());
        assertEquals(KoreanDisplayMode.WORD_TRANSLIT.value, written.koreanMode());
        assertEquals("Ukrainian", written.cyrillicMode());
        assertEquals(Boolean.FALSE, store.values.get(Settings.TRANSLITERATION_ENABLED.key));
        assertEquals(Boolean.FALSE, store.values.get(Settings.TRANSLATION_ENABLED.key));
    }

    @Test
    public void storedModesAreNormalizedToTheLabelsTheShellReads() {
        FakeStore store = new FakeStore();
        store.set(Settings.LAST_CHINESE_CYCLE_MODE, "Cantonese");
        store.set(Settings.LAST_KOREAN_CYCLE_MODE, "Letter-by-letter");

        TransliterationShellState state = TransliterationShellState.read(store);

        assertEquals("jyutping", state.chineseMode());
        assertEquals(KoreanDisplayMode.WORD_TRANSLIT.value, state.koreanMode());

        state.setModes(null, "pinyin-eza", null, null);
        assertEquals("", state.japaneseMode());
        assertEquals("pinyin-eza", state.chineseMode());
        assertEquals("", state.koreanMode());
        assertEquals("", state.cyrillicMode());
    }

    @Test
    public void characterFormFollowsThePanelWriteWithoutRemounting() {
        FakeStore store = new FakeStore();
        TransliterationShellState state = TransliterationShellState.read(store);
        assertEquals(ChineseCharacterForm.ORIGINAL, state.characterForm());

        store.set(Settings.CHINESE_CHARACTER_FORM, "traditional");
        assertEquals(ChineseCharacterForm.TRADITIONAL, state.characterForm());

        store.set(Settings.CHINESE_CHARACTER_FORM, "simplified");
        assertEquals(ChineseCharacterForm.SIMPLIFIED, state.characterForm());
    }

    @Test
    public void mountStateDoesNotFollowLaterFlagWrites() {
        FakeStore store = new FakeStore();
        store.set(Settings.NATIVE_SPICY_ROMANIZATION, true);
        store.set(Settings.LAST_CHINESE_CYCLE_MODE, "jyutping");
        TransliterationShellState state = TransliterationShellState.read(store);

        store.set(Settings.NATIVE_SPICY_ROMANIZATION, false);
        store.set(Settings.LAST_CHINESE_CYCLE_MODE, "pinyin");

        assertTrue(state.showRomanization());
        assertEquals("jyutping", state.chineseMode());
    }

    private static final class FakeStore implements TypedStore {
        final Map<String, Object> values = new HashMap<>();
        int reads;
        int writes;

        void set(Settings.Setting<?> setting, Object value) {
            values.put(setting.key, value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Settings.Setting<T> setting) {
            reads++;
            Object value = values.get(setting.key);
            return value == null ? setting.defaultValue : (T) value;
        }

        @Override
        public boolean contains(Settings.Setting<?> setting) {
            return values.containsKey(setting.key);
        }

        @Override
        public void putBoolean(Settings.BooleanSetting setting, boolean value) {
            writes++;
            values.put(setting.key, value);
        }

        @Override
        public void putString(Settings.StringSetting setting, String value) {
            writes++;
            values.put(setting.key, value);
        }

        @Override
        public void putInt(Settings.IntegerSetting setting, int value) {
            writes++;
            values.put(setting.key, value);
        }
    }
}
