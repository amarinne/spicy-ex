package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Ownership, one-shot delivery, destruction, and hook-install behaviour of the result bridge. */
public class ActivityResultBridgeTest {
    private final List<Runnable> posted = new ArrayList<>();
    private final ActivityResultRequests requests = new ActivityResultRequests(posted::add);

    @Test
    public void twoActivitiesWithSameRequestCodeKeepSeparateCallbacks() {
        Object activityA = new Object();
        Object activityB = new Object();
        Callback callbackA = new Callback();
        Callback callbackB = new Callback();
        requests.start(activityA, 10234, callbackA, code -> { });
        requests.start(activityB, 10234, callbackB, code -> { });

        Intent resultB = new Intent();
        assertTrue(requests.dispatch(activityB, 10234, true, resultB));
        runPosted();
        assertSame(resultB, callbackB.only());
        assertEquals(0, callbackA.calls);

        Intent resultA = new Intent();
        assertTrue(requests.dispatch(activityA, 10234, true, resultA));
        runPosted();
        assertSame(resultA, callbackA.only());

        assertFalse(requests.dispatch(activityB, 10234, true, new Intent()));
    }

    @Test
    public void repeatedLaunchFromSameOwnerIsRefusedWhilePending() {
        Object activity = new Object();
        Callback first = new Callback();
        Callback second = new Callback();
        List<Integer> launches = new ArrayList<>();
        requests.start(activity, 10234, first, launches::add);
        requests.start(activity, 10234, second,
                code -> { throw new AssertionError("second launch must not start"); });

        assertEquals(1, launches.size());
        assertNull(second.only());

        Intent result = new Intent();
        assertTrue(requests.dispatch(activity, 10234, true, result));
        runPosted();
        assertSame(result, first.only());
        assertFalse(requests.dispatch(activity, 10234, true, new Intent()));

        Callback third = new Callback();
        requests.start(activity, 10234, third, launches::add);
        assertEquals(2, launches.size());
        Intent next = new Intent();
        assertTrue(requests.dispatch(activity, 10234, true, next));
        runPosted();
        assertSame(next, third.only());
    }

    @Test
    public void lateOrWrongOwnerResultDoesNotConsumePendingAttempt() {
        Object activity = new Object();
        Object otherActivity = new Object();
        Callback callback = new Callback();
        requests.start(activity, 7, callback, code -> { });

        assertFalse(requests.dispatch(otherActivity, 7, true, new Intent()));
        assertFalse(requests.dispatch(activity, 8, true, new Intent()));
        assertEquals(0, callback.calls);

        Intent result = new Intent();
        assertTrue(requests.dispatch(activity, 7, true, result));
        runPosted();
        assertSame(result, callback.only());
    }

    @Test
    public void launchFailureDeliversCancelledExactlyOnce() {
        Object activity = new Object();
        Callback callback = new Callback();
        requests.start(activity, 9, callback,
                code -> { throw new IllegalStateException("picker unavailable"); });

        assertNull(callback.only());
        assertTrue(posted.isEmpty());
        assertFalse(requests.dispatch(activity, 9, true, new Intent()));
    }

    @Test
    public void launchFailureAfterImmediateResultDoesNotDoubleDeliver() {
        Object activity = new Object();
        Callback callback = new Callback();
        Intent result = new Intent();
        requests.start(activity, 10, callback, code -> {
            assertTrue(requests.dispatch(activity, code, true, result));
            throw new IllegalStateException("launch reported failure after the result");
        });

        runPosted();
        assertSame(result, callback.only());
        assertFalse(requests.dispatch(activity, 10, true, new Intent()));
    }

    @Test
    public void destructionReleasesPendingWithoutDelivery() {
        Object activity = new Object();
        Callback callback = new Callback();
        requests.start(activity, 11, callback, code -> { });

        requests.destroy(activity);

        assertEquals(0, callback.calls);
        assertTrue(posted.isEmpty());
        assertFalse(requests.dispatch(activity, 11, true, new Intent()));
    }

    @Test
    public void destructionBeforePostedDeliverySuppressesCallback() {
        Object activity = new Object();
        Callback callback = new Callback();
        requests.start(activity, 12, callback, code -> { });
        assertTrue(requests.dispatch(activity, 12, true, new Intent()));

        requests.destroy(activity);
        runPosted();

        assertEquals(0, callback.calls);
    }

    @Test
    public void cancelledResultDeliversNullExactlyOnce() {
        Object activity = new Object();
        Callback callback = new Callback();
        requests.start(activity, 13, callback, code -> { });

        assertTrue(requests.dispatch(activity, 13, false, new Intent()));
        runPosted();
        assertNull(callback.only());
        assertFalse(requests.dispatch(activity, 13, false, new Intent()));
    }

