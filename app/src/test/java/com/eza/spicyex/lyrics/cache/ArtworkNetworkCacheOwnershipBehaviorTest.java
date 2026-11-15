package com.eza.spicyex.lyrics.cache;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Timeout;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Ownership rules for the shared cover download. A download may only finish the request that
 * started it, trimming retires running work, and a late result must not repopulate retired state
 * or retire a newer request for the same cover.
 */
public class ArtworkNetworkCacheOwnershipBehaviorTest {
    @Before
    public void clearSharedState() {
        ArtworkNetworkCache.trimMemory();
    }

    @Test
    public void malformedUrlIsContainedAndRetryable() throws Exception {
        FakeHttp http = new FakeHttp();
        boolean escaped = false;
        try {
            ArtworkNetworkCache.copyOrFetch("http://[", http);
        } catch (RuntimeException failure) {
            escaped = true;
        }
        assertFalse("a malformed cover URL must stay inside the cache", escaped);
        assertEquals("the URL fails before newCall", 0, http.newCalls);
        assertEquals("a failed start leaves no ownership", 0, pendingCount());

        ArtworkNetworkCache.copyOrFetch("http://[", http);
        assertEquals("the same cover can fail again without stranding", 0, pendingCount());
    }

    @Test
    public void synchronousNewCallFailureIsRetryable() throws Exception {
        FakeHttp http = new FakeHttp();
        http.newCallFailure = new IllegalStateException("fixture newCall");
        boolean escaped = false;
        try {
            ArtworkNetworkCache.copyOrFetch("cover-newcall", http);
        } catch (RuntimeException failure) {
            escaped = true;
        }
        assertFalse(escaped);
        assertEquals(1, http.newCalls);
        assertEquals("a failed newCall leaves no ownership", 0, pendingCount());

        http.newCallFailure = null;
        ArtworkNetworkCache.copyOrFetch("cover-newcall", http);
        assertEquals("the same cover starts a new download", 2, http.newCalls);
        assertEquals(1, http.attempts.size());
    }

    @Test
    public void synchronousEnqueueFailureIsRetryable() throws Exception {
        FakeHttp http = new FakeHttp();
        http.enqueueFailure = new IllegalStateException("fixture enqueue");
        boolean escaped = false;
        try {
            ArtworkNetworkCache.copyOrFetch("cover-enqueue", http);
        } catch (RuntimeException failure) {
            escaped = true;
        }
        assertFalse(escaped);
        assertEquals(1, http.attempts.size());
        assertEquals("a failed enqueue leaves no ownership", 0, pendingCount());

        http.enqueueFailure = null;
        ArtworkNetworkCache.copyOrFetch("cover-enqueue", http);
        assertEquals("the same cover is enqueued again", 2, http.attempts.size());
    }

    @Test
    public void trimRetiresLateSuccessAndAllowsRetry() throws Exception {
        FakeHttp http = new FakeHttp();
        String imageId = "cover-trim";
        Attempt retired = start(imageId, http);
        AtomicInteger notifications = new AtomicInteger();
        Runnable listener = notifications::incrementAndGet;
        ArtworkNetworkCache.addListener(listener);
        try {
            ArtworkNetworkCache.trimMemory();
            respond(retired, "late cover");
            assertFalse("a retired success must not refill the cache",
                    ArtworkNetworkCache.has(imageId));
            assertEquals("a retired success does not notify", 0, notifications.get());
            assertEquals(0, pendingCount());

            Attempt fresh = start(imageId, http);
            assertEquals("trim allows a fresh attempt", 2, http.attempts.size());
            respond(fresh, "fresh cover");
            assertTrue(ArtworkNetworkCache.has(imageId));
        } finally {
            ArtworkNetworkCache.removeListener(listener);
        }
    }

    @Test
    public void retiredSuccessCannotReplaceNewerRequest() throws Exception {
        FakeHttp http = new FakeHttp();
        String imageId = "cover-replace-success";
        Attempt retired = start(imageId, http);
        ArtworkNetworkCache.trimMemory();
        Attempt replacement = start(imageId, http);

        AtomicInteger notifications = new AtomicInteger();
        Runnable listener = notifications::incrementAndGet;
        ArtworkNetworkCache.addListener(listener);
        try {
            respond(retired, "old cover");
            assertFalse("an old success must not refill retired cache state",
                    ArtworkNetworkCache.has(imageId));
            assertEquals("an old success does not notify", 0, notifications.get());
            assertEquals("the replacement still owns the download", 1, pendingCount());

            ArtworkNetworkCache.copyOrFetch(imageId, http);
            assertEquals("the replacement still coalesces", 2, http.attempts.size());

            respond(replacement, "new cover");
            assertTrue(ArtworkNetworkCache.has(imageId));
            assertEquals(1, notifications.get());
            assertEquals(0, pendingCount());
        } finally {
            ArtworkNetworkCache.removeListener(listener);
        }
    }

    @Test
    public void retiredFailureCannotRetireNewerRequest() throws Exception {
        FakeHttp http = new FakeHttp();
        String imageId = "cover-replace-failure";
        Attempt retired = start(imageId, http);
        ArtworkNetworkCache.trimMemory();
        Attempt replacement = start(imageId, http);

        fail(retired);
        assertEquals("an old failure must not retire the replacement", 1, pendingCount());
        ArtworkNetworkCache.copyOrFetch(imageId, http);
        assertEquals("the replacement still coalesces", 2, http.attempts.size());

        respond(replacement, "new cover");
        assertTrue(ArtworkNetworkCache.has(imageId));
        assertEquals(0, pendingCount());
    }

