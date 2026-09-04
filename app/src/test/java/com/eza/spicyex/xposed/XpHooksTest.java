package com.eza.spicyex.xposed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Executable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedInterface;

/** Interceptor-chain semantics: early return, throwable suppression, exact match, registry. */
public class XpHooksTest {
    static class Fixture {
        String greet(String name) {
            return "hi " + name;
        }

        String greet() {
            return "none";
        }

        String greet(Object name) {
            return "obj";
        }
    }

    /** Scripted Chain fake: records proceed args, returns a result or throws. */
    static final class Script {
        final Executable executable;
        final Object thisObject;
        final List<Object> args;
        Object postProceedThisObject;
        Object result;
        Throwable error;
        boolean proceeded;
        Object[] proceedArgs;

        Script(Executable executable, Object thisObject, Object... args) {
            this.executable = executable;
            this.thisObject = thisObject;
            this.args = new ArrayList<>(Arrays.asList(args));
            this.postProceedThisObject = thisObject;
        }
    }

    static XposedInterface.Hooker captureHooker(List<XposedInterface.Hooker> captured) {
        XpHooks.unhookAll();
        XpHooks.attach((XposedInterface) Proxy.newProxyInstance(
                XpHooksTest.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hook")) {
                        return Proxy.newProxyInstance(
                                XpHooksTest.class.getClassLoader(),
                                new Class<?>[]{XposedInterface.HookBuilder.class},
                                (builder, builderMethod, builderArgs) -> {
                                    switch (builderMethod.getName()) {
                                        case "setPriority":
                                        case "setExceptionMode":
                                        case "setId":
                                            return builder;
                                        case "intercept":
                                            captured.add((XposedInterface.Hooker) builderArgs[0]);
                                            return Proxy.newProxyInstance(
                                                    XpHooksTest.class.getClassLoader(),
                                                    new Class<?>[]{XposedInterface.HookHandle.class},
                                                    new RecordingHandle());
                                        default:
                                            throw new UnsupportedOperationException(
                                                    builderMethod.getName());
                                    }
                                });
                    }
                    if (method.getName().equals("getApiVersion")) return 102;
                    throw new UnsupportedOperationException(method.getName());
                }));
        return null;
    }

    static final class RecordingHandle implements InvocationHandler {
        boolean unhooked;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "unhook":
                    unhooked = true;
                    return null;
                case "getId":
                    return "test-id";
                case "getExecutable":
                    return null;
                case "replaceHook":
                    return proxy;
                case "toString":
                    return "RecordingHandle";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        }
    }

    static XposedInterface.Chain chainFor(Script script) {
        return (XposedInterface.Chain) Proxy.newProxyInstance(
                XpHooksTest.class.getClassLoader(),
                new Class<?>[]{XposedInterface.Chain.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getExecutable":
                            return script.executable;
                        case "getThisObject":
                            return script.proceeded ? script.postProceedThisObject : script.thisObject;
                        case "getArgs":
                            return new ArrayList<>(script.args);
                        case "getArg":
                            return script.args.get((Integer) args[0]);
                        case "proceed":
                            script.proceeded = true;
                            script.proceedArgs = args == null || args.length == 0
                                    ? script.args.toArray()
                                    : (Object[]) args[0];
                            if (script.error != null) throw script.error;
                            return script.result;
                        case "proceedWith":
                            script.proceeded = true;
                            return script.result;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    private final List<XposedInterface.Hooker> captured = new ArrayList<>();
    private Method greetMethod;
    private Fixture fixture;

    @Before
    public void setUp() throws Exception {
        captureHooker(captured);
        fixture = new Fixture();
        greetMethod = Fixture.class.getDeclaredMethod("greet", String.class);
    }

    private Object intercept(Script script) throws Throwable {
        assertEquals(1, captured.size());
        return captured.get(0).intercept(chainFor(script));
    }

    @Test
    public void beforeAndAfterObserveResult() throws Throwable {
        List<String> events = new ArrayList<>();
        XpHooks.hook(greetMethod, "test:greet",
                param -> events.add("before:" + param.args[0]),
                param -> events.add("after:" + param.getResult()));
        Script script = new Script(greetMethod, fixture, "bob");
        script.result = "hi bob";

        assertEquals("hi bob", intercept(script));
        assertEquals(Arrays.asList("before:bob", "after:hi bob"), events);
        assertTrue(script.proceeded);
    }

    @Test
    public void earlyReturnSkipsOriginalButRunsAfter() throws Throwable {
        AtomicBoolean afterRan = new AtomicBoolean(false);
        XpHooks.hook(greetMethod, "test:greet",
                param -> param.setResult("early"),
                param -> {
                    afterRan.set(true);
                    assertEquals("early", param.getResult());
                });
        Script script = new Script(greetMethod, fixture, "bob");
        script.result = "hi bob";

        assertEquals("early", intercept(script));
        assertFalse(script.proceeded);
        assertTrue(afterRan.get());
    }

    @Test
    public void afterCanSuppressOriginalThrowable() throws Throwable {
        XpHooks.hook(greetMethod, "test:greet", null,
                param -> {
                    assertTrue(param.getThrowable() instanceof IllegalStateException);
                    param.setResult("suppressed");
                });
        Script script = new Script(greetMethod, fixture, "bob");
        script.error = new IllegalStateException("boom");

        assertEquals("suppressed", intercept(script));
    }

    @Test
    public void unsuppressedThrowableRethrows() throws Throwable {
        XpHooks.hookAfter(greetMethod, "test:greet", param -> {
        });
        Script script = new Script(greetMethod, fixture, "bob");
        script.error = new IllegalStateException("boom");

        try {
            intercept(script);
            fail("expected rethrow");
        } catch (IllegalStateException expected) {
            assertEquals("boom", expected.getMessage());
        }
    }

    @Test
    public void beforeArgMutationReachesOriginal() throws Throwable {
        XpHooks.hookBefore(greetMethod, "test:greet", param -> param.args[0] = "alice");
        Script script = new Script(greetMethod, fixture, "bob");
        script.result = "hi alice";

        assertEquals("hi alice", intercept(script));
        assertEquals("alice", script.proceedArgs[0]);
    }

    @Test
    public void constructorRefreshesThisObjectAfterProceed() throws Throwable {
        java.lang.reflect.Constructor<Fixture> ctor = Fixture.class.getDeclaredConstructor();
        Object fresh = new Fixture();
        AtomicReference<Object> seen = new AtomicReference<>();
        XpHooks.hookAfter(ctor, "test:ctor", param -> seen.set(param.thisObject));
        Script script = new Script(ctor, null);
        script.postProceedThisObject = fresh;

        intercept(script);
        assertSame(fresh, seen.get());
    }

    @Test
    public void exactMatchResolvesSingleNoArgOverload() throws Throwable {
        AtomicReference<Object> seen = new AtomicReference<>("unset");
        XpHooks.findAfter(Fixture.class, "greet", "test:greet0", param -> seen.set(param.getResult()));
        Script script = new Script(Fixture.class.getDeclaredMethod("greet"), fixture);
        script.result = "none";

        assertEquals("none", intercept(script));
        assertEquals("none", seen.get());
    }

    @Test
    public void missingMethodThrowsUnchecked() {
        try {
            XpHooks.findAfter(Fixture.class, "missing", "test:missing", param -> {
            });
            fail("expected NoSuchMethodError");
        } catch (NoSuchMethodError expected) {
        }
    }

    @Test
    public void unhookAllReleasesGenerationHandles() {
        List<RecordingHandle> recorders = new ArrayList<>();
        InvocationHandler recorderFactory = (proxy, method, methodArgs) -> {
            if (method.getName().equals("intercept")) {
                RecordingHandle recorder = new RecordingHandle();
                recorders.add(recorder);
                return Proxy.newProxyInstance(
                        XpHooksTest.class.getClassLoader(),
                        new Class<?>[]{XposedInterface.HookHandle.class}, recorder);
            }
            if (method.getName().equals("setPriority")
                    || method.getName().equals("setExceptionMode")
                    || method.getName().equals("setId")) {
                return proxy;
            }
            throw new UnsupportedOperationException(method.getName());
        };
        XpHooks.attach((XposedInterface) Proxy.newProxyInstance(
                XpHooksTest.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class},
                (proxy, method, methodArgs) -> {
                    if (method.getName().equals("hook")) {
                        return Proxy.newProxyInstance(
                                XpHooksTest.class.getClassLoader(),
                                new Class<?>[]{XposedInterface.HookBuilder.class}, recorderFactory);
                    }
                    throw new UnsupportedOperationException(method.getName());
                }));
        XpHooks.hookAfter(greetMethod, "a", param -> {
        });
        XpHooks.hookBefore(greetMethod, "b", param -> {
        });
        assertEquals(2, recorders.size());

        XpHooks.unhookAll();

        assertTrue(recorders.get(0).unhooked);
        assertTrue(recorders.get(1).unhooked);
        assertNull(getRegistrySize());
    }

    private static Object getRegistrySize() {
        try {
            java.lang.reflect.Field field = XpHooks.class.getDeclaredField("REGISTRY");
            field.setAccessible(true);
            List<?> registry = (List<?>) field.get(null);
            return registry.isEmpty() ? null : registry.size();
        } catch (Throwable throwable) {
            throw new AssertionError(throwable);
        }
    }
}