    @Test
    public void hookInstallationBecomesReadyOnlyAfterSuccessAndRetriesCleanly() {
        ActivityResultBridge.Installation installation =
                new ActivityResultBridge.Installation(requests);
        FakeInstaller failing = new FakeInstaller(true);

        assertFalse(installation.install(failing));
        assertFalse(installation.isReady());
        assertEquals(1, failing.installs);
        assertEquals(1, failing.uninstalls);
        assertSame(requests, failing.requests);

        FakeInstaller succeeding = new FakeInstaller(false);
        assertTrue(installation.install(succeeding));
        assertTrue(installation.isReady());
        assertEquals(1, succeeding.installs);
        assertEquals(0, succeeding.uninstalls);

        FakeInstaller ignored = new FakeInstaller(false);
        assertTrue(installation.install(ignored));
        assertEquals(0, ignored.installs);
    }

    @Test
    public void unavailableHooksRefuseLaunchUntilInstallationRecovers() {
        ActivityResultBridge.Installation installation =
                new ActivityResultBridge.Installation(requests);
        Object activity = new Object();
        List<Integer> launches = new ArrayList<>();
        Callback unavailable = new Callback();
        installation.start(activity, 14, unavailable, launches::add);
        assertNull(unavailable.only());
        assertTrue(launches.isEmpty());
        assertFalse(requests.dispatch(activity, 14, true, new Intent()));

        assertFalse(installation.install(new FakeInstaller(true)));
        Callback failed = new Callback();
        installation.start(activity, 14, failed, launches::add);
        assertNull(failed.only());
        assertTrue(launches.isEmpty());
        assertFalse(requests.dispatch(activity, 14, true, new Intent()));

        assertTrue(installation.install(new FakeInstaller(false)));
        Callback recovered = new Callback();
        installation.start(activity, 14, recovered, launches::add);
        assertEquals(1, launches.size());
        Intent result = new Intent();
        assertTrue(requests.dispatch(activity, 14, true, result));
        runPosted();
        assertSame(result, recovered.only());
    }

    @Test
    public void frameworkDispatchOverloadsAreRecognised() throws Exception {
        assertTrue(ActivityResultBridge.isResultDispatch(method("dispatchActivityResult",
                String.class, int.class, int.class, Intent.class, int.class)));
        assertTrue(ActivityResultBridge.isResultDispatch(method("dispatchActivityResult",
                String.class, int.class, int.class, Intent.class, String.class)));
        assertTrue(ActivityResultBridge.isResultDispatch(method("dispatchActivityResult",
                String.class, int.class, int.class, Intent.class)));
        assertFalse(ActivityResultBridge.isResultDispatch(method("onActivityResult",
                int.class, int.class, Intent.class)));
        assertFalse(ActivityResultBridge.isResultDispatch(method("dispatchActivityResult",
                int.class, int.class, int.class, Intent.class)));
    }

    private void runPosted() {
        List<Runnable> queued = new ArrayList<>(posted);
        posted.clear();
        for (Runnable runnable : queued) runnable.run();
    }

    private static Method method(String name, Class<?>... types) throws NoSuchMethodException {
        return ActivityResultBridgeTest.class.getDeclaredMethod(name, types);
    }

    @SuppressWarnings("unused")
    private void dispatchActivityResult(String who, int requestCode, int resultCode, Intent data,
            int flags) {
    }

    @SuppressWarnings("unused")
    private void dispatchActivityResult(String who, int requestCode, int resultCode, Intent data,
            String reason) {
    }

    @SuppressWarnings("unused")
    private void dispatchActivityResult(String who, int requestCode, int resultCode, Intent data) {
    }

    @SuppressWarnings("unused")
    private void onActivityResult(int requestCode, int resultCode, Intent data) {
    }

    @SuppressWarnings("unused")
    private void dispatchActivityResult(int who, int requestCode, int resultCode, Intent data) {
    }

    private static final class Callback implements Consumer<Intent> {
        final List<Intent> results = new ArrayList<>();
        int calls;

        @Override
        public void accept(Intent intent) {
            calls++;
            results.add(intent);
        }

        Intent only() {
            assertEquals(1, calls);
            return results.get(0);
        }
    }

    private static final class FakeInstaller implements ActivityResultBridge.HookInstaller {
        final boolean fail;
        int installs;
        int uninstalls;
        ActivityResultRequests requests;

        FakeInstaller(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void install(ActivityResultRequests requests) throws Throwable {
            this.requests = requests;
            installs++;
            if (fail) throw new IllegalStateException("hook unavailable");
        }

        @Override
        public void uninstall() {
            uninstalls++;
        }
    }
}
