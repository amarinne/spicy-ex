package com.eza.spicyex.hooks;

import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SettingsStore;
import com.eza.spicyex.auto.AutoPrototypeClient;
import com.eza.spicyex.auto.IAutoPrototype;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Drives the production publisher and session demand with a manual worker and local Binder. */
public class AutoPrototypePublisherLifecycleTest {
    @After public void restoreClock() { SystemClock.setElapsedRealtimeForTest(1000); }

    @Test public void enabledWithoutACarSchedulesNothingAndAcquiresNoDemand() throws Exception {
        Fixture f = new Fixture(true);
        assertEquals(1, f.context.receivers.size());
        assertNull(f.worker.repeating);
        assertEquals(0, f.context.bindServiceCalls);
        f.assertDormant();
    }

    @Test public void disabledPublisherDoesNotEvenObserveTheHost() throws Exception {
        Fixture f = new Fixture(false);
        assertTrue(f.context.receivers.isEmpty());
        assertNull(f.worker.repeating);
        assertEquals(0, f.context.bindServiceCalls);
        f.assertDormant();
    }

    @Test public void connectionAcquiresOneLeaseAndDisconnectCancelsAllPublication() throws Exception {
        Fixture f = new Fixture(true);
        f.connect();
        f.worker.tick();
        assertEquals(1, f.policy.pollingDemandCount());
        assertEquals(1, f.subscriptions.size());
        f.context.receivers.get(0).onReceive(f.context, new android.content.Intent());
        f.worker.tick(); f.worker.tick();
        assertEquals(1, f.policy.pollingDemandCount());
        assertEquals(1, java.util.Collections.frequency(f.calls, "configure"));
        f.hostActive = false;
        f.worker.tick();
        f.assertDormant();
        assertTrue(f.worker.repeating.isCancelled());
        int calls = f.calls.size();
        SystemClock.setElapsedRealtimeForTest(100000);
        f.worker.tick();
        assertEquals(calls, f.calls.size());
        assertEquals(0, f.context.bindServiceCalls);
    }

    @Test public void failedConnectionStopsAtDeadlineInsteadOfRebindingForever() throws Exception {
        Fixture f = new Fixture(true);
        f.context.receivers.get(0).onReceive(f.context, new android.content.Intent());
        SystemClock.setElapsedRealtimeForTest(5000);
        f.worker.tick();
        assertEquals(1, f.context.bindServiceCalls);
        assertTrue(f.worker.repeating.isCancelled());
        SystemClock.setElapsedRealtimeForTest(100000);
        f.worker.tick();
        assertEquals(1, f.context.bindServiceCalls);
        f.assertDormant();
    }

    @Test public void hostNoticesRequireTheModuleSignaturePermission() throws Exception {
        Fixture f = new Fixture(true);
        assertEquals(com.eza.spicyex.auto.AutoPrototypeProvider.HOST_PERMISSION, f.context.receiverPermission);
        assertEquals(0, f.context.bindServiceCalls);
        assertNull(f.worker.once);
    }

    @Test public void receiverRegistrationFailureCannotCrashOrStartAnIdleLoop() throws Exception {
        Fixture f = new Fixture(true, true);
        assertTrue(f.context.receivers.isEmpty());
        assertNull(f.worker.repeating);
        assertNull(f.worker.once);
        f.assertDormant();
    }

    @Test public void disablingAutoRemovesTheReceiverAndPublicationDemand() throws Exception {
        Fixture f = new Fixture(true);
        f.connect();
        f.worker.tick();
        new SettingsStore(f.context).put(Settings.AUTO_ENABLED, false);
        assertTrue(f.context.receivers.isEmpty());
        f.assertDormant();
    }

    private static final class Fixture {
        final FakeAndroidContext context = new FakeAndroidContext();
        final Ticker worker = new Ticker();
        final AutoPrototypeClient client = new AutoPrototypeClient(context);
        final LyricsSessionManager manager = new LyricsSessionManager(context, new Handler(), null, null, () -> 1000L);
        final AutoPrototypePublisher publisher;
        final LyricsSessionPolicy policy;
        final List<?> subscriptions;
        final List<String> calls = new ArrayList<>();
        boolean hostActive = true;
        Fixture(boolean enabled) throws Exception { this(enabled, false); }
        Fixture(boolean enabled, boolean hidden) throws Exception {
            SystemClock.setElapsedRealtimeForTest(1000);
            new SettingsStore(context).put(Settings.AUTO_ENABLED, enabled);
            publisher = new AutoPrototypePublisher(context, manager, client, worker);
            policy = (LyricsSessionPolicy) get(manager, "policy");
            subscriptions = (List<?>) get(manager, "subscriptions");
            if (hidden) context.receiverRegistrationError = new SecurityException("Receiver unavailable");
            publisher.start();
        }
        void connect() throws Exception {
            attachService();
            context.receivers.get(0).onReceive(context, new android.content.Intent());
        }
        void attachService() throws Exception {
            Field service = AutoPrototypeClient.class.getDeclaredField("service");
            service.setAccessible(true);
            service.set(client, new IAutoPrototype.Stub() {
                @Override public Bundle call(String method, Bundle extras) {
                    calls.add(method);
                    Bundle reply = new Bundle();
                    reply.putBoolean("hostActive", hostActive);
                    reply.putBoolean("enabled", true);
                    return reply;
                }
            });
        }
        void assertDormant() {
            assertEquals(0, policy.pollingDemandCount());
            assertTrue(subscriptions.isEmpty());
            assertEquals(0, context.liveBindings);
        }
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static final class Ticker extends ScheduledThreadPoolExecutor {
        Task repeating;
        Task once;
        Ticker() { super(1); }
        @Override public void execute(Runnable action) { action.run(); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable action, long initial, long delay, TimeUnit unit) {
            repeating = new Task(action);
            return repeating;
        }
        @Override public ScheduledFuture<?> schedule(Runnable action, long delay, TimeUnit unit) { once = new Task(action); return once; }
        void tick() { if (repeating != null && !repeating.isCancelled()) repeating.action.run(); }
        void runOnce() { Task task = once; once = null; if (task != null) task.action.run(); }
    }
    private static final class Task extends FutureTask<Object> implements ScheduledFuture<Object> {
        final Runnable action;
        Task(Runnable action) { super(action, null); this.action = action; }
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed other) { return 0; }
    }
}
