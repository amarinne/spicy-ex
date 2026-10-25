package com.eza.spicyex.hooks;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.testsupport.FakeAndroidContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The restored {@code Publish lyrics to HyperGlow} toggle drives enablement itself: the stored
 * boolean decides at start and on change, never a package query.
 *
 * <p>The session manager is a stub shell over the real {@link LyricsSessionPolicy}: only the
 * subscription, demand, and publisher seams the coordinator touches are needed, so these checks
 * stay JVM-fast and the device gate keeps session reuse.
 */
public class SpicyLyricBridgeCoordinatorConfigTest {
    private static final long FROZEN_CLOCK_MS = 1000L;

    private FakeAndroidContext context;
    private LyricsSessionManager sessionManager;
    /** The subscription and demand records the real session manager issues. */
    private List<Object> subscriptions;
    private LyricsSessionPolicy policy;

    @Before
    public void freshProcess() throws Exception {
        SystemClock.setElapsedRealtimeForTest(FROZEN_CLOCK_MS);
        // Matches the static production default, so start-up assertions do not inherit a
        // connection state an earlier test left in this shared JVM.
        Diagnostics.setHyperGlowBridgeStatus("disabled");
        context = new FakeAndroidContext();
        sessionManager = stubSessionManager();
        subscriptions = (List<Object>) field(sessionManager, "subscriptions").get(sessionManager);
        policy = (LyricsSessionPolicy) field(sessionManager, "policy").get(sessionManager);
    }

    @After
    public void restoreClock() {
        SystemClock.setElapsedRealtimeForTest(FROZEN_CLOCK_MS);
    }

    private static LyricsSessionManager stubSessionManager() throws Exception {
        LyricsSessionManager manager = allocate(LyricsSessionManager.class);
        setField(manager, "subscriptions", new ArrayList<>());
        setField(manager, "policy", new LyricsSessionPolicy());
        setField(manager, "handler", new Handler(Looper.getMainLooper()));
        setField(manager, "poll", (Runnable) () -> { });
        return manager;
    }

    private SpicyLyricBridgeCoordinator configuredCoordinator(boolean storedValue) {
        new SettingsStore(context).put(Settings.HYPERGLOW_ENABLED, storedValue);
        SpicyLyricBridgeCoordinator coordinator =
                new SpicyLyricBridgeCoordinator(sessionManager, context);
        coordinator.start();
        return coordinator;
    }

    private static LyricsSessionManager.Snapshot snapshot(String trackUri, long sampledAtMs) {
        return snapshot(trackUri, sampledAtMs, 1);
    }

    private static LyricsSessionManager.Snapshot snapshot(
            String trackUri, long sampledAtMs, int generation) {
        SpotifyTrack track = new SpotifyTrack("Title", "Artist", "Album", trackUri,
                20_000L, "#ffffff", sampledAtMs, null, 240_000L, false);
        return new LyricsSessionManager.Snapshot(track, trackUri, generation, "playing",
                true, 30_000L, sampledAtMs, 1.0);
    }

    @Test
    public void firstStartWithoutStoredValueReportsDisabledAndAttemptsNothing() {
        String configuredBefore = Diagnostics.hyperGlowBridgeStatus();
        SpicyLyricBridgeCoordinator coordinator = new SpicyLyricBridgeCoordinator(sessionManager, context);
        coordinator.start();

        assertEquals("A configured-off start must not touch the connection state",
                configuredBefore, Diagnostics.hyperGlowBridgeStatus());
        assertEquals(0, context.bindServiceCalls);
        assertEquals(0, subscriptions.size());
        assertFalse(policy.hasPollingDemand());
    }

    @Test
    public void storedFalseReportsDisabledAndAttemptsNothing() {
        String configuredBefore = Diagnostics.hyperGlowBridgeStatus();
        configuredCoordinator(false);

        assertEquals(configuredBefore, Diagnostics.hyperGlowBridgeStatus());
        assertEquals(0, context.bindServiceCalls);
        assertEquals(0, subscriptions.size());
        assertFalse(policy.hasPollingDemand());
    }

