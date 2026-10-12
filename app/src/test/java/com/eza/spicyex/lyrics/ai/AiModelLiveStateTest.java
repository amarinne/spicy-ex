package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.testsupport.FakeAndroidContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

public class AiModelLiveStateTest {
    private SettingsStore store;
    private AiSettings settings;
    private AiCredentialStore keys;

    @Before public void setUp() {
        FakeAndroidContext context = new FakeAndroidContext();
        store = new SettingsStore(context);
        keys = new AiCredentialStore(context, new AiSettingsSelectionBehaviorTest.PlainCipher());
        settings = new AiSettings(store, keys);
        store.put(Settings.AI_ENABLED, true);
        select(AiSettings.PROVIDER_OPENAI, "model-a");
        keys.save(settings.credentialScope(), "key-a");
        AiModelLiveState.invalidate();
        AiLastProbe.clearForTest();
    }

    @After public void reset() {
        AiModelLiveState.invalidate();
        AiLastProbe.clearForTest();
    }

    @Test public void missingConfigurationNeverLooksLive() {
        assertNull(AiModelLiveState.begin(null, false));
        assertFalse(AiModelLiveState.isLive(null));
        keys.delete(settings.credentialScope());
        assertNull(AiModelLiveState.begin(settings, false));
    }

    @Test public void acceptedAttemptPersistsOnlyItsOwnMeasurement() {
        AiModelLiveState.Attempt attempt = AiModelLiveState.begin(settings, false);
        assertNotNull(attempt);
        assertNull(AiModelLiveState.begin(settings, false));
        AiModelProbe.Result result = success(180);
        assertTrue(AiModelLiveState.finish(settings, attempt, result));
        assertTrue(AiModelLiveState.isLive(settings));
        assertSame(result, AiLastProbe.get());
        assertEquals(Integer.valueOf(180), measurement(Settings.AI_PROBE_OPENAI,
                "api.openai.com", "model-a").completionTokens);
        assertEquals(116, settings.model().reasoningAllowanceTokens);
        assertNull(AiModelLiveState.begin(settings, false));
        assertFalse(AiModelLiveState.finish(settings, attempt, failure()));
        assertTrue(AiModelLiveState.isLive(settings));
    }

    @Test public void oldProviderCannotPublishIntoNewProviderScope() {
        AiModelLiveState.Attempt old = AiModelLiveState.begin(settings, false);
        select(AiSettings.PROVIDER_DEEPSEEK, "model-b");
        keys.save(settings.credentialScope(), "key-b");
        AiModelLiveState.Attempt current = AiModelLiveState.begin(settings, false);
        assertFalse(AiModelLiveState.finish(settings, old, success(900)));
        assertNoPublication();
        assertTrue(AiModelLiveState.finish(settings, current, success(120)));
        assertNull(measurement(Settings.AI_PROBE_OPENAI, "api.openai.com", "model-a"));
        assertEquals(Integer.valueOf(120), measurement(Settings.AI_PROBE_DEEPSEEK,
                "api.deepseek.com", "model-b").completionTokens);
    }

    @Test public void modelChangeRejectsOldResultBeforeNewProbeBegins() {
        AiModelLiveState.Attempt old = AiModelLiveState.begin(settings, false);
        settings.setModelName("model-b");
        assertFalse(AiModelLiveState.finish(settings, old, success(900)));
        assertNoPublication();
        AiModelLiveState.Attempt current = AiModelLiveState.begin(settings, false);
        assertNotNull(current);
        assertTrue(AiModelLiveState.finish(settings, current, failure()));
        assertEquals("fixture_failure", settings.lastProbeFailureToken());
        assertNull(measurement(Settings.AI_PROBE_OPENAI, "api.openai.com", "model-a"));
    }

    @Test public void modelAtoBtoARejectsOriginalCompletion() {
        AiModelLiveState.Attempt old = AiModelLiveState.begin(settings, false);
        settings.setModelName("model-b");
        assertNotNull(AiModelLiveState.begin(settings, false));
        settings.setModelName("model-a");
        AiModelLiveState.Attempt current = AiModelLiveState.begin(settings, false);
        assertFalse(AiModelLiveState.finish(settings, old, success(900)));
        assertNoPublication();
        assertTrue(AiModelLiveState.finish(settings, current, success(120)));
        assertEquals(Integer.valueOf(120), measurement(Settings.AI_PROBE_OPENAI,
                "api.openai.com", "model-a").completionTokens);
    }

