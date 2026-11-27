package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.Diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.InterruptedIOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * The AI layer's own HTTP client, separate from every other lyric call on purpose.
 *
 * <p>The shared lyrics client is tuned for the opposite problem: a 15-second read timeout so a slow
 * Spicy upstream fails over quickly to the next provider. A model call routinely takes longer than
 * that and has no fallback to fail over to, so putting it on the shared client would mean either
 * losing every long generation or slowing down every lyric fetch to accommodate it.
 *
 * <p>Three things this enforces that a plain call would not:
 *
 * <ul>
 *   <li><b>The response ceiling is applied while reading.</b> A runaway response is cut off at the
 *       limit rather than buffered whole and measured afterwards, which is the difference between
 *       rejecting a bad response and being taken down by one.</li>
 *   <li><b>Cancellation reaches the socket.</b> The signal cancels the {@link Call}, so a track
 *       change stops the transfer instead of merely ignoring its result. It still cannot promise
 *       the provider did not bill — nothing at this layer can.</li>
 *   <li><b>Nothing sensitive is logged.</b> Operation, status and byte count only: never a URL with
 *       a query, never a header, never a request or response body. Provider error bodies routinely
 *       echo the request, which for us is lyric text.</li>
 * </ul>
 */
public final class AiHttp {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String TAG = "AiHttp";

    /** Generous, because a long document legitimately takes a while and there is no fallback. */
    private static final int CONNECT_TIMEOUT_SECONDS = 15;
    /**
     * Read timeout is the gap allowed between bytes — and a completion request is not streamed, so
     * the provider sends nothing at all until it has finished generating.
     *
     * <p>That makes any read timeout a flat ceiling on generation time, and a ninety second one was
     * a harder wall than the deadline it sat behind: a model still working at ninety-one seconds
     * produced an IOException after dispatch, which is {@code DELIVERY_UNKNOWN} — possibly billed.
     * It is pinned to the maximum deadline so the runtime's derived, budget-sized deadline is the
     * only thing that decides when to stop waiting.
     */
    private static final int READ_TIMEOUT_SECONDS =
            (int) (AiContract.MAX_CALL_DEADLINE_MS / 1000L);
    private static final int WRITE_TIMEOUT_SECONDS = 30;

    private static volatile OkHttpClient client;

    private AiHttp() {
    }

    /** One response, already bounded and read. */
    public static final class Result {
        public final int status;
        public final String body;
        public final long bytes;
        /** Set when the transfer itself failed rather than returning a status. */
        public final AiProviderFailure failure;

        private Result(int status, String body, long bytes, AiProviderFailure failure) {
            this.status = status;
            this.body = AiText.nz(body);
            this.bytes = bytes;
            this.failure = failure;
        }

        static Result of(int status, String body, long bytes) {
            return new Result(status, body, bytes, null);
        }

        static Result streamed(int status, long bytes) {
            Result result = new Result(status, "", bytes, null);
            result.streamed = true;
            return result;
        }

        /**
         * True when the body arrived as server-sent events and went to the sink; {@link #body}
         * is then empty. False when the endpoint answered with an ordinary body.
         */
        public boolean streamed;

        static Result failed(AiProviderFailure failure) {
            return new Result(0, "", 0L, failure);
        }

        public boolean ok() {
            return failure == null && status >= 200 && status < 300;
        }

        /** Retry-After in milliseconds, or null. Seconds form only; a date form is ignored. */
        public Long retryAfterMs;
    }

    static OkHttpClient client() {
        OkHttpClient local = client;
        if (local == null) {
            synchronized (AiHttp.class) {
                local = client;
                if (local == null) {
                    local = new OkHttpClient.Builder()
                            // Happy Eyeballs: race IPv4/IPv6 instead of trying routes in order.
                            .fastFallback(true)
                            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            // The transport backstop must never fire before the per-call deadline the
                            // runtime is enforcing, or a slow answer becomes DELIVERY_UNKNOWN here
                            // instead of being cancelled deliberately there.
                            .callTimeout(AiContract.MAX_CALL_DEADLINE_MS, TimeUnit.MILLISECONDS)
                            // Route fallback must stay on. OkHttp 4 tries DNS routes in order
                            // (IPv6 first) with no Happy Eyeballs race, so a blackholed IPv6
                            // route would otherwise fail the call at the connect timeout even
                            // though IPv4 answers in under a second. Retrying a dead route is
                            // billing-safe: OkHttp only retries before the request is sent,
                            // never after dispatch, so a retried call was never served.
                            .retryOnConnectionFailure(true)
                            .build();
                    client = local;
                }
            }
        }
        return local;
    }

    public static Result get(String url, Map<String, String> headers, AiSignal signal,
                             int maxResponseBytes) {
        return execute(request(url, headers).get().build(), signal, maxResponseBytes, "get");
    }

    public static Result postJson(String url, Map<String, String> headers, String json,
                                  AiSignal signal, int maxResponseBytes) {
        Request.Builder builder = request(url, headers)
                .post(RequestBody.create(AiText.nz(json).getBytes(StandardCharsets.UTF_8), JSON));
        return execute(builder.build(), signal, maxResponseBytes, "post");
    }

    /** Receives each server-sent event's {@code data} payload, in order. */
    public interface StreamSink {
        void onData(String data);
    }

