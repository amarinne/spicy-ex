package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.lyrics.ai.AiSettings;
import com.eza.spicyex.lyrics.language.ChineseOutputStyle;
import com.eza.spicyex.lyrics.language.RomanizationOptions;
import com.eza.spicyex.lyrics.session.LayerConfigIds;
import com.eza.spicyex.settings.SettingsReader;
import com.eza.spicyex.settings.registry.SettingsSnapshot;
import com.eza.spicyex.settings.values.TranslationBackend;
import com.eza.spicyex.testsupport.FakeAndroidContext;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * The Translation holder's value space and the identities built from it.
 *
 * <p>Every row pins the exact string the Meaning layer already keyed its cache and run retirement
 * to, so a refactor that moves the read cannot silently move a paid or stored identity. The
 * Romanization rows pin the Sound-side key fragment that must stay untouched by this holder.
 */
public final class TranslationConfigIdentityTest {

    @Test public void defaultsReadTheSchemaAndTheShippedFlow() {
        TranslationConfig config = TranslationConfig.read(null);
        assertFalse(config.enabled);
        assertEquals(TranslationBackend.GOOGLE_UNOFFICIAL, config.backend);
        assertEquals("en", config.target);
        assertEquals("auto", config.sourceMode);
        assertEquals("auto", config.sourceLanguage);
        assertEquals(AiSettings.MeaningFlow.GOOGLE_DRAFT, config.meaningFlow);
        assertTrue(config.usesGoogleBackend());
        assertTrue(config.meaningUsesGoogleBaseline());
        assertEquals("meaning-v1|on=0|backend=google_unofficial|target=en"
                        + "|sourceMode=auto|source=auto|flow=google_draft",
                config.identityInputs().configId());
    }

    @Test public void enabledGoogleTargetUsesTheStoredValues() {
        TranslationConfig config = TranslationConfig.read(new MapReader()
                .put(Settings.TRANSLATION_ENABLED, true)
                .put(Settings.TRANSLATION_TARGET, "es"));
        assertTrue(config.enabled);
        assertEquals("es", config.target);
        assertEquals("meaning-v1|on=1|backend=google_unofficial|target=es"
                        + "|sourceMode=auto|source=auto|flow=google_draft",
                config.identityInputs().configId());
    }

    @Test public void providerTargetManualSourcePreview() {
        TranslationConfig config = TranslationConfig.read(new MapReader()
                .put(Settings.TRANSLATION_ENABLED, true)
                .put(Settings.TRANSLATION_BACKEND, "provider")
                .put(Settings.TRANSLATION_TARGET, "ja")
                .put(Settings.SOURCE_LANGUAGE_MODE, "manual")
                .put(Settings.SOURCE_LANGUAGE, "hi")
                .put(Settings.AI_TRANSLATION_PIPELINE, "Google preview"));
        assertTrue(config.enabled);
        assertEquals(TranslationBackend.PROVIDER, config.backend);
        assertFalse(config.usesGoogleBackend());
        assertEquals("hi", config.sourceLanguage);
        assertEquals(AiSettings.MeaningFlow.GOOGLE_PREVIEW, config.meaningFlow);
        assertFalse(config.meaningUsesGoogleBaseline());
        assertEquals("meaning-v1|on=1|backend=provider|target=ja"
                        + "|sourceMode=manual|source=hi|flow=google_preview",
                config.identityInputs().configId());
    }

    @Test public void manualSourceModeKeepsItsLanguageAndTheIdentityUsesIt() {
        TranslationConfig config = TranslationConfig.read(new MapReader()
                .put(Settings.TRANSLATION_ENABLED, true)
                .put(Settings.TRANSLATION_TARGET, "zh-TW")
                .put(Settings.SOURCE_LANGUAGE_MODE, "manual")
                .put(Settings.SOURCE_LANGUAGE, "zh")
                .put(Settings.AI_TRANSLATION_PIPELINE, "AI only"));
        assertEquals("zh", config.sourceLanguage);
        assertEquals(AiSettings.MeaningFlow.AI_ONLY, config.meaningFlow);
        assertEquals("meaning-v1|on=1|backend=google_unofficial|target=zh-TW"
                        + "|sourceMode=manual|source=zh|flow=ai_only",
                config.identityInputs().configId());
    }

