package com.eza.spicyex.lyrics.processing;

import com.eza.spicyex.lyrics.LyricResponseReader;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import okio.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class F5GoogleResponseLimitTest {
    @Test public void oversizedGoogleBodyUsesExistingRetryAndNeverReturnsPartialSuccess() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            BufferedSource source = Okio.buffer(new Source() {
                long left = LyricResponseReader.GOOGLE_BYTES + 1L;
                public long read(Buffer sink, long count) {
                    if (left == 0) return -1;
                    long size = Math.min(left, Math.min(count, 8192));
                    for (long i = 0; i < size; i++) sink.writeByte(' ');
                    left -= size;
                    return size;
                }
                public Timeout timeout() { return Timeout.NONE; }
                public void close() { closed.incrementAndGet(); }
            });
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(ResponseBody.create(null, -1, source)).build();
        }).build();
        try {
            Method send = GoogleEnhancer.class.getDeclaredMethod("sendRequest", Request.class, OkHttpClient.class);
            send.setAccessible(true);
            Request request = new Request.Builder().url("https://google.invalid/")
                    .tag(String.class, "f5-transport#1").build();
            GoogleEnhancer.HttpResult result = (GoogleEnhancer.HttpResult) send.invoke(null, request, client);
            assertEquals("", result.body);
            assertEquals("IOException", result.failureReason);
            assertEquals(2, result.attempts);
            assertEquals(2, requests.get());
            assertEquals(2, closed.get());
        } finally {
            client.dispatcher().cancelAll();
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }
}
