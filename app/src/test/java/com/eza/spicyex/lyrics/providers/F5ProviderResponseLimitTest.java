package com.eza.spicyex.lyrics.providers;

import android.content.Context;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricResponseReader;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.catalog.*;
import com.eza.spicyex.lyrics.catalog.CatalogSource.*;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences;
import com.google.gson.JsonArray;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import okio.*;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real adapter callbacks and catalog decisions; interceptors and streams never contact providers. */
public class F5ProviderResponseLimitTest {
    private static final SpotifyTrack TRACK = new SpotifyTrack("Song", "Artist", "Album",
            "spotify:track:f5", 0, "", 0, null, 180000, false);
    private final List<OkHttpClient> clients = new ArrayList<>();

    private static final class Result implements LyricsRepository.ResultCallback {
        final CountDownLatch done = new CountDownLatch(1);
        ProviderFailure failure;
        LyricsDocument document;
        int successes;
        int errors;
        public void onSuccess(LyricsDocument doc) { document = doc; successes++; done.countDown(); }
        public void onError(String message) { errors++; done.countDown(); }
        public void onFailure(ProviderFailure value) { failure = value; onError(value.message); }
        Result await() throws Exception {
            assertTrue("terminal adapter callback", done.await(5, TimeUnit.SECONDS));
            return this;
        }
        void assertTransient() throws Exception {
            await();
            assertEquals(0, successes);
            assertEquals(1, errors);
            assertNotNull(failure);
            assertEquals(ProviderStatus.TRANSIENT_ERROR, failure.status);
        }
    }