    @Test public void everyFlowProducesItsOwnGoldenIdentity() {
        String[] labels = {"Google preview", "Google draft", "AI only"};
        String[] tokens = {"google_preview", "google_draft", "ai_only"};
        for (int i = 0; i < labels.length; i++) {
            TranslationConfig config = TranslationConfig.read(new MapReader()
                    .put(Settings.TRANSLATION_ENABLED, true)
                    .put(Settings.AI_TRANSLATION_PIPELINE, labels[i]));
            assertEquals(tokens[i], config.identityInputs().meaningFlow);
            assertEquals("meaning-v1|on=1|backend=google_unofficial|target=en"
                            + "|sourceMode=auto|source=auto|flow=" + tokens[i],
                    config.identityInputs().configId());
        }
    }

    @Test public void invalidStoredValuesFallBackExactlyAsTheSchemaDoes() {
        TranslationConfig config = TranslationConfig.read(new MapReader()
                .put(Settings.TRANSLATION_ENABLED, true)
                .put(Settings.TRANSLATION_BACKEND, "bogus")
                .put(Settings.TRANSLATION_TARGET, "xx")
                .put(Settings.SOURCE_LANGUAGE_MODE, "bogus")
                .put(Settings.SOURCE_LANGUAGE, "xx")
                .put(Settings.AI_TRANSLATION_PIPELINE, "bogus"));
        assertEquals(TranslationBackend.GOOGLE_UNOFFICIAL, config.backend);
        assertEquals("en", config.target);
        assertEquals("auto", config.sourceMode);
        assertEquals("auto", config.sourceLanguage);
        assertEquals(AiSettings.MeaningFlow.GOOGLE_DRAFT, config.meaningFlow);
        assertEquals("meaning-v1|on=1|backend=google_unofficial|target=en"
                        + "|sourceMode=auto|source=auto|flow=google_draft",
                config.identityInputs().configId());
    }

    /** The retired "disabled" backend label still switches the Meaning gate off. */
    @Test public void disabledBackendLabelStillMeansOff() {
        TranslationConfig config = TranslationConfig.read(new RawReader()
                .put(Settings.TRANSLATION_ENABLED, true)
                .put(Settings.TRANSLATION_BACKEND, "disabled"));
        assertFalse(config.enabled);
        assertEquals(TranslationBackend.GOOGLE_UNOFFICIAL, config.backend);
        assertEquals("meaning-v1|on=0|backend=google_unofficial|target=en"
                        + "|sourceMode=auto|source=auto|flow=google_draft",
                config.identityInputs().configId());
    }

    @Test public void capturedSnapshotKeepsTheSameIdentity() {
        SettingsStore store = new SettingsStore(new FakeAndroidContext());
        store.put(Settings.TRANSLATION_ENABLED, true);
        store.put(Settings.TRANSLATION_BACKEND, "provider");
        store.put(Settings.TRANSLATION_TARGET, "fr");
        store.put(Settings.SOURCE_LANGUAGE_MODE, "manual");
        store.put(Settings.SOURCE_LANGUAGE, "ko");
        store.put(Settings.AI_TRANSLATION_PIPELINE, "Google preview");
        TranslationConfig live = TranslationConfig.read(store);
        TranslationConfig captured = TranslationConfig.read(SettingsSnapshot.capture(store));
        assertEquals(live.identityInputs().configId(), captured.identityInputs().configId());
        assertEquals("meaning-v1|on=1|backend=provider|target=fr"
                        + "|sourceMode=manual|source=ko|flow=google_preview",
                captured.identityInputs().configId());
    }

