package com.eza.spicyex.lyrics.session;

/**
 * Layer configuration identities.
 *
 * <p>This is the split that keeps the two lanes independent: a Sound config ID contains only Sound
 * inputs, a Meaning config ID only Meaning inputs. Changing the translation target must not change
 * the Sound key, and changing Korean romanization mode must not change the Meaning key.
 */
public final class LayerConfigIds {
    /** Bump when the Sound processor's output shape changes. Independent of the Meaning contract. */
    public static final int SOUND_CONTRACT_VERSION = 1;
    /** Bump when the Meaning backend's output shape changes. Independent of the Sound contract. */
    public static final int MEANING_CONTRACT_VERSION = 1;

    private LayerConfigIds() {
    }

    /**
     * @param romanizationOptionsKey {@code RomanizationOptions.cacheKey()} — orthography and mode
     * @param sourceLanguage         effective source language for reading selection
     * @param readingSchemaVersion   reading render-plan schema the artifact was produced under
     */
    public static String sound(boolean enabled, String romanizationOptionsKey, String sourceLanguage,
                               int readingSchemaVersion) {
        return "sound-v" + SOUND_CONTRACT_VERSION
                + "|on=" + (enabled ? 1 : 0)
                + "|" + Digests.nz(romanizationOptionsKey)
                + "|src=" + Digests.nz(sourceLanguage)
                + "|schema=" + readingSchemaVersion;
    }

    public static String meaning(boolean enabled, String backend, String targetLanguage,
                                 String sourceLanguageMode, String sourceLanguage) {
        return "meaning-v" + MEANING_CONTRACT_VERSION
                + "|on=" + (enabled ? 1 : 0)
                + "|backend=" + Digests.nz(backend)
                + "|target=" + Digests.nz(targetLanguage)
                + "|sourceMode=" + Digests.nz(sourceLanguageMode)
                + "|source=" + Digests.nz(sourceLanguage);
    }
}