    @Test
    public void normalFetchCoalescesCachesAndNotifies() throws Exception {
        FakeHttp http = new FakeHttp();
        String imageId = "cover-normal";
        Attempt attempt = start(imageId, http);
        ArtworkNetworkCache.copyOrFetch(imageId, http);
        assertEquals("one download per cover", 1, http.attempts.size());

        AtomicInteger notifications = new AtomicInteger();
        Runnable listener = notifications::incrementAndGet;
        ArtworkNetworkCache.addListener(listener);
        try {
            respond(attempt, "cover bytes");
            assertEquals(1, notifications.get());
            assertTrue(ArtworkNetworkCache.has(imageId));
            assertNotNull("a cache hit hands out an owned copy",
                    ArtworkNetworkCache.copyOrFetch(imageId, http));
            assertEquals("a cache hit does not download again", 1, http.attempts.size());
            assertEquals(0, pendingCount());
        } finally {
            ArtworkNetworkCache.removeListener(listener);
        }
    }

    @Test
    public void throwingListenerDoesNotBlockNotificationOrCleanup() throws Exception {
        FakeHttp http = new FakeHttp();
        String imageId = "cover-listener-failure";
        Attempt attempt = start(imageId, http);
        AtomicInteger later = new AtomicInteger();
        Runnable boom = () -> {
            throw new IllegalStateException("fixture listener");
        };
        Runnable listener = later::incrementAndGet;
        ArtworkNetworkCache.addListener(boom);
        ArtworkNetworkCache.addListener(listener);
        boolean escaped = false;
        try {
            respond(attempt, "cover bytes");
        } catch (RuntimeException failure) {
            escaped = true;
        } finally {
            ArtworkNetworkCache.removeListener(boom);
            ArtworkNetworkCache.removeListener(listener);
        }
        assertFalse("a listener failure stays out of the download callback", escaped);
        assertEquals("later listeners still run", 1, later.get());
        assertTrue(ArtworkNetworkCache.has(imageId));
        assertEquals(0, pendingCount());
    }

    @Test
    public void keepsFourMostRecentCovers() throws Exception {
        FakeHttp http = new FakeHttp();
        for (int i = 0; i < 5; i++) {
            Attempt attempt = start("cover-" + i, http);
            respond(attempt, "cover " + i);
        }
        assertFalse("the oldest cover is evicted", ArtworkNetworkCache.has("cover-0"));
        for (int i = 1; i < 5; i++) {
            assertTrue("cover-" + i + " stays cached", ArtworkNetworkCache.has("cover-" + i));
        }
    }

    private static Attempt start(String imageId, FakeHttp http) {
        ArtworkNetworkCache.copyOrFetch(imageId, http);
        assertFalse("a download should start", http.attempts.isEmpty());
        return http.attempts.get(http.attempts.size() - 1);
    }

    private static void respond(Attempt attempt, String payload) throws Exception {
        assertNotNull("the callback is registered", attempt.callback);
        attempt.callback.onResponse(attempt.call, new Response.Builder()
                .request(attempt.request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(payload, MediaType.get("image/png")))
                .build());
    }

    private static void fail(Attempt attempt) {
        assertNotNull("the callback is registered", attempt.callback);
        attempt.callback.onFailure(attempt.call, new IOException("fixture offline"));
    }

    /** Reads the ownership map as it exists before and after the fix. */
    private static int pendingCount() throws Exception {
        Field field = ArtworkNetworkCache.class.getDeclaredField("IN_FLIGHT");
        field.setAccessible(true);
        Object pending = field.get(null);
        if (pending instanceof Map) return ((Map<?, ?>) pending).size();
        if (pending instanceof Set) return ((Set<?>) pending).size();
        throw new AssertionError("unexpected IN_FLIGHT state: " + pending);
    }

    private static final class Attempt {
        final Request request;
        Call call;
        Callback callback;

        Attempt(Request request) {
            this.request = request;
        }
    }

    /** A local client that captures callbacks and injects synchronous start failures. */
    private static final class FakeHttp extends OkHttpClient {
        final List<Attempt> attempts = new ArrayList<>();
        int newCalls;
        RuntimeException newCallFailure;
        RuntimeException enqueueFailure;

        @Override
        public Call newCall(Request request) {
            newCalls++;
            if (newCallFailure != null) throw newCallFailure;
            final Attempt attempt = new Attempt(request);
            Call call = (Call) Proxy.newProxyInstance(
                    Call.class.getClassLoader(),
                    new Class<?>[]{Call.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "enqueue":
                                if (enqueueFailure != null) throw enqueueFailure;
                                attempt.callback = (Callback) args[0];
                                return null;
                            case "request":
                                return request;
                            case "clone":
                                return proxy;
                            case "isExecuted":
                            case "isCanceled":
                                return false;
                            case "timeout":
                                return Timeout.NONE;
                            case "toString":
                                return "fake-call";
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            default:
                                return null;
                        }
                    });
            attempt.call = call;
            attempts.add(attempt);
            return call;
        }
    }
}
