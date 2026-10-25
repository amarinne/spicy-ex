package com.eza.spicyex.hooks;

import android.os.Bundle;
import android.os.SystemClock;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.testsupport.FakeAndroidContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The publisher's failure bound: one refused or throwing bind and one failed provider call start
 * a 30-second attempt window; no heartbeat re-arms the cycle, and the fan-out keeps the newest
 * retained payload so the next demand carries current state. The window alone gates the retry,
 * so a provider success cannot strand the binder, and a completion from an earlier enable
 * lifetime cannot steer the renewed one.
 */
public class SpicyLyricBridgePublisherBoundsTest {
    private static final long FROZEN_CLOCK_MS = 1000L;
    private static final long EXPIRED_CLOCK_MS = FROZEN_CLOCK_MS + 31_000L;

    private FakeAndroidContext context;
    private SpicyLyricBridgePublisher publisher;

    @Before
    public void freshPublisher() {
        SystemClock.setElapsedRealtimeForTest(FROZEN_CLOCK_MS);
        Diagnostics.setHyperGlowBridgeStatus("disabled");
        context = new FakeAndroidContext();
        publisher = new SpicyLyricBridgePublisher(context);
    }

    @After
    public void restoreClock() throws Exception {
        releaseBlockedProviderCall();
        SystemClock.setElapsedRealtimeForTest(FROZEN_CLOCK_MS);
        android.content.ContentResolver.testHandler = null;
    }

    private void releaseBlockedProviderCall() throws Exception {
        java.util.concurrent.CountDownLatch gate = context.providerCallGate;
        while (gate != null && gate.getCount() > 0) {
            gate.countDown();
            Thread.sleep(2L);
        }
    }

    private static Bundle state(String line) {
        Bundle state = new Bundle();
        state.putString("line", line);
        return state;
    }

    @Test
    public void declinedBindFallsBackOnceAndHoldsEveryNewPublicationDemand()
            throws Exception {
        publisher.enable();
        assertEquals(1, context.bindServiceCalls);
        assertEquals("provider_fallback", Diagnostics.hyperGlowBridgeStatus());

        for (int i = 0; i < 5; i++) {
            publisher.publish(state("row " + i));
        }

        assertEquals("The refusal window holds the heartbeat", 1, context.bindServiceCalls);
        assertEquals("No provider attempt runs while the window holds", 0,
                context.providerCalls.size());
        assertTrue(awaitProviderDrain());
        assertEquals("No provider attempt ran, so the fallback state stands",
                "provider_fallback", Diagnostics.hyperGlowBridgeStatus());
    }

    @Test
    public void throwingBindTakesTheSameBoundedFallbackAsADecline()
            throws Exception {
        // The Spotify process cannot see the companion package: bindService throws.
        context.bindServiceError = new SecurityException("Package com.eza.hyperglow not visible");

        publisher.enable();
        assertEquals(1, context.bindServiceCalls);
        assertEquals("provider_fallback", Diagnostics.hyperGlowBridgeStatus());

        for (int i = 0; i < 5; i++) {
            publisher.publish(state("row " + i));
        }

        assertEquals(1, context.bindServiceCalls);
        assertTrue(awaitProviderDrain());
    }

    @Test
    public void failedProviderAttemptRearmsTheWindowInsteadOfTheImmediateRebindCycle()
            throws Exception {
        publisher.enable();
        SystemClock.setElapsedRealtimeForTest(EXPIRED_CLOCK_MS);
        publisher.publish(state("row"));

        // Window expiry: the demand gets one bind attempt and the retained state rides the
        // provider once; the failed provider call re-arms the window instead of rebinding.
        assertEquals(2, context.bindServiceCalls);
        assertTrue(awaitProviderDrain());
        assertEquals("The retained state rides the provider once", 1,
                context.providerCalls.size());
        assertEquals("provider_failed", Diagnostics.hyperGlowBridgeStatus());

        int bindsAfterFailure = context.bindServiceCalls;
        int callsAfterFailure = context.providerCalls.size();
        for (int i = 0; i < 5; i++) {
            publisher.publish(state("row " + i));
        }

        assertEquals("A failed provider call must not restart the bind/provider cycle per call",
                bindsAfterFailure, context.bindServiceCalls);
        assertEquals("The re-armed window holds the retained state", callsAfterFailure,
                context.providerCalls.size());
        assertTrue(awaitProviderDrain());
    }

    @Test
    public void disableSupersedesAStillQueuedStatePublish() throws Exception {
        context.providerCallOutcomes.add("block");
        context.providerCallOutcomes.add("throw");
        CountDownLatch gate = new CountDownLatch(1);
        context.providerCallGate = gate;

        publisher.enable();                          // declined bind; provider fallback; window
        SystemClock.setElapsedRealtimeForTest(EXPIRED_CLOCK_MS);
        publisher.publish(state("row"));             // one bind retry, then a queued state attempt

        assertTrue("The drain dispatched the state and parks inside its call",
                awaitFirstProviderCall());
        publisher.clearAndDisconnect("producer", 7); // the disable clear queues behind it

        gate.countDown();
        assertTrue(awaitProviderDrain());
        assertEquals("The disable clear is the last provider word after the parked state",
                Arrays.asList("publish", "clear"), context.providerCalls);
        assertEquals("disabled", Diagnostics.hyperGlowBridgeStatus());
    }

