package com.eza.spicyex.lyrics;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpicyTransportTest {
    private final long now = System.currentTimeMillis();
    private final long[] persisted = new long[4];
    private final SpicyCircuitBreaker breaker = new SpicyCircuitBreaker(new SpicyCircuitBreaker.Store() {
        public long[] load() { return persisted.clone(); }
        public void save(long until, int rung, long trip, long probe) {
            persisted[0] = until; persisted[1] = rung; persisted[2] = trip; persisted[3] = probe;
        }
    }, () -> now, () -> 0.5);
    private Request request(boolean probe) {
        return new Request.Builder().url("https://api.spicylyrics.org/query")
                .tag(SpicyTransport.Probe.class, probe ? new SpicyTransport.Probe() : null).build();
    }
    private OkHttpClient client(Interceptor origin) {
        return SpicyTransport.client(new OkHttpClient(), breaker).newBuilder().addInterceptor(origin).build();
    }
    private Response response(Interceptor.Chain chain, int status, String body) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").header("Retry-After", "120")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
    }
    @Test public void transportRefusalsSuppressBeforeTouchingOriginAndOnlyOneUserProbePasses() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient client = client(chain -> { calls.incrementAndGet(); return response(chain, 429, "{}"); });
        for (int i = 0; i < 2; i++) try (Response res = client.newCall(request(false)).execute()) { assertEquals(429, res.code()); }
        assertEquals(120000, persisted[0] - now);
        assertThrows(SpicyCircuitBreaker.Suppressed.class, () -> client.newCall(request(false)).execute());
        assertEquals(2, calls.get());
        try (Response res = client.newCall(request(true)).execute()) { assertEquals(429, res.code()); }
        assertThrows(SpicyCircuitBreaker.Suppressed.class, () -> client.newCall(request(true)).execute());
        assertEquals(3, calls.get());
        assertEquals(1, persisted[1]);
    }
    @Test public void envelope429And503NeverTripTransportBreaker() throws Exception {
        for (int code : new int[]{429,503,429,503}) {
            OkHttpClient client = client(chain -> response(chain, 200,
                    "{\"queries\":[{\"operationId\":\"0\",\"result\":{\"httpStatus\":" + code + "}}]}"));
            try (Response res = client.newCall(request(false)).execute()) { assertEquals(200, res.code()); }
        }
        assertEquals(0, persisted[0]);
    }
    @Test public void transport401DoesNotRetryOrTrip() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient client = client(chain -> { calls.incrementAndGet(); return response(chain, 401, "{}"); });
        for (int i = 0; i < 3; i++) try (Response res = client.newCall(request(false)).execute()) { assertEquals(401, res.code()); }
        assertEquals(3, calls.get());
        assertEquals(0, persisted[0]);
    }
    @Test public void unreadableNetworkTripsOncePerCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        OkHttpClient client = client(chain -> { calls.incrementAndGet(); throw new IOException("fixture offline"); });
        assertThrows(IOException.class, () -> client.newCall(request(false)).execute());
        assertThrows(IOException.class, () -> client.newCall(request(false)).execute());
        assertThrows(SpicyCircuitBreaker.Suppressed.class, () -> client.newCall(request(false)).execute());
        assertEquals(2, calls.get());
    }
    @Test public void requestHasWholeCallDeadlineAndRouteFallbackWithoutRedirects() {
        OkHttpClient client = SpicyTransport.client(new OkHttpClient(), breaker);
        assertEquals(30000, client.callTimeoutMillis());
        assertEquals(4000, client.connectTimeoutMillis());
        assertTrue("dead routes must be raced, not tried in order",
                client.fastFallback());
        assertTrue(client.retryOnConnectionFailure());
        assertFalse(client.followRedirects());
        assertFalse(client.followSslRedirects());
    }
}
