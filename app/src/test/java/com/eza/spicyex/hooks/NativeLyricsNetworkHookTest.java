package com.eza.spicyex.hooks;

import static org.junit.Assert.*;

import com.eza.spicyex.lyrics.providers.NativeLyricsSource;
import com.eza.spicyex.xposed.XpHooks;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Runs the installed response callbacks against real OkHttp bodies and request pairing. */
public class NativeLyricsNetworkHookTest {
    private static final String TRACK_A = "aaaaaaaaaaaaaaaaaaaaaa";
    private static final String TRACK_B = "bbbbbbbbbbbbbbbbbbbbbb";
    private final Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
    private final Map<String, Method> methods = new HashMap<>();
    private final List<ResponseBody> bodies = new ArrayList<>();
    private final AtomicReference<String> capturedTrack = new AtomicReference<>();
    private NativeLyricsSource source;

    @Before public void install() {
        XpHooks.unhookAll();
        XpHooks.attach((XposedInterface) proxy(XposedInterface.class, (p, m, args) -> {
            if (!"hook".equals(m.getName())) throw new AssertionError(m);
            Method executable = (Method) args[0];
            return proxy(XposedInterface.HookBuilder.class, (builder, operation, values) -> {
                if ("intercept".equals(operation.getName())) {
                    if (executable.getDeclaringClass() == ResponseBody.class) {
                        hooks.put(executable.getName(), (XposedInterface.Hooker) values[0]);
                        methods.put(executable.getName(), executable);
                    }
                    return proxy(XposedInterface.HookHandle.class, (handle, action, ignored) -> null);
                }
                return builder;
            });
        }));
        source = new NativeLyricsSource(null, null);
        source.addNativeListener((id, document) -> capturedTrack.set(id));
        NativeLyricsNetworkHook.install(getClass().getClassLoader(), source, null);
    }

    @After public void cleanup() throws Exception {
        for (ResponseBody body : bodies) {
            pending().remove(body);
            body.close();
        }
        XpHooks.unhookAll();
        XpHooks.attach(null);
        NativeLyricsNetworkHook.bind(null);
    }

    @Test public void closingAnUnreadBodyReleasesOnlyItsOwnPairing() throws Throwable {
        ResponseBody first = pairedBody(TRACK_A);
        ResponseBody second = pairedBody(TRACK_B);
        call("close", first, null);
        assertFalse(pending().containsKey(first));
        assertEquals(TRACK_B, pending().get(second));
        assertNull(capturedTrack.get());
        call("close", first, null);
        assertEquals(TRACK_B, pending().get(second));
    }

    @Test public void readingStillCapturesTheRequestTrackAndConsumesItsPairing() throws Throwable {
        ResponseBody body = pairedBody(TRACK_A);
        assertTrue(((String) call("string", body, null)).contains("Hello"));
        assertEquals(TRACK_A, capturedTrack.get());
        assertFalse(pending().containsKey(body));
        call("close", body, null);
        assertEquals(TRACK_A, capturedTrack.get());
    }

    @Test public void failedCloseStillReleasesPairingAndPreservesTheHostError() throws Throwable {
        ResponseBody body = pairedBody(TRACK_A);
        IllegalStateException error = new IllegalStateException("fixture close failure");
        try {
            call("close", body, error);
            fail("host error must escape");
        } catch (IllegalStateException actual) {
            assertSame(error, actual);
        }
        assertFalse(pending().containsKey(body));
        assertNull(capturedTrack.get());
    }

    private ResponseBody pairedBody(String id) throws Exception {
        ResponseBody body = ResponseBody.create(MediaType.get("application/json"),
                "{\"lines\":[{\"words\":\"Hello\",\"startTimeInMs\":1000}]}");
        bodies.add(body);
        Request request = new Request.Builder().url(
                "https://example.test/color-lyrics/v2/track/" + id).build();
        Response response = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build();
        Method pairing = NativeLyricsNetworkHook.class.getDeclaredMethod("pairRequestWithBody",
                Object[].class, Object.class, NativeLyricsSource.class);
        pairing.setAccessible(true);
        pairing.invoke(null, new Object[]{request}, response, source);
        assertEquals(id, pending().get(body));
        return body;
    }

    private Object call(String name, ResponseBody body, Throwable error) throws Throwable {
        XposedInterface.Hooker hook = hooks.get(name);
        assertNotNull("response hook must be installed: " + name, hook);
        Method executable = methods.get(name);
        return hook.intercept((XposedInterface.Chain) proxy(XposedInterface.Chain.class,
                (p, operation, args) -> {
                    switch (operation.getName()) {
                        case "getExecutable": return executable;
                        case "getThisObject": return body;
                        case "getArgs": return Collections.emptyList();
                        case "proceed":
                            if (error != null) throw error;
                            try { return executable.invoke(body); }
                            catch (InvocationTargetException failure) { throw failure.getCause(); }
                        default: throw new AssertionError(operation);
                    }
                }));
    }

    private Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type}, handler);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, String> pending() throws Exception {
        Field field = NativeLyricsNetworkHook.class.getDeclaredField("PENDING");
        field.setAccessible(true);
        return (Map<Object, String>) field.get(null);
    }
}
