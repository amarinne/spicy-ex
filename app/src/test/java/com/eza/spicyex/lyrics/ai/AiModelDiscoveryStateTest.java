package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.*;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/** Discovery completion and picker selection must stay with the request's provider and key. */
public class AiModelDiscoveryStateTest {
    private SettingsStore store;
    private AiSettings settings;
    private AiCredentialStore keys;
    private final AiModelDiscoveryState discovery = new AiModelDiscoveryState();

    @Before public void setup() {
        FakeAndroidContext context = new FakeAndroidContext();
        store = new SettingsStore(context);
        keys = new AiCredentialStore(context, new AiSettingsSelectionBehaviorTest.PlainCipher());
        settings = new AiSettings(store, keys);
        store.put(Settings.AI_ENABLED, true);
        store.put(Settings.AI_PROVIDER, AiSettings.PROVIDER_OPENAI);
        keys.save(settings.credentialScope(), "fixture-key-a");
    }

    @Test public void discoveryWorksBeforeAModelIsChosenAndCompletionPublishesOnlyOnce() {
        assertEquals("", settings.modelName());
        AiModelDiscoveryState.Attempt attempt = discovery.begin(settings);
        assertNotNull(attempt);
        assertFalse(discovery.canSelect(settings, attempt));
        assertTrue(discovery.finish(settings, attempt));
        assertTrue(discovery.canSelect(settings, attempt));
        assertFalse(discovery.finish(settings, attempt));
        assertEquals("", settings.modelName());
    }

    @Test public void missingKeyEndpointOrDisabledFeatureCannotStartDiscovery() {
        assertNull(discovery.begin(null));
        store.put(Settings.AI_ENABLED, false);
        assertNull(discovery.begin(settings));
        store.put(Settings.AI_ENABLED, true);
        keys.delete(settings.credentialScope());
        assertNull(discovery.begin(settings));
        store.put(Settings.AI_PROVIDER, AiSettings.PROVIDER_CUSTOM);
        keys.save(settings.credentialScope(), "fixture-key-a");
        assertNull(discovery.begin(settings));
    }

    @Test public void providerChangeRejectsCompletionAndTheDisplayedPicker() {
        AiModelDiscoveryState.Attempt attempt = discovery.begin(settings);
        assertTrue(discovery.finish(settings, attempt));
        store.put(Settings.AI_PROVIDER, AiSettings.PROVIDER_DEEPSEEK);
        keys.save(settings.credentialScope(), "fixture-key-a");
        assertFalse(discovery.canSelect(settings, attempt));
        assertEquals("", settings.modelName());
        AiModelDiscoveryState.Attempt current = discovery.begin(settings);
        store.put(Settings.AI_PROVIDER, AiSettings.PROVIDER_OPENAI);
        assertFalse(discovery.finish(settings, current));
    }

    @Test public void keyRotationAndDeletionRejectOldCompletionEvenWithoutAnotherLookup() {
        AiModelDiscoveryState.Attempt rotated = discovery.begin(settings);
        keys.save(settings.credentialScope(), "fixture-key-b");
        assertFalse(discovery.finish(settings, rotated));
        assertFalse(discovery.canSelect(settings, rotated));
        AiModelDiscoveryState.Attempt deleted = discovery.begin(settings);
        keys.delete(settings.credentialScope());
        assertFalse(discovery.finish(settings, deleted));
    }

    @Test public void endpointChangeRejectsCustomResultsAndSelection() {
        store.put(Settings.AI_PROVIDER, AiSettings.PROVIDER_CUSTOM);
        store.put(Settings.AI_ENDPOINT, "https://a.example/v1");
        keys.save(settings.credentialScope(), "fixture-key-a");
        AiModelDiscoveryState.Attempt attempt = discovery.begin(settings);
        assertTrue(discovery.finish(settings, attempt));
        store.put(Settings.AI_ENDPOINT, "https://b.example/v1");
        assertFalse(discovery.canSelect(settings, attempt));
        AiModelDiscoveryState.Attempt other = discovery.begin(settings);
        store.put(Settings.AI_ENDPOINT, "https://a.example/v1");
        assertFalse(discovery.finish(settings, other));
    }

