package com.eza.spicyex.diagnostics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** One-shot asynchronous uploader. OkHttp retries and redirects are disabled. */
public final class DiagnosticUploader {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final int RESPONSE_LIMIT_BYTES = 32 * 1024;
    private static final String REPORT_PATH = "/v1/reports";
    private static final String RETENTION_POLICY_INDEFINITE = "indefinite";
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build();

    public void upload(String endpoint, SpicyDiagnosticReportFactory.Draft draft,
                       ResultCallback callback) {
        if (callback == null) return;
        HttpUrl url = endpoint == null ? null : HttpUrl.parse(endpoint);
        if (draft == null || url == null || !"https".equals(url.scheme())
                || !url.username().isEmpty() || !url.password().isEmpty()
                || url.query() != null || url.fragment() != null
                || !REPORT_PATH.equals(url.encodedPath())
                || DiagnosticReportContract.utf8Bytes(draft.json)
                > DiagnosticReportContract.CLIENT_BODY_BYTES) {
            callback.onResult(Result.failure(Kind.INVALID_REPORT));
            return;
        }
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .post(RequestBody.create(draft.json, JSON))
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException error) {
                callback.onResult(Result.failure(
                        error instanceof SocketTimeoutException || error instanceof InterruptedIOException
                                ? Kind.TIMEOUT : Kind.NETWORK));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response ignored = response) {
                    int code = response.code();
                    if (code == 200 || code == 201) {
                        String body = boundedBody(response.body());
                        Receipt receipt = parseReceipt(body, draft.reportId);
                        callback.onResult(receipt == null
                                ? Result.failure(Kind.INVALID_RESPONSE)
                                : Result.success(receipt, code == 200));
                        return;
                    }
                    callback.onResult(Result.failure(mapStatus(code)));
                } catch (Exception ignored) {
                    callback.onResult(Result.failure(Kind.INVALID_RESPONSE));
                }
            }
        });
    }

    static Kind mapStatus(int code) {
        if (code == 400) return Kind.INVALID_REPORT;
        if (code == 409) return Kind.REPORT_ID_COLLISION;
        if (code == 413) return Kind.REQUEST_TOO_LARGE;
        if (code == 429) return Kind.RATE_LIMITED;
        if (code == 503) return Kind.STORAGE_UNAVAILABLE;
        if (code >= 300 && code <= 399) return Kind.REDIRECT_REJECTED;
        if (code >= 500 && code <= 599) return Kind.SERVER_ERROR;
        return Kind.INVALID_RESPONSE;
    }

    private static String boundedBody(ResponseBody body) throws IOException {
        if (body == null || body.contentLength() > RESPONSE_LIMIT_BYTES) return "";
        try (InputStream input = body.byteStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            while (true) {
                int read = input.read(buffer);
                if (read < 0) break;
                total += read;
                if (total > RESPONSE_LIMIT_BYTES) return "";
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    static Receipt parseReceipt(String body, String expectedReportId) {
        try {
            JsonObject object = JsonParser.parseString(body).getAsJsonObject();
            String reportId = object.get("reportId").getAsString();
            String received = object.get("receivedAtUtc").getAsString();
            if (!object.has("rawExpiresAtUtc") || !object.get("rawExpiresAtUtc").isJsonNull()) {
                return null;
            }
            String retentionPolicy = object.get("retentionPolicy").getAsString();
            if (!expectedReportId.equals(reportId) || !DiagnosticReportContract.validReportId(reportId)
                    || received.isEmpty() || received.length() > 64
                    || !RETENTION_POLICY_INDEFINITE.equals(retentionPolicy)) return null;
            return new Receipt(reportId, received, null, retentionPolicy);
        } catch (Exception ignored) {
            return null;
        }
    }

    public interface ResultCallback {
        void onResult(Result result);
    }

    public enum Kind {
        INVALID_REPORT,
        REPORT_ID_COLLISION,
        REQUEST_TOO_LARGE,
        RATE_LIMITED,
        STORAGE_UNAVAILABLE,
        SERVER_ERROR,
        REDIRECT_REJECTED,
        TIMEOUT,
        NETWORK,
        INVALID_RESPONSE
    }

    public static final class Receipt {
        public final String reportId;
        public final String receivedAtUtc;
        public final String rawExpiresAtUtc;
        public final String retentionPolicy;

        Receipt(String reportId, String receivedAtUtc, String rawExpiresAtUtc,
                String retentionPolicy) {
            this.reportId = reportId;
            this.receivedAtUtc = receivedAtUtc;
            this.rawExpiresAtUtc = rawExpiresAtUtc;
            this.retentionPolicy = retentionPolicy;
        }
    }

    public static final class Result {
        public final Receipt receipt;
        public final boolean idempotentRetry;
        public final Kind failure;

        private Result(Receipt receipt, boolean idempotentRetry, Kind failure) {
            this.receipt = receipt;
            this.idempotentRetry = idempotentRetry;
            this.failure = failure;
        }

        static Result success(Receipt receipt, boolean idempotentRetry) {
            return new Result(receipt, idempotentRetry, null);
        }

        static Result failure(Kind kind) {
            return new Result(null, false, kind);
        }

        public boolean successful() {
            return receipt != null;
        }
    }
}
