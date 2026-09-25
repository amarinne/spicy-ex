package com.eza.spicyex.lyrics.ai;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/**
 * Records and answers the AI layer's paid requests from a JVM test.
 *
 * <p>The client the layer builds is replaced, not rebuilt, so timeouts and call behaviour stay
 * the device's; only the last interceptor is added, and it answers before any socket is opened.
 * The original client is put back in {@link #restore()} so a later test sees production wiring.
 */
public final class AiHttpTestControl {

    /** One request as the provider would have received it. */
    public static final class Recorded {
        public final String url;
        public final String body;

        Recorded(String url, String body) {
            this.url = url;
            this.body = body == null ? "" : body;
        }
    }

    private static final Field CLIENT = clientField();
    private static OkHttpClient original;
    private static final List<Recorded> requests = new ArrayList<>();

    private AiHttpTestControl() {
    }

    private static Field clientField() {
        try {
            Field field = AiHttp.class.getDeclaredField("client");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException missing) {
            throw new AssertionError("AiHttp client field moved", missing);
        }
    }

    /** Replaces the layer's client with one that answers {@code status} without any network. */
    public static synchronized void install(final int status) throws Exception {
        if (original == null) original = AiHttp.client();
        requests.clear();
        OkHttpClient intercepted = original.newBuilder().addInterceptor(new Interceptor() {
            @Override public Response intercept(Chain chain) throws IOException {
                Request request = chain.request();
                Buffer buffer = new Buffer();
                if (request.body() != null) request.body().writeTo(buffer);
                synchronized (requests) {
                    requests.add(new Recorded(request.url().toString(), buffer.readUtf8()));
                }
                return new Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(status)
                        .message(status == 401 ? "Unauthorized" : "Error")
                        .body(ResponseBody.create(
                                "{\"error\":{\"message\":\"test refusal\"}}",
                                MediaType.get("application/json")))
                        .build();
            }
        }).build();
        CLIENT.set(null, intercepted);
    }

    /** Puts the production client back and forgets every recorded request. */
    public static synchronized void restore() throws Exception {
        requests.clear();
        if (original != null) CLIENT.set(null, original);
        original = null;
    }

    public static List<Recorded> recorded() {
        synchronized (requests) {
            return new ArrayList<>(requests);
        }
    }

    public static int count() {
        synchronized (requests) {
            return requests.size();
        }
    }

    public static void clear() {
        synchronized (requests) {
            requests.clear();
        }
    }
}
