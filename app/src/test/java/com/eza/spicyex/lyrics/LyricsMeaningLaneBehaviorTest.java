package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.lyrics.ai.AiCredentialStore;
import com.eza.spicyex.lyrics.ai.AiHttpTestControl;
import com.eza.spicyex.lyrics.ai.AiPaidRecord;
import com.eza.spicyex.lyrics.ai.AiRecordStore;
import com.eza.spicyex.lyrics.ai.AiRecordStores;
import com.eza.spicyex.lyrics.ai.AiRunConfig;
import com.eza.spicyex.lyrics.ai.AiMeaningRun;
import com.eza.spicyex.lyrics.ai.AiRequestLiveState;
import com.eza.spicyex.lyrics.ai.AiSettings;
import com.eza.spicyex.lyrics.ai.FakeAiRecordStore;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.CanonicalRow;
import com.eza.spicyex.lyrics.session.DerivedLayerArtifact;
import com.eza.spicyex.lyrics.session.LayerFailure;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.LayerRunCoalescer;
import com.eza.spicyex.testsupport.FakeAndroidContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import okhttp3.OkHttpClient;

/**
 * The Meaning lane's observable contracts, driven through {@code start(...)} on the JVM.
 *
 * <p>The lane is Android-coupled only by its surface dispatcher, its clock, its configuration
 * source, its paid store, and its executors; those are the test seam, so everything else — which
 * flow wins, who owns the coalescer key, what reaches a paid provider — is asserted as behaviour
 * rather than as source text.
 */
public final class LyricsMeaningLaneBehaviorTest {

    private static final String BACKEND = "google_unofficial";
    private static final String GOOGLE_DRAFT_TEXT = "T:";
    private static final long WAIT_MS = 5_000L;

    private FakeAndroidContext context;
    private SettingsStore settingsStore;
    private AiCredentialStore credentials;
    private AiSettings aiSettings;
    private FakeAiRecordStore recordStore;
    private GateExecutor aiExecutor;
    private ExecutorService laneExecutor;
    private ControllableProvider provider;
    private EventLog events;
    private final List<ExecutorService> spareExecutors = new ArrayList<>();