    @Test public void secondLookupCancelsTheFirstAndRejectsItsDelayedCompletion() {
        AiModelDiscoveryState.Attempt old = discovery.begin(settings);
        AiModelDiscoveryState.Attempt current = discovery.begin(settings);
        assertTrue(old.signal.isAborted());
        assertEquals("model_discovery_superseded", old.signal.reason());
        assertFalse(discovery.finish(settings, old));
        assertFalse(discovery.canSelect(settings, old));
        assertTrue(discovery.finish(settings, current));
    }

    @Test public void newLookupRetiresAnAlreadyDisplayedPicker() {
        AiModelDiscoveryState.Attempt old = discovery.begin(settings);
        assertTrue(discovery.finish(settings, old));
        AiModelDiscoveryState.Attempt current = discovery.begin(settings);
        assertFalse(discovery.canSelect(settings, old));
        assertFalse(old.signal.isAborted());
        assertTrue(discovery.finish(settings, current));
    }

    @Test public void providerAtoBtoAStillRejectsTheOriginalLookup() {
        AiModelDiscoveryState.Attempt old = discovery.begin(settings);
        store.put(Settings.AI_PROVIDER, AiSettings.PROVIDER_DEEPSEEK);
        keys.save(settings.credentialScope(), "fixture-key-b");
        assertNotNull(discovery.begin(settings));
        store.put(Settings.AI_PROVIDER, AiSettings.PROVIDER_OPENAI);
        AiModelDiscoveryState.Attempt current = discovery.begin(settings);
        assertFalse(discovery.finish(settings, old));
        assertTrue(discovery.finish(settings, current));
    }

    @Test public void timeoutCanReportOnceButCannotOfferModelsOrAcceptALateResult() {
        AiModelDiscoveryState.Attempt attempt = discovery.begin(settings);
        attempt.signal.abort("model_discovery_timeout");
        assertTrue(discovery.finish(settings, attempt));
        assertFalse(discovery.canSelect(settings, attempt));
        assertFalse(discovery.finish(settings, attempt));
    }

    @Test public void disablingFeatureRejectsPendingResultsAndPickerSelections() {
        AiModelDiscoveryState.Attempt attempt = discovery.begin(settings);
        store.put(Settings.AI_ENABLED, false);
        assertFalse(discovery.finish(settings, attempt));
        assertFalse(discovery.canSelect(settings, attempt));
    }

    @Test public void unrelatedModelChoiceDoesNotRetireAValidProviderList() {
        AiModelDiscoveryState.Attempt attempt = discovery.begin(settings);
        settings.setModelName("another-current-model");
        assertTrue(discovery.finish(settings, attempt));
        assertTrue(discovery.canSelect(settings, attempt));
    }

    @Test public void realListRequestUsesCapturedEndpointAndKeyAfterSettingsChange() {
        store.put(Settings.AI_PROVIDER, AiSettings.PROVIDER_CUSTOM);
        store.put(Settings.AI_ENDPOINT, "https://a.example/v1");
        keys.save(settings.credentialScope(), "fixture-key-a");
        RecordingTransport transport = new RecordingTransport();
        AiSettings.DiscoverySnapshot snapshot = settings.snapshotDiscovery(transport);
        store.put(Settings.AI_ENDPOINT, "https://b.example/v1");
        keys.save(settings.credentialScope(), "fixture-key-b");
        AiModelListResult result = snapshot.provider.listModels(new AiSignal());
        assertTrue(result.ok);
        assertEquals("https://a.example/v1/models", transport.url);
        assertEquals("Bearer fixture-key-a", transport.authorization);
        assertEquals("fixture-model", result.models.get(0).name);
        assertFalse(snapshot.matches(settings));
    }

    private static final class RecordingTransport implements AiGeminiProvider.Transport {
        String url;
        String authorization;
        @Override public AiHttp.Result get(String url, Map<String, String> headers,
                                           AiSignal signal, int maxBytes) {
            this.url = url;
            authorization = headers.get("Authorization");
            return AiHttp.Result.of(200, "{\"data\":[{\"id\":\"fixture-model\"}]}", 0);
        }
        @Override public AiHttp.Result postJson(String url, Map<String, String> headers,
                                                String body, AiSignal signal, int maxBytes) {
            throw new AssertionError("Discovery must not generate content.");
        }
    }
}
