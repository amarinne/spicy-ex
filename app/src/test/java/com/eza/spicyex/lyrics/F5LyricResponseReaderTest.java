package com.eza.spicyex.lyrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class F5LyricResponseReaderTest {
    private static final class StreamBody extends ResponseBody {
        final Buffer data;
        final long declared;
        final MediaType type;
        final BufferedSource source;
        boolean closed;
        long pulled;
        long largestRead;
        boolean fail;
        StreamBody(byte[] bytes, long declared, String type, int chunk) {
            data = new Buffer().write(bytes);
            this.declared = declared;
            this.type = type == null ? null : MediaType.get(type);
            source = Okio.buffer(new Source() {
                public long read(Buffer sink, long count) throws IOException {
                    largestRead = Math.max(largestRead, count);
                    if (fail) throw new IOException("broken stream");
                    long read = data.read(sink, Math.min(count, chunk));
                    if (read > 0) pulled += read;
                    return read;
                }
                public Timeout timeout() { return Timeout.NONE; }
                public void close() { closed = true; }
            });
        }
        public MediaType contentType() { return type; }
        public long contentLength() { return declared; }
        public BufferedSource source() { return source; }
    }

    private static StreamBody text(String value, long declared, int chunk) {
        return new StreamBody(value.getBytes(StandardCharsets.UTF_8), declared, null, chunk);
    }

    private static void oversized(StreamBody body, int max) throws Exception {
        try {
            LyricResponseReader.read(body, max);
            fail("oversize must never return a truncated success");
        } catch (IOException expected) {
            assertTrue(body.closed);
        }
    }

    @Test public void unknownChunkedAndDeceptiveLengthsReadCompleteExactBoundary() throws Exception {
        for (long declared : new long[]{-1, 0, 1, 7, 8}) {
            StreamBody body = text("abc你🙂", declared, 1); // 10 decoded bytes, split within code points.
            assertEquals("abc你🙂", LyricResponseReader.read(body, 10));
            assertEquals(10, body.pulled);
            assertTrue(body.closed);
        }
        StreamBody overdeclared = text("abc", 10, 2);
        assertEquals("abc", LyricResponseReader.read(overdeclared, 10));
        assertTrue(overdeclared.closed); // Same length semantics as ResponseBody.string().
    }

    @Test public void unknownAndUnderdeclaredOversizeAlwaysThrowAndClose() throws Exception {
        for (long declared : new long[]{-1, 0, 1, 10}) {
            oversized(text("01234567890", declared, 1), 10);
        }
    }

    @Test public void declaredOversizeRejectsBeforePulling() throws Exception {
        StreamBody body = text("abc", Long.MAX_VALUE, 1);
        oversized(body, 10);
        assertEquals(0, body.pulled);
    }

    @Test public void zeroBudgetAcceptsOnlyEmptyBody() throws Exception {
        StreamBody empty = text("", -1, 1);
        assertEquals("", LyricResponseReader.read(empty, 0));
        assertTrue(empty.closed);
        oversized(text("a", -1, 1), 0);
    }

    @Test public void brokenStreamClosesAndReturnsNoPartialBody() throws Exception {
        StreamBody body = text("abc", -1, 1);
        body.fail = true;
        try {
            LyricResponseReader.read(body, 10);
            fail("broken stream accepted");
        } catch (IOException expected) { assertTrue(body.closed); }
    }

    @Test public void charsetBomAndMalformedBytesMatchOkHttpStringSemantics() throws Exception {
        byte[][] values = {
                "你好🙂".getBytes(StandardCharsets.UTF_8),
                new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'a'},
                new byte[]{(byte) 0xff, (byte) 0xfe, 'a', 0, (byte) 0xe0, 0x4f},
                new byte[]{(byte) 0xfe, (byte) 0xff, 0, 'a'},
                new byte[]{(byte) 0xff, (byte) 0xfe, 0, 0, 'a', 0, 0, 0},
                new byte[]{0, 0, (byte) 0xfe, (byte) 0xff, 0, 0, 0, 'a'},
                new byte[]{(byte) 0xc3, 0x28}, // Malformed UTF-8 keeps OkHttp's replacement policy.
                new byte[]{(byte) 0xe9}
        };
        for (byte[] bytes : values) {
            for (String type : new String[]{null, "text/plain; charset=utf-8",
                    "text/plain; charset=iso-8859-1", "text/plain; charset=unsupported"}) {
                MediaType media = type == null ? null : MediaType.get(type);
                String expected = ResponseBody.create(bytes, media).string();
                StreamBody body = new StreamBody(bytes, -1, type, 1);
                assertEquals(expected, LyricResponseReader.read(body, bytes.length));
                assertTrue(body.closed);
            }
        }
    }

    @Test public void gzipExpansionIsCountedAfterDecompression() throws Exception {
        Buffer compressed = new Buffer();
        try (BufferedSink gzip = Okio.buffer(new GzipSink(compressed))) {
            gzip.writeUtf8("x".repeat(100000));
        }
        assertTrue(compressed.size() < 1000);
        final boolean[] closed = {false};
        BufferedSource decoded = Okio.buffer(new ForwardingSource(new GzipSource(compressed)) {
            public void close() throws IOException { closed[0] = true; super.close(); }
        });
        try {
            LyricResponseReader.read(ResponseBody.create(MediaType.get("text/plain"), -1, decoded), 1000);
            fail("compressed expansion accepted");
        } catch (IOException expected) { assertTrue(closed[0]); }
    }

    @Test public void endlessSourceStopsAtBudgetPlusOneWithOnlyOneReadAheadSegment() throws Exception {
        final long[] produced = {0};
        final boolean[] closed = {false};
        BufferedSource source = Okio.buffer(new Source() {
            public long read(Buffer sink, long count) {
                assertTrue(count <= 8192);
                for (long i = 0; i < count; i++) sink.writeByte('x');
                produced[0] += count;
                return count;
            }
            public Timeout timeout() { return Timeout.NONE; }
            public void close() { closed[0] = true; }
        });
        try {
            LyricResponseReader.read(ResponseBody.create(null, -1, source), 32768);
            fail("endless stream accepted");
        } catch (IOException expected) {
            assertTrue(closed[0]);
            assertTrue(produced[0] <= 32768 + 8192);
        }
    }
}