    @Test public void keyAtoBtoARejectsOriginalCompletion() {
        AiModelLiveState.Attempt old = AiModelLiveState.begin(settings, false);
        keys.save(settings.credentialScope(), "key-b");
        AiModelLiveState.invalidate();
        assertNotNull(AiModelLiveState.begin(settings, false));
        keys.save(settings.credentialScope(), "key-a");
        AiModelLiveState.invalidate();
        AiModelLiveState.Attempt current = AiModelLiveState.begin(settings, false);
        assertFalse(AiModelLiveState.finish(settings, old, success(900)));
        assertNoPublication();
        assertTrue(AiModelLiveState.finish(settings, current, success(120)));
    }

    @Test public void explicitTestSupersedesInitialAndEarlierExplicitTests() {
        AiModelLiveState.Attempt initial = AiModelLiveState.begin(settings, false);
        AiModelLiveState.Attempt first = AiModelLiveState.begin(settings, true);
        AiModelLiveState.Attempt current = AiModelLiveState.begin(settings, true);
        assertFalse(AiModelLiveState.finish(settings, initial, success(900)));
        assertFalse(AiModelLiveState.finish(settings, first, failure()));
        assertNoPublication();
        AiModelProbe.Result result = success(120);
        assertTrue(AiModelLiveState.finish(settings, current, result));
        assertFalse(AiModelLiveState.finish(settings, initial, failure()));
        assertSame(result, AiLastProbe.get());
        assertTrue(AiModelLiveState.isLive(settings));
    }

    @Test public void invalidationRejectsCompletionWithoutReplacementAttempt() {
        AiModelLiveState.Attempt old = AiModelLiveState.begin(settings, false);
        AiModelLiveState.invalidate();
        assertFalse(AiModelLiveState.finish(settings, old, success(900)));
        assertNoPublication();
    }

    @Test public void disabledFeatureRejectsPendingCompletion() {
        AiModelLiveState.Attempt old = AiModelLiveState.begin(settings, false);
        store.put(Settings.AI_ENABLED, false);
        assertFalse(AiModelLiveState.finish(settings, old, success(900)));
        assertNoPublication();
    }

    @Test public void runtimeFailureIsAcceptedOnceWithoutInventingMeasurement() {
        AiModelLiveState.Attempt attempt = AiModelLiveState.begin(settings, true);
        assertTrue(AiModelLiveState.finish(settings, attempt, null));
        assertNoPublication();
        assertNull(AiModelLiveState.begin(settings, false));
    }

    @Test public void capturedRequestKeepsEndpointModelAndKeyAfterSettingsChange() {
        select(AiSettings.PROVIDER_CUSTOM, "model-a");
        store.put(Settings.AI_ENDPOINT, "https://a.example/v1");
        keys.save(settings.credentialScope(), "key-a");
        RecordingTransport transport = new RecordingTransport();
        AiSettings.ProbeSnapshot snapshot = settings.snapshotProbe(transport);
        store.put(Settings.AI_ENDPOINT, "https://b.example/v1");
        settings.setModelName("model-b");
        keys.save(settings.credentialScope(), "key-b");
        AiModelProbe.probe(snapshot.provider, snapshot.config, null);
        assertEquals("https://a.example/v1/chat/completions", transport.url);
        assertEquals("Bearer key-a", transport.authorization);
        assertTrue(transport.body.contains("\"model\":\"model-a\""));
        assertFalse(transport.body.contains("model-b"));
    }

    private void assertNoPublication() {
        assertFalse(AiModelLiveState.isLive(settings));
        assertNull(AiLastProbe.get());
        assertEquals("", store.get(Settings.AI_PROBE_OPENAI));
        assertEquals("", store.get(Settings.AI_PROBE_DEEPSEEK));
    }

    private void select(String provider, String model) {
        store.put(Settings.AI_PROVIDER, provider);
        settings.setModelName(model);
    }

    private AiProbeMeasurement.Parsed measurement(Settings.Setting<String> setting,
                                                 String host, String model) {
        return AiProbeMeasurement.decode(store.get(setting), host, model);
    }

    private static AiModelProbe.Result success(int tokens) {
        return AiModelProbe.Result.success(new AiModelProbe.Trace("request", "response", null,
                AiUsage.of(10, tokens), 0));
    }

    private static AiModelProbe.Result failure() {
        return AiModelProbe.Result.failed("fixture_failure");
    }

    private static final class RecordingTransport implements AiGeminiProvider.Transport {
        String url;
        String authorization;
        String body;

        @Override public AiHttp.Result get(String url, Map<String, String> headers,
                                           AiSignal signal, int maxBytes) {
            throw new AssertionError("The fixture must not discover models.");
        }

        @Override public AiHttp.Result postJson(String url, Map<String, String> headers,
                                                String body, AiSignal signal, int maxBytes) {
            this.url = url;
            this.authorization = headers.get("Authorization");
            this.body = body;
            return AiHttp.Result.of(503, "", 0);
        }
    }
}