    @Before public void setUp() throws Exception {
        context = new FakeAndroidContext();
        settingsStore = new SettingsStore(context);
        credentials = new AiCredentialStore(context, new IdentityCipher());
        aiSettings = new AiSettings(settingsStore, credentials);
        recordStore = new FakeAiRecordStore();
        laneExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "lane-behavior"));
        aiExecutor = new GateExecutor("ai-behavior");
        provider = new ControllableProvider();
        events = new EventLog();
        AiRecordStores.installFactoryForTest((Context ignored) -> recordStore);
        AiHttpTestControl.install(401);
        AiRequestLiveState.clearForTest();
    }

    @After public void tearDown() throws Exception {
        aiExecutor.openGate();
        for (ExecutorService spare : spareExecutors) spare.shutdownNow();
        laneExecutor.shutdownNow();
        aiExecutor.shutdownNow();
        AiRecordStores.installFactoryForTest(null);
        AiHttpTestControl.restore();
        AiRequestLiveState.clearForTest();
        clearCoalescer();
    }

    // --- Google lane ------------------------------------------------------

    @Test public void googleLanePublishesEveryPendingTranslation() throws Exception {
        LyricsMeaningLane lane = newLane();
        Recorder callback = new Recorder("a");

        assertTrue(lane.start("track", 1, pendingDocument(), BACKEND, "en", "ko", "ko",
                false, alwaysCurrent(), callback));

        assertTrue(events.await("a:complete", WAIT_MS));
        assertTrue(events.index("google-translate:lane-behavior") >= 0);
        assertTrue(events.index("google-translate:lane-behavior") < events.index("a:complete"));
    }

    // --- Coalescer contracts ---------------------------------------------

    /**
     * Issue #5: the AI path used to count a metric for a deferred run and drop it, so a tap during
     * an automatic run reported "did not start" and never ran at all.
     */
    @Test public void deferredAiRunReplaysInsteadOfBeingDropped() throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_AI_ONLY, false);
        LyricsMeaningLane owner = newLane();
        LyricsMeaningLane otherSurface = newLane(openAiExecutor("ai-other"));
        Recorder first = new Recorder("first");
        Recorder second = new Recorder("second");
        LyricsDocument document = notPendingDocument();

        assertTrue(owner.start("track", 1, document, BACKEND, "en", "ko", "ko",
                false, alwaysCurrent(), first));
        // The owner's job is parked behind a closed gate, so the key is still claimed.
        assertFalse(otherSurface.start("track", 1, document, BACKEND, "en", "ko", "ko",
                false, alwaysCurrent(), second));
        assertFalse(events.await("second:complete", 250));

        aiExecutor.openGate();

        assertTrue("expected first:complete, saw " + events.snapshot(),
                events.await("first:complete", WAIT_MS));
        assertTrue("the deferred run must replay and settle; saw " + events.snapshot(),
                events.await("second:complete", WAIT_MS));
    }

    /** A deferred explicit request is queued work, so the surface may honestly call it running. */
    @Test public void deferredExplicitAiRequestStillCountsAsStarted() throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_AI_ONLY, false);
        LyricsMeaningLane owner = newLane();
        LyricsMeaningLane otherSurface = newLane(openAiExecutor("ai-other"));
        Recorder first = new Recorder("first");
        Recorder second = new Recorder("second");
        LyricsDocument document = notPendingDocument();

        assertTrue(owner.start("track", 1, document, BACKEND, "en", "ko", "ko",
                false, alwaysCurrent(), first));
        assertTrue("the queued tap is started even though it had to wait",
                otherSurface.start("track", 1, document, BACKEND, "en", "ko", "ko",
                        true, alwaysCurrent(), second));

        aiExecutor.openGate();

        assertTrue("expected first:complete, saw " + events.snapshot(),
                events.await("first:complete", WAIT_MS));
        assertTrue("expected second:complete, saw " + events.snapshot(),
                events.await("second:complete", WAIT_MS));
    }

    /**
     * The key is claimed before dispatch; a refused execution would otherwise hold it forever with
     * no owner to release it, and every later request for that song would be told work is in flight.
     */
    @Test public void refusedAiDispatchReleasesTheCoalescerKey() throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_AI_ONLY, false);
        LyricsMeaningLane lane = newLane();
        Recorder refused = new Recorder("refused");
        Recorder later = new Recorder("later");
        LyricsDocument document = notPendingDocument();

        aiExecutor.rejecting(true);
        assertFalse(lane.start("track", 1, document, BACKEND, "en", "ko", "ko",
                false, alwaysCurrent(), refused));
        assertFalse(events.await("refused:complete", 250));

        aiExecutor.rejecting(false);
        aiExecutor.openGate();
        assertTrue(lane.start("track", 1, document, BACKEND, "en", "ko", "ko",
                false, alwaysCurrent(), later));
        assertTrue("a later request must claim the abandoned key",
                events.await("later:complete", WAIT_MS));
    }

    // --- Billing boundary -------------------------------------------------

    /**
     * Automatic Meaning must not bill for source lyrics already in the translation target: the
     * trigger is only a request when there is translation work to do for it.
     */
    @Test public void automaticAiRequestRequiresTranslationWork() throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_AI_ONLY, true);
        giveCredential();
        aiExecutor.openGate();
        LyricsMeaningLane lane = newLane();

        Recorder idle = new Recorder("idle");
        assertTrue(lane.start("track", 1, notPendingDocument(), BACKEND, "en", "en", "en",
                false, alwaysCurrent(), idle));
        assertTrue("expected idle:complete, saw " + events.snapshot(),
                events.await("idle:complete", WAIT_MS));
        assertEquals("an automatic trigger with no translation work must not bill",
                0, AiHttpTestControl.count());
        assertEquals(AiRequestLiveState.Phase.NONE,
                AiRequestLiveState.diagnosticSnapshot(LayerKind.MEANING).current.phase);

        // Control: with work pending the same configuration does ask, so the guard above is the
        // only reason the first run stayed quiet.
        Recorder busy = new Recorder("busy");
        assertTrue(lane.start("track", 2, pendingDocument(), BACKEND, "en", "ko", "ko",
                false, alwaysCurrent(), busy));
        assertTrue(events.await("busy:complete", WAIT_MS));
        assertEquals("a pending translation does cross the billing boundary",
                1, AiHttpTestControl.count());
        assertNotEquals(AiRequestLiveState.Phase.NONE,
                AiRequestLiveState.diagnosticSnapshot(LayerKind.MEANING).current.phase);
    }

    // --- Google preview flow ---------------------------------------------

    /**
     * Preview must be its own branch: Google displays first and the AI job replaces it, while
     * AI-only never asks Google for display at all.
     */
    @Test public void previewPublishesGoogleFirstAndAiOnlyNeverDoes() throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_GOOGLE_PREVIEW, false);
        giveCredential();
        aiExecutor.openGate();

        Recorder preview = new Recorder("preview");
        assertTrue(newLane().start("track", 1, pendingDocument(), BACKEND, "en", "ko", "ko",
                true, alwaysCurrent(), preview));
        assertTrue("expected preview:complete, saw " + events.snapshot(),
                events.await("preview:complete", WAIT_MS));
        assertTrue("preview shows Google while AI works, saw " + events.snapshot(),
                events.index("preview:rerender") >= 0);

        configureAi(AiSettings.TRANSLATION_PIPELINE_AI_ONLY, false);
        Recorder aiOnly = new Recorder("aiOnly");
        assertTrue(newLane().start("track", 2, pendingDocument(), BACKEND, "en", "ko", "ko",
                true, alwaysCurrent(), aiOnly));
        assertTrue("expected aiOnly:complete, saw " + events.snapshot(),
                events.await("aiOnly:complete", WAIT_MS));
        assertEquals("AI-only has no Google display job to publish first: " + events.snapshot(),
                -1, events.index("aiOnly:rerender"));
    }

    /**
     * Google display work belongs on the lane executor; only the AI job waits behind the AI
     * executor's queue, so a parked AI job can never hold up the display.
     */
    @Test public void googleChildRunsOnTheLaneExecutorWithoutWaitingForTheAiJob()
            throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_GOOGLE_PREVIEW, false);
        giveCredential();
        Recorder preview = new Recorder("preview");

        assertTrue(newLane().start("track", 1, pendingDocument(), BACKEND, "en", "ko", "ko",
                true, alwaysCurrent(), preview));
        assertTrue("the display must publish while the AI job is still queued: "
                        + events.snapshot(),
                events.await("preview:rerender", WAIT_MS));
        assertTrue("Google must run on the lane executor: " + events.snapshot(),
                events.index("google-translate:lane-behavior") >= 0);
        assertEquals("the parked AI job must not have billed yet", 0, AiHttpTestControl.count());

        aiExecutor.openGate();
        assertTrue("expected preview:complete, saw " + events.snapshot(),
                events.await("preview:complete", WAIT_MS));
        assertEquals(1, AiHttpTestControl.count());
    }

    /**
     * Google publication releases the AI job; until it publishes, no paid request may leave.
     */
    @Test public void previewHoldsThePaidRequestUntilGooglePublishes() throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_GOOGLE_PREVIEW, false);
        giveCredential();
        aiExecutor.openGate();
        provider.gate();
        Recorder preview = new Recorder("preview");

        assertTrue(newLane().start("track", 1, pendingDocument(), BACKEND, "en", "ko", "ko",
                true, alwaysCurrent(), preview));

        Thread.sleep(300L);
        assertEquals("Google has not published, so nothing may be billed yet",
                0, AiHttpTestControl.count());
        assertEquals("the AI job must still be waiting on Google",
                AiRequestLiveState.Phase.PREPARING,
                AiRequestLiveState.diagnosticSnapshot(LayerKind.MEANING).current.phase);
        assertEquals(-1, events.index("preview:rerender"));

        provider.release();
        assertTrue("expected preview:rerender, saw " + events.snapshot(),
                events.await("preview:rerender", WAIT_MS));
        assertTrue("the released AI job must reach the provider",
                events.await(() -> AiHttpTestControl.count() == 1, WAIT_MS));
        assertTrue("expected preview:complete, saw " + events.snapshot(),
                events.await("preview:complete", WAIT_MS));
    }

    /**
     * Preview AI semantics are AI-only semantics: raw lyrics and the plain meaning identity, so
     * one paid answer serves both flows instead of buying the same lines twice.
     */
    @Test public void previewAndAiOnlyShareOnePaidRecordIdentity() throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_GOOGLE_PREVIEW, false);
        giveCredential();
        aiExecutor.openGate();
        Recorder preview = new Recorder("preview");
        assertTrue(newLane().start("track", 1, pendingDocument(), BACKEND, "en", "ko", "ko",
                true, alwaysCurrent(), preview));
        assertTrue("expected preview:complete, saw " + events.snapshot(),
                events.await("preview:complete", WAIT_MS));
        assertEquals(1, AiHttpTestControl.count());
        Set<String> previewIdentity = recordStore.keys();
        assertEquals("preview wrote exactly one paid identity", 1, previewIdentity.size());
        String body = AiHttpTestControl.recorded().get(0).body;
        assertTrue("the request carries the raw lyrics: " + body,
                body.contains("안녕하세요"));
        assertFalse("the request carries no Google draft: " + body,
                body.contains(GOOGLE_DRAFT_TEXT));

        configureAi(AiSettings.TRANSLATION_PIPELINE_AI_ONLY, false);
        AiHttpTestControl.clear();
        Recorder aiOnly = new Recorder("aiOnly");
        assertTrue(newLane().start("track", 2, pendingDocument(), BACKEND, "en", "ko", "ko",
                true, alwaysCurrent(), aiOnly));
        assertTrue("expected aiOnly:complete, saw " + events.snapshot(),
                events.await("aiOnly:complete", WAIT_MS));
        assertEquals(1, AiHttpTestControl.count());
        assertEquals("preview and AI-only must key one paid record, not two",
                previewIdentity, recordStore.keys());
    }

    /** A paid answer already bought must not skip the display flow the user selected. */
    @Test public void cachedAiAnswerStillShowsGoogleFirstInPreview() throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_GOOGLE_PREVIEW, false);
        giveCredential();
        aiExecutor.openGate();
        LyricsDocument document = pendingDocument();
        AiRecordStores.installFactoryForTest(ignored -> new CachedAnswerStore(document));
        Recorder preview = new Recorder("preview");

        assertTrue(newLane().start("track", 1, document, BACKEND, "en", "ko", "ko",
                true, alwaysCurrent(), preview));

        assertTrue("a cached answer must not skip the Google display: " + events.snapshot(),
                events.await("preview:rerender", WAIT_MS));
        assertTrue("expected preview:complete, saw " + events.snapshot(),
                events.await("preview:complete", WAIT_MS));
        assertEquals("a reused answer is not billed again", 0, AiHttpTestControl.count());
    }

    /**
     * Exactly-once accounting: a refused AI dispatch cancels the run's calls and leaves the layer
     * claimable rather than busy forever.
     */
    @Test public void rejectedPreviewDispatchLeavesTheLayerClaimable() throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_GOOGLE_PREVIEW, false);
        giveCredential();
        Recorder refused = new Recorder("refused");
        Recorder later = new Recorder("later");
        LyricsDocument document = pendingDocument();

        aiExecutor.rejecting(true);
        assertFalse(newLane().start("track", 1, document, BACKEND, "en", "ko", "ko",
                true, alwaysCurrent(), refused));

        assertTrue("a refused dispatch aborts the run's provider calls: " + events.snapshot(),
                events.await("cancel", WAIT_MS));

        aiExecutor.rejecting(false);
        aiExecutor.openGate();
        assertTrue(newLane().start("track", 2, document, BACKEND, "en", "ko", "ko",
                true, alwaysCurrent(), later));
        assertTrue("the refused run must not have left the layer busy: " + events.snapshot(),
                events.await("later:complete", WAIT_MS));
    }

    /** Retirement before AI dispatch wakes no paid request and releases the run. */
    @Test public void retiredPreviewRunReleasesTheLayerWithoutRequesting() throws Exception {
        configureAi(AiSettings.TRANSLATION_PIPELINE_GOOGLE_PREVIEW, false);
        giveCredential();
        aiExecutor.openGate();
        LyricsSecondaryProcessor.CurrentGuard generationTwoIsRetired =
                (id, generation, snapshot) -> generation != 2;
        Recorder retired = new Recorder("retired");
        Recorder later = new Recorder("later");

        assertTrue(newLane().start("track", 2, pendingDocument(), BACKEND, "en", "ko", "ko",
                true, generationTwoIsRetired, retired));
        assertTrue("the retired run must cancel rather than bill",
                events.await(() -> AiRequestLiveState.diagnosticSnapshot(LayerKind.MEANING)
                        .current.phase == AiRequestLiveState.Phase.CANCELLED, WAIT_MS));
        assertEquals(0, AiHttpTestControl.count());
        assertEquals(-1, events.index("retired:complete"));

        assertTrue(newLane().start("track", 3, pendingDocument(), BACKEND, "en", "ko", "ko",
                true, generationTwoIsRetired, later));
        assertTrue("the released key must be claimable again: " + events.snapshot(),
                events.await("later:complete", WAIT_MS));
    }

    // --- Harness ----------------------------------------------------------

    private LyricsMeaningLane newLane() {
        return newLane(aiExecutor);
    }

    /** A second surface: its own lane instance, because each surface owns its own lane. */
    private LyricsMeaningLane newLane(ExecutorService otherAiExecutor) {
        LyricsMeaningLane lane = new LyricsMeaningLane(context, new OkHttpClient(), laneExecutor,
                otherAiExecutor, 1, provider, Runnable::run, () -> 1_000L,
                context -> aiSettings);
        if (otherAiExecutor != aiExecutor) spareExecutors.add(otherAiExecutor);
        return lane;
    }

    private GateExecutor openAiExecutor(String name) {
        GateExecutor executor = new GateExecutor(name);
        executor.openGate();
        return executor;
    }

    private void configureAi(String flow, boolean automatic) {
        settingsStore.put(Settings.AI_ENABLED, true);
        settingsStore.put(Settings.TRANSLATION_ENABLED, true);
        settingsStore.put(Settings.AI_PROVIDER, AiSettings.PROVIDER_OPENAI);
        settingsStore.put(Settings.AI_MODEL_OPENAI, "gpt-4o-mini");
        settingsStore.put(Settings.AI_TRANSLATION_PIPELINE, flow);
        settingsStore.put(Settings.AI_TRANSLATION_MODE,
                automatic ? "Always use AI" : "On demand");
    }

    private void giveCredential() {
        assertTrue(credentials.save(aiSettings.credentialScope(), "sk-test-key"));
    }

    private static LyricsSecondaryProcessor.CurrentGuard alwaysCurrent() {
        return (id, generation, snapshot) -> true;
    }

    static LyricsDocument pendingDocument() {
        return document("ko", true);
    }

    static LyricsDocument notPendingDocument() {
        return document("ko", false);
    }

    private static LyricsDocument document(String language, boolean translationPending) {
        LyricsDocument document = new LyricsDocument();
        document.trackId = "behavior-track";
        document.language = language;
        document.translationPending = translationPending;
        for (int i = 0; i < 2; i++) {
            LyricsLine line = new LyricsLine();
            line.text = i == 0 ? "안녕하세요" : "반갑습니다";
            line.startMs = i * 1_000L;
            line.endMs = i * 1_000L + 900L;
            document.lines.add(line);
        }
        return document;
    }

    private static void clearCoalescer() throws Exception {
        Field field = LyricsMeaningLane.class.getDeclaredField("COALESCER");
        field.setAccessible(true);
        ((LayerRunCoalescer) field.get(null)).clear();
    }

    private final class Recorder implements LyricsSecondaryProcessor.Callback {
        private final String tag;

        Recorder(String tag) {
            this.tag = tag;
        }

        @Override public void rerender(LayerKind layer, DerivedLayerArtifact partial,
                                       String message) {
            events.add(tag + ":rerender");
        }

        @Override public void progress(String message) {
            events.add(tag + ":progress");
        }

        @Override public void complete(LayerKind layer, DerivedLayerArtifact artifact,
                                       LayerFailure failure, String message, int changed) {
            events.add(tag + ":complete");
        }
    }

    /** Dispatch as the surface would: inline, on whatever thread the lane settles from. */
    static final class EventLog {
        private final List<String> events = new CopyOnWriteArrayList<>();

        void add(String event) {
            events.add(event);
        }

        List<String> snapshot() {
            return new ArrayList<>(events);
        }

        int index(String event) {
            return events.indexOf(event);
        }

        boolean await(String event, long timeoutMs) {
            return await(() -> events.contains(event), timeoutMs);
        }

        boolean await(BooleanSupplier condition, long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (condition.getAsBoolean()) return true;
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return condition.getAsBoolean();
        }
    }

    final class ControllableProvider implements LyricsMeaningLane.MeaningProvider {
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean gated;
        private final AtomicInteger translateCalls = new AtomicInteger();

        void gate() {
            gated = true;
        }

        void release() {
            release.countDown();
        }

        int translateCalls() {
            return translateCalls.get();
        }

        @Override public String backendId() {
            return "behavior";
        }

        @Override public boolean handles(String backendSetting) {
            return true;
        }

        @Override public GoogleEnhancer.BatchResult translate(Context context, OkHttpClient http,
                int processingVersion, String trackId, String sourceLang, String targetLang,
                List<GoogleEnhancer.BatchLine> batch, String cancelTag) {
            translateCalls.incrementAndGet();
            events.add("google-translate:" + Thread.currentThread().getName());
            if (gated) {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            GoogleEnhancer.BatchResult result = new GoogleEnhancer.BatchResult();
            for (GoogleEnhancer.BatchLine line : batch) {
                result.translations.put(line.index, "T:" + line.text);
            }
            return result;
        }

        @Override public void cancel(OkHttpClient http, String cancelTag) {
            events.add("cancel");
        }

        @Override public boolean shouldDisplay(String sourceText, String translated) {
            return translated != null && !translated.isEmpty();
        }
    }

    /**
     * One AI worker whose next job waits at a gate, plus a switch that refuses dispatch entirely —
     * the two executor behaviours the lane's coalescer contract depends on.
     */
    static final class GateExecutor extends AbstractExecutorService {
        private final ExecutorService delegate;
        private final CountDownLatch gate = new CountDownLatch(1);
        private volatile boolean rejecting;

        GateExecutor(String name) {
            delegate = Executors.newSingleThreadExecutor(r -> new Thread(r, name));
        }

        void openGate() {
            gate.countDown();
        }

        void rejecting(boolean value) {
            rejecting = value;
        }

        @Override public void execute(Runnable command) {
            if (rejecting) throw new RejectedExecutionException("executor refusing dispatch");
            delegate.execute(() -> {
                try {
                    if (!gate.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("gate never opened");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                command.run();
            });
        }

        @Override public void shutdown() {
            delegate.shutdown();
        }

        @Override public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }

    /**
     * A paid store that already holds a finished answer for whatever it is asked about, so a
     * preview run can meet the cache instead of a provider.
     */
    static final class CachedAnswerStore implements AiRecordStore {
        private final java.util.Map<String, String> answers = new java.util.LinkedHashMap<>();

        CachedAnswerStore(LyricsDocument document) {
            for (CanonicalRow row : CanonicalBase.fromDocument("", document).rows) {
                answers.put(row.rowId, "AI:" + row.text);
            }
        }

        @Override public AiPaidRecord read(AiRunConfig config) {
            AiPaidRecord record = AiPaidRecord.begin(config, 1L);
            for (java.util.Map.Entry<String, String> answer : answers.entrySet()) {
                record.putItem(answer.getKey(), answer.getValue());
            }
            record.status = AiPaidRecord.Status.COMPLETE;
            return record;
        }

        @Override public boolean commit(AiRunConfig config, AiPaidRecord record) {
            return true;
        }

        @Override public Reservation reserve(AiRunConfig config, long maxRecordBytes) {
            return Reservation.admitted();
        }

        @Override public void release(AiRunConfig config) {
        }

        @Override public void forget(AiRunConfig config) {
        }
    }

    /** The credential store with a cipher that is happy to live on a plain JVM. */
    static final class IdentityCipher implements AiCredentialStore.Cipher {
        @Override public String encrypt(String plaintext) {
            return plaintext;
        }

        @Override public String decrypt(String ciphertext) {
            return ciphertext;
        }

        @Override public void clear() {
        }
    }
}
