package com.eza.spicyex.lyrics.providers;

import android.content.Context;
import com.eza.spicyex.SpotifyTrack;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.catalog.*;
import com.eza.spicyex.lyrics.catalog.CatalogSource.ProviderStatus;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences;
import com.google.gson.JsonArray;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises adapter exits and the same pure catalog/planner path used after persistence. */
public class F4ProviderFailureOriginTest {
    private static final SpotifyTrack TRACK = new SpotifyTrack(
            "Song", "Artist", "Album", "spotify:track:f4", 0, "", 0, null, 180000, false);
    private final List<OkHttpClient> clients = new ArrayList<>();

    private static final class Result implements LyricsRepository.ResultCallback {
        final CountDownLatch done = new CountDownLatch(1);
        ProviderFailure failure;
        LyricsDocument document;
        String error;
        int successes;
        int errors;
        public void onSuccess(LyricsDocument value) { document = value; successes++; done.countDown(); }
        public void onError(String value) { error = value; errors++; done.countDown(); }
        public void onFailure(ProviderFailure value) { failure = value; onError(value.message); }
        Result await() throws Exception { assertTrue("terminal callback", done.await(5, TimeUnit.SECONDS)); return this; }
    }

    private OkHttpClient client(Interceptor interceptor) {
        OkHttpClient client = new OkHttpClient.Builder().retryOnConnectionFailure(false)
                .addInterceptor(interceptor).build();
        clients.add(client);
        return client;
    }

