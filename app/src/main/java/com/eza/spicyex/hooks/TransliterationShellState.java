package com.eza.spicyex.hooks;

import com.eza.spicyex.Settings;
import com.eza.spicyex.lyrics.LyricsDisplayMode;
import com.eza.spicyex.lyrics.language.ChineseCharacterForm;
import com.eza.spicyex.lyrics.language.KoreanDisplayMode;
import com.eza.spicyex.settings.SettingsReader;
import com.eza.spicyex.settings.SettingsWriter;

/**
 * The fullscreen shell's own reading state: its show/hide flags and the last mode each language
 * cycle stopped on.
 *
 * <p>Owns the initial read and every write of the internal shell keys. The user gates
 * ({@code TRANSLITERATION_ENABLED} and {@code TRANSLATION_ENABLED}) are never read or written
 * here: they stay the panel's decision, and the shell only combines them with this state.
 * The Chinese character form is also the panel's setting, so it is resolved from the reader on
 * every call rather than cached at mount.
 */
public final class TransliterationShellState {
    private final SettingsReader reader;
    private boolean showRomanization;
    private boolean showTranslation;
    private String japaneseMode;
    private String chineseMode;
    private String koreanMode;
    private String cyrillicMode;

    private TransliterationShellState(SettingsReader reader,
            boolean showRomanization,
            boolean showTranslation,
            String japaneseMode,
            String chineseMode,
            String koreanMode,
            String cyrillicMode) {
        this.reader = reader;
        this.showRomanization = showRomanization;
        this.showTranslation = showTranslation;
        this.japaneseMode = japaneseMode;
        this.chineseMode = chineseMode;
        this.koreanMode = koreanMode;
        this.cyrillicMode = cyrillicMode;
    }

    public static TransliterationShellState read(SettingsReader reader) {
        return new TransliterationShellState(
                reader,
                Boolean.TRUE.equals(reader.get(Settings.NATIVE_SPICY_ROMANIZATION)),
                Boolean.TRUE.equals(reader.get(Settings.NATIVE_SPICY_TRANSLATION)),
                safe(reader.get(Settings.LAST_JAPANESE_CYCLE_MODE)),
                LyricsDisplayMode.normalizeChineseMode(
                        reader.get(Settings.LAST_CHINESE_CYCLE_MODE)),
                KoreanDisplayMode.valueOfSetting(reader.get(Settings.LAST_KOREAN_CYCLE_MODE)),
                safe(reader.get(Settings.LAST_CYRILLIC_CYCLE_MODE)));
    }

    public boolean showRomanization() {
        return showRomanization;
    }

    public boolean showTranslation() {
        return showTranslation;
    }

    public String japaneseMode() {
        return japaneseMode;
    }

    public String chineseMode() {
        return chineseMode;
    }

    public String koreanMode() {
        return koreanMode;
    }

    public String cyrillicMode() {
        return cyrillicMode;
    }

    public ChineseCharacterForm characterForm() {
        return ChineseCharacterForm.fromSetting(reader.get(Settings.CHINESE_CHARACTER_FORM));
    }

    public void setShowRomanization(boolean value) {
        showRomanization = value;
    }

    public void setShowTranslation(boolean value) {
        showTranslation = value;
    }

    /** Flips the translation toggle and returns its new visibility. */
    public boolean toggleTranslation() {
        showTranslation = !showTranslation;
        return showTranslation;
    }

    /** Takes the modes a cycle stopped on, normalized to the labels the settings store. */
    public void setModes(String japaneseMode, String chineseMode, String koreanMode,
                         String cyrillicMode) {
        this.japaneseMode = safe(japaneseMode);
        this.chineseMode = LyricsDisplayMode.normalizeChineseMode(chineseMode);
        this.koreanMode = safe(koreanMode);
        this.cyrillicMode = safe(cyrillicMode);
    }

    /** Persists the shell's show/hide flags and last cycle modes; the user gates stay untouched. */
    public void write(SettingsWriter writer) {
        writer.put(Settings.NATIVE_SPICY_ROMANIZATION, showRomanization);
        writer.put(Settings.NATIVE_SPICY_TRANSLATION, showTranslation);
        writer.put(Settings.LAST_JAPANESE_CYCLE_MODE, japaneseMode);
        writer.put(Settings.LAST_CHINESE_CYCLE_MODE, chineseMode);
        writer.put(Settings.LAST_KOREAN_CYCLE_MODE, koreanMode);
        writer.put(Settings.LAST_CYRILLIC_CYCLE_MODE, cyrillicMode);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