    @Test
    public void storedTrueEnablesThroughTheToggleNotPackageLookup() {
        SpicyLyricBridgeCoordinator coordinator = configuredCoordinator(true);

        // Enablement issues the bind directly: the Spotify process may not even see the
        // companion package, and that must not decide the toggle.
        assertEquals(1, context.bindServiceCalls);
        assertEquals("com.eza.hyperglow", context.lastBindIntent.getComponent().getPackageName());
        assertEquals(1, subscriptions.size());
        assertTrue(policy.hasPollingDemand());
        // The refused bind answers provider fallback, never "disabled": configured stays on.
        assertEquals("provider_fallback", Diagnostics.hyperGlowBridgeStatus());
    }

    @Test
    public void firstStartBeforeTheApplicationContextDoesNotLeakItsBinding() {
        // The hook runs inside Application.attach, where the process has no application context
        // yet. LoadedApk keys the bound connection by the outer context it sees at bind time, so
        // an unbind issued after attach looks under the application, misses, and leaves the
        // first lifetime's binding alive across toggle-off.
        context.applicationContextAttached = false;
        context.bindServiceShouldSucceed = true; // the device had a live bridge connection
        new SettingsStore(context).put(Settings.HYPERGLOW_ENABLED, true);
        SpicyLyricBridgeCoordinator coordinator =
                new SpicyLyricBridgeCoordinator(sessionManager, context);
        coordinator.start();

        assertEquals("A pre-attach start must not bind from the attaching context",
                0, context.bindServiceCalls);

        context.applicationContextAttached = true; // Application.attach returns; setOuterContext runs
        coordinator.onSessionChanged(snapshot("spotify:track:1", 5_000L));
        assertEquals(1, context.bindServiceCalls);
        assertEquals(1, context.liveBindings);

        new SettingsStore(context).put(Settings.HYPERGLOW_ENABLED, false);
        assertEquals("Toggle off must release the connection the application context owns",
                0, context.liveBindings);
    }

    @Test
    public void toggleOffReleasesDemandSubscriptionAndClearsTheBridge() throws Exception {
        SpicyLyricBridgeCoordinator coordinator = configuredCoordinator(true);
        SpicyLyricBridgePublisher publisher =
                (SpicyLyricBridgePublisher) field(coordinator, "publisher").get(coordinator);
        assertEquals(1, subscriptions.size());

        new SettingsStore(context).put(Settings.HYPERGLOW_ENABLED, false);

        assertEquals(0, subscriptions.size());
        assertFalse(policy.hasPollingDemand());
        assertEquals("disabled", Diagnostics.hyperGlowBridgeStatus());
        // The disable clear rides the provider path once; it does not rebind anything.
        assertEquals(1, context.bindServiceCalls);
        assertTrue("The disable clear must finish draining before the status check settles",
                awaitProviderDrain(publisher));
        assertEquals("Stale provider work must not flip the off state",
                "disabled", Diagnostics.hyperGlowBridgeStatus());
    }

    @Test
    public void toggleBackOnRetriesTheBindImmediately() {
        SpicyLyricBridgeCoordinator coordinator = configuredCoordinator(true);
        assertEquals(1, context.bindServiceCalls);

        new SettingsStore(context).put(Settings.HYPERGLOW_ENABLED, false);
        assertEquals("The refused cycle holds while off", 1, context.bindServiceCalls);

        new SettingsStore(context).put(Settings.HYPERGLOW_ENABLED, true);
        // A deliberate re-enable is its own demand: one fresh bind attempt, not timer-driven.
        assertEquals(2, context.bindServiceCalls);
        assertEquals(1, subscriptions.size());
        assertTrue(policy.hasPollingDemand());
    }

    @Test
    public void absentBridgeDoesNotReAttemptPerHeartbeat() {
        SpicyLyricBridgeCoordinator coordinator = configuredCoordinator(true);
        int attemptsAfterEnable = context.bindServiceCalls;

        for (long tick = 0; tick < 10; tick++) {
            coordinator.onSessionChanged(snapshot("spotify:track:1", 5_000L + tick * 1_000L));
        }

        assertEquals("Each heartbeat holds the attempt inside the refusal window",
                attemptsAfterEnable, context.bindServiceCalls);
    }