    private static Response response(Interceptor.Chain chain, int code, String body) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("provider wording is irrelevant")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
    }

    private static LyricsRepository.Parser parserFailure(String method, String message) {
        LyricsParser real = new LyricsParser(null);
        return (LyricsRepository.Parser) Proxy.newProxyInstance(
                LyricsRepository.Parser.class.getClassLoader(), new Class[]{LyricsRepository.Parser.class},
                (proxy, called, args) -> {
                    if (called.getName().equals(method)) throw new IllegalStateException(message);
                    try { return called.invoke(real, args); }
                    catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                });
    }

    private static ProviderRecord assertRetry(SourceId source, Result result, ProviderStatus expected,
                                               long delayMs) {
        assertNotNull(result.failure);
        assertEquals(expected, result.failure.status);
        assertEquals(0, result.successes);
        assertEquals(1, result.errors);
        CatalogPolicy policy = new CatalogPolicy(Collections.singletonList(source), false);
        CatalogState empty = CatalogState.empty("f4");
        CatalogChange change = CatalogDecisions.providerFailure(empty, policy, source,
                result.failure.status, 1000L);
        assertTrue(change.accepted);
        assertTrue(change.putCandidates.isEmpty());
        ProviderRecord record = change.putProviders.get(0);
        CatalogState stored = new CatalogState("f4", null, Collections.singletonMap(source, record),
                null, null, true);
        AcquisitionPlanner.Plan suppressed = AcquisitionPlanner.plan(stored, policy,
                CatalogDecisions.render(stored, policy), 1000L + delayMs - 1, true);
        assertEquals(AcquisitionPlanner.Action.NONE, suppressed.action);
        assertEquals(1000L + delayMs, suppressed.retryAtMs);
        assertEquals(AcquisitionPlanner.Action.FETCH, AcquisitionPlanner.plan(stored, policy,
                CatalogDecisions.render(stored, policy), 1000L + delayMs, true).action);
        return record;
    }

    @After public void closeClients() {
        for (OkHttpClient client : clients) {
            client.dispatcher().cancelAll();
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }

    @Test public void appleTransportAndParserWordsCannotCreateDurableAbsence() throws Exception {
        for (String message : new String[]{"stream was empty", "404 bytes read", "no lyrics field"}) {
            Result network = new Result();
            new LyricsRepository(client(chain -> { throw new IOException(message); }),
                    new LyricsParser(null), null, null).fetchSource(null, TRACK, 1,
                    LyricsSourcePreferences.Source.APPLE_MUSIC, false, network);
            assertRetry(SourceId.APPLE, network.await(), ProviderStatus.TRANSIENT_ERROR, 30000L);
            Result parsed = new Result();
            new LyricsRepository(client(chain -> response(chain, 200, "{}")),
                    parserFailure("parseSpicyLyrics", message), null, null).fetchSource(null, TRACK, 1,
                    LyricsSourcePreferences.Source.APPLE_MUSIC, false, parsed);
            assertRetry(SourceId.APPLE, parsed.await(), ProviderStatus.TRANSIENT_ERROR, 30000L);
        }
    }

    @Test public void actualHttp404IsAbsenceEvenWhenResponseTextSaysTimeout() throws Exception {
        Result result = new Result();
        new LyricsRepository(client(chain -> response(chain, 404, "timeout, parse failed")),
                new LyricsParser(null), null, null).fetchSource(null, TRACK, 1,
                LyricsSourcePreferences.Source.APPLE_MUSIC, false, result);
        assertRetry(SourceId.APPLE, result.await(), ProviderStatus.NOT_FOUND, 86400000L);
    }

    @Test public void lrclibParserMessagesAndConfirmedSearchMissHaveDifferentHorizons() {
        for (String message : new String[]{"empty JSON input", "404 token", "no lyrics key"}) {
            Result result = new Result();
            new LyricsRepository(null, parserFailure("parseLrclibLyrics", message), null, null)
                    .deliverMergedLrclib(null, TRACK, 1, result, "strict LRCLIB",
                            new LyricsProviderChain(1, null), false, new JsonArray());
            assertRetry(SourceId.LRCLIB, result, ProviderStatus.TRANSIENT_ERROR, 30000L);
        }
        Result miss = new Result();
        new LyricsRepository(null, new LyricsParser(null), null, null).deliverMergedLrclib(
                null, TRACK, 1, miss, "network empty 404 no lyrics",
                new LyricsProviderChain(1, null), false, new JsonArray());
        assertRetry(SourceId.LRCLIB, miss, ProviderStatus.NOT_FOUND, 86400000L);
    }

    private Result lrclibVariants(Interceptor interceptor) throws Exception {
        Result result = new Result();
        LyricsRepository repository = new LyricsRepository(client(interceptor), new LyricsParser(null), null, null);
        Method method = LyricsRepository.class.getDeclaredMethod("fetchLrclibVariant", Context.class,
                SpotifyTrack.class, int.class, LyricsRepository.ResultCallback.class, String.class,
                LyricsProviderChain.class, boolean.class, List.class, int.class, JsonArray.class, boolean.class);
        method.setAccessible(true);
        method.invoke(repository, null, TRACK, 1, result, "empty 404 no lyrics", new LyricsProviderChain(1, null),
                false, Arrays.asList("https://lrclib.invalid/first", "https://lrclib.invalid/last"),
                0, new JsonArray(), false);
        return result.await();
    }

    @Test public void lrclibLaterEmptySearchDoesNotEraseAnEarlierTransientFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Result result = lrclibVariants(chain -> {
            if (calls.getAndIncrement() == 0) throw new IOException("no lyrics: 404 empty stream");
            return response(chain, 200, "[]");
        });
        assertRetry(SourceId.LRCLIB, result, ProviderStatus.TRANSIENT_ERROR, 30000L);
        assertEquals(2, calls.get());
        assertRetry(SourceId.LRCLIB, lrclibVariants(chain -> response(chain, 200, "[]")),
                ProviderStatus.NOT_FOUND, 86400000L);
        assertRetry(SourceId.LRCLIB, lrclibVariants(chain -> response(chain, 200, "{}")),
                ProviderStatus.TRANSIENT_ERROR, 30000L);
    }

    @Test public void amllDirectMissThenSearchFailureIsTransientAndCleanMissIsDurable() throws Exception {
        for (String searchBody : new String[]{"[]", "empty JSON input", "{}", "[null]"}) {
            Result result = new Result();
            OkHttpClient http = client(chain -> chain.request().url().host().contains("stevexmh")
                    ? response(chain, 404, "timeout") : response(chain, 200, searchBody));
            new LyricsRepository(http, new LyricsParser(null), null, null).fetchSource(null, TRACK, 1,
                    LyricsSourcePreferences.Source.AMLL, false, result);
            assertRetry(SourceId.AMLL, result.await(), searchBody.equals("[]")
                    ? ProviderStatus.NOT_FOUND : ProviderStatus.TRANSIENT_ERROR,
                    searchBody.equals("[]") ? 86400000L : 30000L);
        }
        Result network = new Result();
        new LyricsRepository(client(chain -> { throw new IOException("empty 404 no lyrics"); }),
                new LyricsParser(null), null, null).fetchSource(null, TRACK, 1,
                LyricsSourcePreferences.Source.AMLL, false, network);
        assertRetry(SourceId.AMLL, network.await(), ProviderStatus.TRANSIENT_ERROR, 30000L);
    }

    @Test public void amllActual404RemainsAbsenceAcrossDirectAndSearchRequests() throws Exception {
        Result result = new Result();
        new LyricsRepository(client(chain -> response(chain, 404, "empty parser timeout")),
                new LyricsParser(null), null, null).fetchSource(null, TRACK, 1,
                LyricsSourcePreferences.Source.AMLL, false, result);
        assertRetry(SourceId.AMLL, result.await(), ProviderStatus.NOT_FOUND, 86400000L);
    }

    @Test public void qqServiceErrorsAndMalformedSearchAreTransientButValidEmptyListIsAbsence() throws Exception {
        for (String body : new String[]{"empty", "{}",
                "{\"music.search.SearchCgiService\":{\"code\":404}}",
                "{\"music.search.SearchCgiService\":{\"code\":0,\"data\":{\"body\":{\"song\":{\"list\":[]}}}}}"}) {
            Result result = new Result();
            new QqMusicAdapter(client(chain -> response(chain, 200, body)), new LyricsParser(null), null)
                    .fetch(null, TRACK, 1, result);
            boolean absent = body.contains("\"list\":[]");
            assertRetry(SourceId.QQ, result.await(), absent ? ProviderStatus.NOT_FOUND : ProviderStatus.TRANSIENT_ERROR,
                    absent ? 86400000L : 30000L);
        }
    }

    @Test public void nativeUntypedErrorsCannotEstablishAbsenceFromTheirWording() throws Exception {
        Result result = new Result();
        LyricsRepository.NativeLyricsProvider nativeProvider = new LyricsRepository.NativeLyricsProvider() {
            public LyricsDocument getNativeLyricsDocument(SpotifyTrack track) { return null; }
            public void requestNativeLyrics(SpotifyTrack track, RequestCallback callback) {
                callback.onResult(null, "empty 404 no lyrics disabled socket");
            }
        };
        new LyricsRepository(null, new LyricsParser(null), nativeProvider, null).fetchSource(
                null, TRACK, 1, LyricsSourcePreferences.Source.SPOTIFY, false, result);
        assertRetry(SourceId.SPOTIFY_NATIVE, result.await(), ProviderStatus.TRANSIENT_ERROR, 30000L);
    }

    @Test public void parserAbsenceIsTypedButMalformedPayloadsStayTransient() {
        LyricsParser parser = new LyricsParser(null);
        for (String raw : new String[]{"{}", "empty", "{\"lrc\":{\"lyric\":null}}", "{\"code\":404,\"lrc\":{\"lyric\":\"\"}}"}) {
            try { parser.parseNeteaseLyrics(null, TRACK, raw); fail("expected failure"); }
            catch (RuntimeException failure) {
                assertEquals(ProviderStatus.TRANSIENT_ERROR,
                        ProviderFailure.from(failure, "no lyrics empty 404").status);
            }
        }
        for (String raw : new String[]{"{\"nolyric\":true,\"code\":200}",
                "{\"lrc\":{\"lyric\":\"\"},\"code\":200}"}) {
            try { parser.parseNeteaseLyrics(null, TRACK, raw); fail("expected absence"); }
            catch (ProviderFailure failure) { assertEquals(ProviderStatus.NOT_FOUND, failure.status); }
        }
        for (String raw : new String[]{"{}", "{\"lyric\":null}", "{\"lyric\":\"a\"}",
                "{\"lyric\":\"\",\"code\":404}"}) {
            try { parser.parseQqMusicLyrics(null, TRACK, raw); fail("expected failure"); }
            catch (RuntimeException failure) {
                assertEquals(ProviderStatus.TRANSIENT_ERROR, ProviderFailure.from(failure, "empty").status);
            }
        }
        try { parser.parseQqMusicLyrics(null, TRACK, "{\"lyric\":\"\",\"code\":0}"); fail("expected absence"); }
        catch (ProviderFailure failure) { assertEquals(ProviderStatus.NOT_FOUND, failure.status); }
    }

    @Test public void usableLrclibResponseStillWinsAfterAnEarlierNetworkFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Result result = lrclibVariants(chain -> {
            if (calls.getAndIncrement() == 0) throw new IOException("empty stream");
            return response(chain, 200, "[{\"trackName\":\"Song\",\"syncedLyrics\":\"[00:01.00] hello\\n[00:02.00] world\"}]");
        });
        assertNotNull(result.document);
        assertEquals(1, result.successes);
        assertEquals(0, result.errors);
        assertNull(result.failure);
        assertEquals("hello", result.document.lines.get(0).text);
        assertEquals(1, result.document.generation);
        assertEquals(2, calls.get());
    }

    @Test public void legacyCallbackReceivesNonNullMessageWithoutKnowingTypedFailures() {
        AtomicInteger errors = new AtomicInteger();
        LyricsRepository.ResultCallback legacy = new LyricsRepository.ResultCallback() {
            public void onSuccess(LyricsDocument document) { fail("unexpected success"); }
            public void onError(String message) { assertEquals("", message); errors.incrementAndGet(); }
        };
        legacy.onFailure(ProviderFailure.transientError(null));
        assertEquals(1, errors.get());
    }
}