    /**
     * Raw byte bound for a streamed body. Every delta repeats a JSON envelope of a few hundred
     * bytes, so the raw stream is far larger than the text it carries; the adapter bounds the
     * accumulated text itself. This only stops a runaway stream from running forever.
     */
    static final long MAX_STREAM_BYTES = 64L * 1024L * 1024L;

    /**
     * Posts {@code json} and reads a server-sent event stream into {@code sink}.
     *
     * <p>Bytes keep flowing while the model works, so a gateway that drops a silent connection
     * (one closed a 40-row call at exactly 60 s on 2026-11-19) sees a live one. A non-2xx answer,
     * or an endpoint that ignores {@code stream} and returns an ordinary body, is read as
     * {@link #postJson} would read it, bounded by {@code maxResponseBytes}.
     */
    public static Result postJsonStream(String url, Map<String, String> headers, String json,
                                        AiSignal signal, int maxResponseBytes, StreamSink sink) {
        Request.Builder builder = request(url, headers)
                .header("Accept", "text/event-stream, application/json")
                .post(RequestBody.create(AiText.nz(json).getBytes(StandardCharsets.UTF_8), JSON));
        return execute(builder.build(), signal, maxResponseBytes, "stream", sink);
    }

    private static Result readStream(Response response, int maxBytes, StreamSink sink)
            throws IOException {
        ResponseBody body = response.body();
        String type = body == null || body.contentType() == null
                ? "" : body.contentType().toString().toLowerCase(java.util.Locale.ROOT);
        if (sink == null || !response.isSuccessful() || !type.contains("text/event-stream")) {
            return read(response, maxBytes);
        }
        BufferedSource source = body.source();
        long bytes = 0L;
        String line;
        while ((line = source.readUtf8Line()) != null) {
            bytes += line.length() + 1L;
            if (bytes > MAX_STREAM_BYTES) return Result.failed(AiProviderFailure.oversized(bytes));
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.equals("[DONE]")) break;
            if (!data.isEmpty()) sink.onData(data);
        }
        return Result.streamed(response.code(), bytes);
    }

    private static Request.Builder request(String url, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
        }
        return builder;
    }

    private static Result execute(Request request, AiSignal signal, int maxResponseBytes,
                                  String operation) {
        return execute(request, signal, maxResponseBytes, operation, null);
    }

    private static Result execute(Request request, AiSignal signal, int maxResponseBytes,
                                  String operation, StreamSink sink) {
        final Call call = client().newCall(request);
        AiSignal.Registration registration = signal == null ? null
                : signal.onAbort(new Runnable() {
                    @Override public void run() {
                        call.cancel();
                    }
                });
        try (Response response = call.execute()) {
            Result result = sink == null ? read(response, maxResponseBytes)
                    : readStream(response, maxResponseBytes, sink);
            result.retryAfterMs = retryAfterMs(response.header("Retry-After"));
            Diagnostics.event("Ai" + TAG, operation + ":" + result.status + ":" + result.bytes + "b");
            return result;
        } catch (InterruptedIOException interrupted) {
            // Cancelled or timed out. Either way the request may already have been served, so this
            // is delivery-unknown and never something to retry on our own initiative.
            if (signal != null && signal.isAborted()) {
                throw new AiCancelledException(signal.reason(), interrupted);
            }
            Diagnostics.event("Ai" + TAG, operation + ":timeout");
            return Result.failed(AiProviderFailure.deliveryUnknown(
                    AiProviderFailure.Cause.TIMEOUT, 0));
        } catch (IOException network) {
            if (signal != null && signal.isAborted()) {
                throw new AiCancelledException(signal.reason(), network);
            }
            Diagnostics.event("Ai" + TAG, operation + ":network");
            return Result.failed(AiProviderFailure.deliveryUnknown(
                    AiProviderFailure.Cause.NETWORK, 0));
        } catch (RuntimeException unexpected) {
            if (signal != null && signal.isAborted()) {
                throw new AiCancelledException(signal.reason(), unexpected);
            }
            Diagnostics.event("Ai" + TAG, operation + ":" + unexpected.getClass().getSimpleName());
            return Result.failed(AiProviderFailure.deliveryUnknown(
                    AiProviderFailure.Cause.NETWORK, 0));
        } finally {
            if (registration != null) registration.remove();
        }
    }

    /**
     * Reads at most {@code maxBytes}, refusing anything larger.
     *
     * <p>A declared Content-Length past the ceiling is refused without reading at all; otherwise the
     * body is pulled incrementally and abandoned the moment it crosses the limit.
     */
    private static Result read(Response response, int maxBytes) throws IOException {
        ResponseBody body = response.body();
        if (body == null) return Result.of(response.code(), "", 0L);
        long declared = body.contentLength();
        if (declared > maxBytes) {
            return Result.failed(AiProviderFailure.oversized(declared));
        }
        BufferedSource source = body.source();
        source.request(maxBytes + 1L);
        long buffered = source.getBuffer().size();
        if (buffered > maxBytes) {
            return Result.failed(AiProviderFailure.oversized(buffered));
        }
        String text = source.getBuffer().readString(StandardCharsets.UTF_8);
        return Result.of(response.code(), text, buffered);
    }

    /** Seconds form only. A HTTP-date is legal but rare here, and guessing at it is worse. */
    private static Long retryAfterMs(String header) {
        String value = AiText.nz(header).trim();
        if (value.isEmpty()) return null;
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? null : Math.min(seconds * 1000L, AiContract.RETRY_AFTER_CAP_MS);
        } catch (NumberFormatException notSeconds) {
            return null;
        }
    }
}
