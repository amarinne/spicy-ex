package com.eza.spicyex.lyrics;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Shared Spicy transport; native and LRCLIB keep their own network policy. */
final class SpicyTransport {
    static final class Probe {}
    private static SpicyCircuitBreaker breaker;
    static synchronized SpicyCircuitBreaker breaker(Context context) {
        if (breaker == null) {
            SharedPreferences prefs = context.getSharedPreferences("SpicyLyrics_QueryBreaker_g1", Context.MODE_PRIVATE);
            breaker = new SpicyCircuitBreaker(new SpicyCircuitBreaker.Store() {
                public long[] load() {
                    return new long[]{prefs.getLong("openUntil", 0), prefs.getInt("ladderIndex", 0),
                            prefs.getLong("lastTripAt", 0), prefs.getLong("lastProbeAt", 0)};
                }
                public void save(long until, int rung, long trip, long probe) {
                    prefs.edit().putLong("openUntil", until).putInt("ladderIndex", rung)
                            .putLong("lastTripAt", trip).putLong("lastProbeAt", probe).commit();
                }
            }, System::currentTimeMillis, Math::random);
        }
        return breaker;
    }
    static OkHttpClient client(OkHttpClient base, SpicyCircuitBreaker breaker) {
        // The whole-call budget must cover a dead-route fallback plus a slow worker: on a
        // blackholed IPv6 route the connect timeout alone burns ~10s before IPv4 is tried,
        // and an uncached worker lookup legitimately takes seconds upstream. A 15s budget
        // turned that sum into timeouts that tripped the breaker and suppressed every later
        // attempt. Retries stay bounded by the breaker ladder, not by this ceiling.
        // Connect itself is capped tight so the fallback happens in seconds rather than
        // stalling the whole lyric chain behind one dead route; a slow-but-working route
        // still succeeds via the fallback address.
        return base.newBuilder().callTimeout(30, TimeUnit.SECONDS)
                // Happy Eyeballs: race IPv4/IPv6 instead of trying routes in order.
                .fastFallback(true)
                .connectTimeout(4, TimeUnit.SECONDS)
                // Route fallback stays on: a dead route (e.g. blackholed IPv6) falls back to
                // the next DNS route instead of failing the call. Retries happen only before
                // dispatch, so provider failover and the breaker see the same per-call outcome.
                .retryOnConnectionFailure(true).followRedirects(false).followSslRedirects(false)
                .addInterceptor(chain -> {
                    SpicyCircuitBreaker.Lease lease;
                    try {
                        lease = breaker.acquire(chain.request().tag(Probe.class) != null);
                    } catch (SpicyCircuitBreaker.Suppressed e) {
                        SpicyNetworkDiagnostics.recordTransport("rate-limited", 0, e.retryAfterMs);
                        throw e;
                    }
                    Response response;
                    try { response = chain.proceed(chain.request()); }
                    catch (IOException e) {
                        breaker.failure(lease, null);
                        SpicyNetworkDiagnostics.recordTransport("upstream-error", 0, null);
                        throw e;
                    }
                    Long delay = SpicyCircuitBreaker.retryAfter(response.header("Retry-After"), System.currentTimeMillis());
                    if (SpicyCircuitBreaker.tripStatus(response.code())) breaker.failure(lease, delay);
                    else breaker.success(lease);
                    if (!response.isSuccessful()) SpicyNetworkDiagnostics.recordTransport(
                            response.code() == 429 ? "rate-limited" : "upstream-error", response.code(), delay);
                    return response;
                }).build();
    }
}