    private OkHttpClient client(Interceptor interceptor) {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(interceptor).build();
        clients.add(client);
        return client;
    }
    private static Response response(Interceptor.Chain chain, ResponseBody body) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build();
    }
    private static Response response(Interceptor.Chain chain, String text) {
        return response(chain, ResponseBody.create(text, MediaType.get("application/json")));
    }
    private static ResponseBody oversized(int budget, boolean[] closed) {
        // Unknown-length stream with valid JSON followed by whitespace. Before the fix it parses
        // successfully, so the assertion distinguishes a read limit from a parser rejection.
        BufferedSource source = Okio.buffer(new Source() {
            long remaining = budget + 1L;
            boolean prefix = true;
            public long read(Buffer sink, long count) {
                if (remaining == 0) return -1;
                long size = Math.min(remaining, Math.min(count, 8192));
                for (long i = 0; i < size; i++) {
                    sink.writeByte(prefix ? '[' : remaining == budget ? ']' : ' ');
                    prefix = false;
                    remaining--;
                }
                return size;
            }
            public Timeout timeout() { return Timeout.NONE; }
            public void close() { closed[0] = true; }
        });
        return ResponseBody.create(MediaType.get("application/json"), -1, source);
    }
    private static LyricsDocument good() {
        LyricsDocument doc = new LyricsDocument();
        doc.type = "Word";
        LyricsLine line = new LyricsLine();
        line.text = "previously valid lyric";
        line.startMs = 1000;
        line.endMs = 2000;
        doc.lines.add(line);
        return doc;
    }
    @After public void closeClients() {
        for (OkHttpClient client : clients) {
            client.dispatcher().cancelAll();
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }

    @Test public void oversizedAppleNeverReachesParserAndKeepsGoodCatalogCandidate() throws Exception {
        AtomicInteger parses = new AtomicInteger();
        LyricsRepository.Parser parser = (LyricsRepository.Parser) Proxy.newProxyInstance(
                LyricsRepository.Parser.class.getClassLoader(), new Class[]{LyricsRepository.Parser.class},
                (proxy, method, args) -> { parses.incrementAndGet(); return good(); });
        boolean[] closed = {false};
        Result result = new Result();
        new LyricsRepository(client(chain -> response(chain,
                oversized(LyricResponseReader.LYRIC_BYTES, closed))), parser, null, null).fetchSource(
                null, TRACK, 1, LyricsSourcePreferences.Source.APPLE_MUSIC, false, result);
        result.assertTransient();
        assertTrue(closed[0]);
        assertEquals("stop before parsing or cache success", 0, parses.get());

        CatalogCandidate cached = CatalogAdapters.buildCandidate(SourceId.APPLE, TRACK, good(),
                MatchMethod.EXACT_SPOTIFY_ID, "f5", "good stored raw", 1, 1);
        assertNotNull(cached);
        CatalogPolicy policy = new CatalogPolicy(Collections.singletonList(SourceId.APPLE), false);
        CatalogState state = new CatalogState("f5", Collections.singletonList(cached), null,
                null, null, true);
        CatalogChange change = CatalogDecisions.providerFailure(state, policy, SourceId.APPLE,
                result.failure.status, 1000);
        assertTrue(change.putCandidates.isEmpty());
        assertTrue(change.deleteCandidateIds.isEmpty());
        assertSame(cached, change.resolution.winner);
        assertEquals(31000, AcquisitionPlanner.dueAtMs(change.putProviders.get(0), false));
        assertEquals("good stored raw", CatalogCodec.inflate(cached.rawPayload));
    }

    @Test public void oversizedMetadataSearchIsTransientForBothAdapters() throws Exception {
        for (boolean qq : new boolean[]{true, false}) {
            boolean[] closed = {false};
            OkHttpClient http = client(chain -> response(chain,
                    oversized(LyricResponseReader.SEARCH_BYTES, closed)));
            Result result = new Result();
            if (qq) new QqMusicAdapter(http, new LyricsParser(null), null).fetch(null, TRACK, 1, result);
            else new NeteaseAdapter(http, new LyricsParser(null)).fetch(null, TRACK, 1, result);
            result.assertTransient();
            assertTrue(closed[0]);
        }
    }

    @Test public void oversizedAmllThenCleanSearchMissRemainsTransient() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        boolean[] closed = {false};
        Result result = new Result();
        new LyricsRepository(client(chain -> {
            if (chain.request().url().host().contains("stevexmh")) return response(chain,
                    oversized(LyricResponseReader.LYRIC_BYTES, closed));
            searches.incrementAndGet();
            return response(chain, "[]");
        }), new LyricsParser(null), null, null).fetchSource(null, TRACK, 1,
                LyricsSourcePreferences.Source.AMLL, false, result);
        result.assertTransient();
        assertTrue(closed[0]);
        assertTrue(searches.get() > 0);
    }

    @Test public void oversizedAppleDoesNotStopOrderedNativeFallback() throws Exception {
        boolean[] closed = {false};
        LyricsDocument nativeDoc = good();
        AtomicInteger nativeRequests = new AtomicInteger();
        LyricsRepository.NativeLyricsProvider nativeProvider = new LyricsRepository.NativeLyricsProvider() {
            public LyricsDocument getNativeLyricsDocument(SpotifyTrack track) { return null; }
            public void requestNativeLyrics(SpotifyTrack track, RequestCallback callback) {
                nativeRequests.incrementAndGet();
                callback.onResult(nativeDoc, null);
            }
        };
        Result result = new Result();
        new LyricsRepository(client(chain -> response(chain,
                oversized(LyricResponseReader.LYRIC_BYTES, closed))), new LyricsParser(null),
                nativeProvider, null).fetchLyrics(null, TRACK, 1, false, "", 0, null,
                new AcquisitionScope(Arrays.asList(SourceId.APPLE, SourceId.SPOTIFY_NATIVE), true), result);
        result.await();
        assertSame(nativeDoc, result.document);
        assertEquals(1, result.successes);
        assertEquals(0, result.errors);
        assertEquals(1, nativeRequests.get());
        assertTrue(closed[0]);
    }

    @Test public void oversizedLrclibVariantContinuesAndCannotTurnIntoDurableAbsence() throws Exception {
        for (String later : new String[]{"[]", "[{\"trackName\":\"Song\",\"artistName\":\"Artist\","
                + "\"syncedLyrics\":\"[00:01.00] hello\\n[00:02.00] world\"}]"}) {
            AtomicInteger requests = new AtomicInteger();
            boolean[] closed = {false};
            LyricsRepository repository = new LyricsRepository(client(chain -> requests.getAndIncrement() == 0
                    ? response(chain, oversized(LyricResponseReader.LYRIC_SEARCH_BYTES, closed))
                    : response(chain, later)), new LyricsParser(null), null, null);
            Result result = new Result();
            Method method = LyricsRepository.class.getDeclaredMethod("fetchLrclibVariant", Context.class,
                    SpotifyTrack.class, int.class, LyricsRepository.ResultCallback.class, String.class,
                    LyricsProviderChain.class, boolean.class, List.class, int.class, JsonArray.class, boolean.class);
            method.setAccessible(true);
            method.invoke(repository, null, TRACK, 1, result, "test", new LyricsProviderChain(1, null),
                    false, Arrays.asList("https://lrclib.invalid/first", "https://lrclib.invalid/last"),
                    0, new JsonArray(), false);
            result.await();
            assertTrue(closed[0]);
            assertEquals(2, requests.get());
            if (later.equals("[]")) result.assertTransient();
            else { assertEquals(1, result.successes); assertEquals(0, result.errors); }
        }
    }

    @Test public void oversizedWordLyricsStillRunQqAndNeteaseFallbacks() throws Exception {
        for (boolean qq : new boolean[]{true, false}) {
            boolean[] closed = {false};
            OkHttpClient http = client(chain -> response(chain,
                    oversized(LyricResponseReader.LYRIC_BYTES, closed)));
            Object adapter = qq ? new QqMusicAdapter(http, new LyricsParser(null), null)
                    : new NeteaseAdapter(http, new LyricsParser(null));
            Method method = adapter.getClass().getDeclaredMethod("fetchWordLyric", Context.class,
                    SpotifyTrack.class, SpotifyTrack.class, boolean.class, int.class, long.class,
                    LyricsRepository.ResultCallback.class, Runnable.class);
            method.setAccessible(true);
            CountDownLatch fallback = new CountDownLatch(1);
            Result result = new Result();
            method.invoke(adapter, null, TRACK, TRACK, false, 1, 123L, result,
                    (Runnable) fallback::countDown);
            assertTrue(fallback.await(5, TimeUnit.SECONDS));
            assertTrue(closed[0]);
            assertEquals(0, result.successes);
            assertEquals(0, result.errors);
        }
    }
}