    @Test
    public void lateWorkAfterDisableIsSilent() throws Exception {
        publisher.enable();
        assertTrue(awaitProviderDrain());
        publisher.clearAndDisconnect("producer", 7);
        assertEquals("disabled", Diagnostics.hyperGlowBridgeStatus());

        int bindsAtDisable = context.bindServiceCalls;
        publisher.publish(state("late"));
        publisher.clear("producer", 7);
        publisher.publishDocument(new Bundle(), new byte[]{1});

        assertEquals("A disabled publisher issues no further attempts",
                bindsAtDisable, context.bindServiceCalls);
        assertTrue(awaitProviderDrain());
        assertEquals("Only the disable clear reached the provider",
                Arrays.asList("clear"), context.providerCalls);
        assertEquals("disabled", Diagnostics.hyperGlowBridgeStatus());
    }

    @Test
    public void disableDuringInFlightProviderCallCannotSteerTheRenewedLifetime()
            throws Exception {
        context.providerCallOutcomes.add("block");   // the pre-disable state attempt parks
        context.providerCallOutcomes.add("throw");   // the disable clear's stale verdict
        context.providerCallOutcomes.add("return");  // the renewed lifetime's state succeeds
        CountDownLatch gate = new CountDownLatch(1);
        context.providerCallGate = gate;

        publisher.enable();                          // declined bind #1
        SystemClock.setElapsedRealtimeForTest(EXPIRED_CLOCK_MS);
        publisher.publish(state("row"));             // bind retry #2 declined; the state parks
        assertTrue(awaitFirstProviderCall());

        publisher.clearAndDisconnect("producer", 3); // queues the disable clear behind it
        publisher.enable();                          // renewed lifetime: bind #3 declined again
        assertEquals(3, context.bindServiceCalls);

        SystemClock.setElapsedRealtimeForTest(62_000L); // walk past the renewed window
        publisher.publish(state("renewed"));         // bind retry #4 declined; new state queued
        assertEquals("The renewed window holds its own rebind", 4, context.bindServiceCalls);

        gate.countDown();
        assertTrue(awaitProviderDrain());
        assertEquals("The disable clear lands before the renewed state",
                Arrays.asList("publish", "clear", "publish"), context.providerCalls);
        assertEquals("A stale success cannot flip the renewed lifetime's state",
                "provider_connected", Diagnostics.hyperGlowBridgeStatus());
        assertEquals(4, context.bindServiceCalls);
    }

    @Test
    public void successfulProviderCallDoesNotStrandTheBinder() throws Exception {
        context.providerCallOutcomes.add("return");  // every provider call answers

        publisher.enable();                          // declined bind #1; window armed
        publisher.publish(state("held"));            // the window holds; nothing rides the provider
        assertTrue(awaitProviderDrain());
        assertEquals(1, context.bindServiceCalls);
        assertEquals(0, context.providerCalls.size());
        assertEquals("provider_fallback", Diagnostics.hyperGlowBridgeStatus());

        SystemClock.setElapsedRealtimeForTest(EXPIRED_CLOCK_MS);
        publisher.publish(state("flushed"));         // one bind retry (declined) and one provider ride
        assertEquals(2, context.bindServiceCalls);
        assertTrue(awaitProviderDrain());
        assertEquals("The retained state rides the provider once", 1,
                context.providerCalls.size());
        assertEquals("The provider answer cannot strand the binder retry", 2,
                context.bindServiceCalls);

        context.bindServiceShouldSucceed = true;     // the next demand after the window recovered
        SystemClock.setElapsedRealtimeForTest(62_000L);
        publisher.publish(state("recovered"));
        assertEquals(3, context.bindServiceCalls);
        assertEquals("connected", Diagnostics.hyperGlowBridgeStatus());
        assertTrue(awaitProviderDrain());
        assertEquals("State now flows over the binder, not the provider", 1,
                context.providerCalls.size());
        assertEquals("The retained state replays on the bridge", 1, context.publishedStates.size());
        assertEquals("recovered", context.publishedStates.get(0).getString("line"));

        publisher.publishDocument(new Bundle(), new byte[]{1});
        assertEquals("The document replay rides the recovered binder", 1,
                context.publishedDocumentMetadata.size());
        assertEquals(1, context.providerCalls.size());
    }

    @Test
    public void repeatedClearDemandsInsideTheWindowRideOneProviderCall() throws Exception {
        publisher.enable();                          // declined bind; provider fallback; window

        for (int tick = 0; tick < 5; tick++) {
            SystemClock.setElapsedRealtimeForTest(FROZEN_CLOCK_MS + (tick + 1) * 1_000L);
            publisher.clear("producer", tick);
        }
        assertTrue(awaitProviderDrain());
        assertEquals("Repeated clear demands coalesce to one provider call", 1,
                context.providerCalls.size());
        assertEquals("clear", context.providerCalls.get(0));
        assertEquals("The cadence gate holds the binder retry too", 1, context.bindServiceCalls);

        publisher.clearAndDisconnect("producer", 9);  // the disable clear ignores the cadence
        assertTrue(awaitProviderDrain());
        assertEquals("The disable clear always goes out", Arrays.asList("clear", "clear"),
                context.providerCalls);
    }

    private boolean awaitProviderDrain() throws Exception {
        Field queued = field("providerDrainQueued");
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (Boolean.FALSE.equals(queued.get(publisher))) return true;
            Thread.sleep(5L);
        }
        return Boolean.FALSE.equals(queued.get(publisher));
    }

    /** Settles once the drain dispatched at least one provider call, e.g. one parked outcome. */
    private boolean awaitFirstProviderCall() throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (!context.providerCalls.isEmpty()) return true;
            Thread.sleep(2L);
        }
        return !context.providerCalls.isEmpty();
    }

    private Field field(String name) throws Exception {
        for (Class<?> type = SpicyLyricBridgePublisher.class;
             type != null; type = type.getSuperclass()) {
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
}