    @Test
    public void windowExpiryReopensOneAttemptPerPublicationDemand() throws Exception {
        SpicyLyricBridgeCoordinator coordinator = configuredCoordinator(true);
        SpicyLyricBridgePublisher publisher =
                (SpicyLyricBridgePublisher) field(coordinator, "publisher").get(coordinator);
        int attemptsAfterEnable = context.bindServiceCalls;

        SystemClock.setElapsedRealtimeForTest(FROZEN_CLOCK_MS + 31_000L);
        coordinator.onSessionChanged(snapshot("spotify:track:1", 40_000L));
        assertEquals("The expired window lets the next demand attempt once",
                attemptsAfterEnable + 1, context.bindServiceCalls);

        coordinator.onSessionChanged(snapshot("spotify:track:1", 42_500L));
        assertEquals("The fresh window holds the following heartbeats",
                attemptsAfterEnable + 1, context.bindServiceCalls);

        // The expiry demand queued one provider attempt for the retained state; its failed
        // answer settles before the test ends, so the shared connection state stays owned here.
        assertTrue(awaitProviderDrain(publisher));
        assertEquals("provider_failed", Diagnostics.hyperGlowBridgeStatus());
    }

    @Test
    public void trackChangeClearDoesNotRebindWhileTheWindowIsOpen() throws Exception {
        SpicyLyricBridgeCoordinator coordinator = configuredCoordinator(true);
        SpicyLyricBridgePublisher publisher =
                (SpicyLyricBridgePublisher) field(coordinator, "publisher").get(coordinator);
        int attemptsAfterEnable = context.bindServiceCalls;

        coordinator.onSessionChanged(snapshot("spotify:track:1", 5_000L, 1));
        // A genuinely new generation clears the previous track; the refusal window keeps the
        // rebind off, but the clear itself always rides the provider path and settles its
        // failed answer here so late provider work cannot leak into the next test's checks.
        coordinator.onSessionChanged(snapshot("spotify:track:2", 6_500L, 2));

        assertEquals(attemptsAfterEnable, context.bindServiceCalls);
        assertTrue(awaitProviderDrain(publisher));
        assertEquals("provider_failed", Diagnostics.hyperGlowBridgeStatus());
    }

    @Test
    public void noTrackSamplesClearOncePerWindowAndTheDisableClearAlwaysGoesOut()
            throws Exception {
        SpicyLyricBridgeCoordinator coordinator = configuredCoordinator(true);
        SpicyLyricBridgePublisher publisher =
                (SpicyLyricBridgePublisher) field(coordinator, "publisher").get(coordinator);
        int attemptsAfterEnable = context.bindServiceCalls;

        for (int tick = 0; tick < 5; tick++) {
            SystemClock.setElapsedRealtimeForTest(FROZEN_CLOCK_MS + (tick + 1) * 1_000L);
            coordinator.onSessionChanged(noTrackSnapshot());
        }
        assertTrue(awaitProviderDrain(publisher));
        assertEquals("Repeated no-track samples ride one provider clear per window", 1,
                context.providerCalls.size());
        assertEquals("clear", context.providerCalls.get(0));
        assertEquals("The window also holds the binder retry", attemptsAfterEnable,
                context.bindServiceCalls);

        new SettingsStore(context).put(Settings.HYPERGLOW_ENABLED, false);
        assertTrue(awaitProviderDrain(publisher));
        assertEquals("The disable clear always goes out, even after a normal clear", 2,
                context.providerCalls.size());
        assertEquals("clear", context.providerCalls.get(1));
        assertEquals("disabled", Diagnostics.hyperGlowBridgeStatus());
    }

    private static LyricsSessionManager.Snapshot noTrackSnapshot() {
        return new LyricsSessionManager.Snapshot(null, "", 1, "paused", false, 0L,
                SystemClock.elapsedRealtime(), 1.0);
    }

    private static boolean awaitProviderDrain(SpicyLyricBridgePublisher publisher)
            throws Exception {
        Field queued = field(publisher, "providerDrainQueued");
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (Boolean.FALSE.equals(queued.get(publisher))) return true;
            Thread.sleep(5L);
        }
        return Boolean.FALSE.equals(queued.get(publisher));
    }

    private static Field field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field found = type.getDeclaredField(name);
                found.setAccessible(true);
                return found;
            } catch (NoSuchFieldException ignored) {
                // Walk up.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        field(target, name).set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        // Mirror the PipMarkedAdoption pattern: allocate without Android constructors.
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field instance = unsafe.getDeclaredField("theUnsafe");
        instance.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class)
                .invoke(instance.get(null), type));
    }
}