    @Test public void translationBackendLabelsAreTheExistingSchemaValues() {
        assertEquals("google_unofficial", TranslationBackend.GOOGLE_UNOFFICIAL.storedLabel());
        assertEquals("provider", TranslationBackend.PROVIDER.storedLabel());
        assertEquals(TranslationBackend.GOOGLE_UNOFFICIAL,
                com.eza.spicyex.settings.values.StoredValues.fromStored(
                        TranslationBackend.class, "google_unofficial"));
        assertEquals(TranslationBackend.PROVIDER,
                com.eza.spicyex.settings.values.StoredValues.fromStored(
                        TranslationBackend.class, "provider"));
        assertEquals(TranslationBackend.GOOGLE_UNOFFICIAL,
                com.eza.spicyex.settings.values.StoredValues.fromStored(
                        TranslationBackend.class, "unknown"));
    }

    @Test public void romanizationCacheKeysPreserveUnchangedReadingSystems() {
        assertEquals("cn=pinyin|kr=rrStandard|cnStyle=canonical|tn=0|cy=Russian|cs=0",
                RomanizationOptions.DEFAULTS.cacheKey());
        assertEquals("cn=jyutping|kr=Off|cnStyle=canonical|tn=1|cy=Ukrainian|cs=1",
                new RomanizationOptions("jyutping", "Off", true, "Ukrainian", true)
                        .cacheKey());
        assertEquals("cn=pinyin|kr=vnPronunciation|cnStyle=canonical"
                        + "|krStyle=eza@eza-2026-10-08.1|tn=0|cy=Russian|cs=0",
                new RomanizationOptions("pinyin", "vnPronunciation", false, "Russian", false)
                        .cacheKey());
        assertEquals("cn=pinyin|kr=rrStandard|cnStyle=eza@eza-2026-10-08.1"
                        + "|tn=0|cy=Russian|cs=0",
                new RomanizationOptions("pinyin", "rrStandard", false, "Russian", false,
                        ChineseOutputStyle.EZA).cacheKey());
        assertEquals("cn=jyutping|kr=rrStandard|cnStyle=eza@eza-2026-10-25.1"
                        + "|tn=1|cy=Ukrainian|cs=0",
                new RomanizationOptions("jyutping-eza", "rrStandard", true, "Ukrainian", false)
                        .cacheKey());
    }

    /** The holder feeds the layer identity without changing its format. */
    @Test public void identityIsTheLayerConfigIdComposition() {
        TranslationConfig config = TranslationConfig.read(new MapReader()
                .put(Settings.TRANSLATION_ENABLED, true)
                .put(Settings.TRANSLATION_BACKEND, "provider")
                .put(Settings.TRANSLATION_TARGET, "ko")
                .put(Settings.AI_TRANSLATION_PIPELINE, "Google draft"));
        TranslationConfig.IdentityInputs inputs = config.identityInputs();
        assertEquals(LayerConfigIds.meaning(inputs.enabled, inputs.backend, inputs.target,
                inputs.sourceMode, inputs.sourceLanguage, inputs.meaningFlow), inputs.configId());
        assertTrue(inputs.configId().startsWith("meaning-v1|"));
        assertFalse(inputs.configId().contains("sound-"));
    }

    /** Real readers coerce before this holder sees a value. */
    private static final class MapReader implements SettingsReader {
        private final Map<String, Object> values = new HashMap<>();

        MapReader put(Settings.Setting<?> setting, Object value) {
            values.put(setting.key, value);
            return this;
        }

        @SuppressWarnings("unchecked")
        @Override public <T> T get(Settings.Setting<T> setting) {
            Object value = values.get(setting.key);
            return value == null ? setting.defaultValue : setting.coerce(value);
        }
    }

    /** Deliberately skips coercion, to pin the gate's own "disabled" label handling. */
    private static final class RawReader implements SettingsReader {
        private final Map<String, Object> values = new HashMap<>();

        RawReader put(Settings.Setting<?> setting, Object value) {
            values.put(setting.key, value);
            return this;
        }

        @SuppressWarnings("unchecked")
        @Override public <T> T get(Settings.Setting<T> setting) {
            Object value = values.get(setting.key);
            return value == null ? setting.defaultValue : (T) value;
        }
    }
}
