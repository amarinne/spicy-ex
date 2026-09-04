package com.eza.spicyex.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * Project-owned hook adapter over the single LibXposed API 102 interceptor chain.
 *
 * <p>Preserved legacy semantics:
 * <ul>
 *   <li>before-callback early return ({@link XpParam#setResult}) still runs after-callbacks;</li>
 *   <li>after-callbacks run even when the original throws, and can suppress the
 *       throwable by setting a result;</li>
 *   <li>every hook installs with {@code PROTECTIVE} exception mode: a throwing
 *       callback logs and the chain continues as if the hook were absent;</li>
 *   <li>lookups stay exact-match: a method name without parameter types resolves
 *       one no-arg method, never all overloads.</li>
 * </ul>
 *
 * <p>Stable hook ids ({@code feature + executable}) back atomic replacement on
 * hot reload; every handle joins a generation-owned registry for cleanup.
 */
public final class XpHooks {
    /** Before-callback: observe or mutate args, or {@link XpParam#setResult} to skip the original. */
    public interface Before {
        void before(XpParam param) throws Throwable;
    }

    /** After-callback: observe or replace the result, or suppress a throwable via {@link XpParam#setResult}. */
    public interface After {
        void after(XpParam param) throws Throwable;
    }

    /** Mutable hook parameters mirroring the legacy {@code MethodHookParam} surface. */
    public static final class XpParam {
        /** Receiver of the call; re-read after constructor proceed. Null for static methods. */
        public Object thisObject;
        /** Mutable argument array; mutations are passed to the original on proceed. */
        public Object[] args;
        /** The hooked method or constructor. */
        public final Executable executable;
        private Object result;
        private boolean hasResult;
        private Throwable throwable;

        XpParam(Object thisObject, Object[] args, Executable executable) {
            this.thisObject = thisObject;
            this.args = args == null ? new Object[0] : args;
            this.executable = executable;
        }

        /** Early return (before) or result replacement / throwable suppression (after). */
        public void setResult(Object result) {
            this.result = result;
            this.hasResult = true;
            this.throwable = null;
        }

        public Object getResult() {
            return result;
        }

        public boolean hasResult() {
            return hasResult;
        }

        /** Throwable thrown by the original; null on the early-return path. */
        public Throwable getThrowable() {
            return throwable;
        }
    }

    private static volatile XposedInterface api;
    private static final List<XposedInterface.HookHandle> REGISTRY =
            Collections.synchronizedList(new ArrayList<>());

    private XpHooks() {
    }

    public static void attach(XposedInterface xposed) {
        api = xposed;
    }

    static XposedInterface api() {
        XposedInterface current = api;
        if (current == null) throw new IllegalStateException("XpHooks not attached");
        return current;
    }

    /** Generation-owned handles for hot-reload cleanup. */
    public static void unhookAll() {
        List<XposedInterface.HookHandle> snapshot;
        synchronized (REGISTRY) {
            snapshot = new ArrayList<>(REGISTRY);
            REGISTRY.clear();
        }
        for (XposedInterface.HookHandle handle : snapshot) {
            try {
                handle.unhook();
            } catch (Throwable ignored) {
            }
        }
    }

    // Core.

    public static XposedInterface.HookHandle hook(Executable executable, String id, Before before, After after) {
        XposedInterface.Hooker hooker = chain -> {
            XpParam param = new XpParam(
                    chain.getThisObject(), chain.getArgs().toArray(new Object[0]), chain.getExecutable());
            if (before != null) before.before(param);
            if (!param.hasResult()) {
                Object result = null;
                try {
                    result = chain.proceed(param.args);
                } catch (Throwable throwable) {
                    param.throwable = throwable;
                }
                if (param.executable instanceof Constructor<?>) {
                    try {
                        param.thisObject = chain.getThisObject();
                    } catch (Throwable ignored) {
                    }
                }
                if (param.throwable == null) {
                    param.result = result;
                    param.hasResult = true;
                }
            }
            if (after != null) after.after(param);
            if (param.throwable != null && !param.hasResult()) throw param.throwable;
            return param.hasResult() ? param.getResult() : null;
        };
        XposedInterface.HookHandle handle = api()
                .hook(executable)
                .setId(id)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
        REGISTRY.add(handle);
        return handle;
    }

    // Before / after shorthand, with and without stable ids.

    public static XposedInterface.HookHandle hookBefore(Executable executable, Before before) {
        return hook(executable, null, before, null);
    }

    public static XposedInterface.HookHandle hookBefore(Executable executable, String id, Before before) {
        return hook(executable, id, before, null);
    }

    public static XposedInterface.HookHandle hookAfter(Executable executable, After after) {
        return hook(executable, null, null, after);
    }

    public static XposedInterface.HookHandle hookAfter(Executable executable, String id, After after) {
        return hook(executable, id, null, after);
    }

    // Exact-match find-and-hook on a Class, with and without stable ids.

    public static XposedInterface.HookHandle findBefore(
            Class<?> clazz, String methodName, Before before, Class<?>... paramTypes) {
        return hook(XpReflect.findMethodExact(clazz, methodName, paramTypes), null, before, null);
    }

    public static XposedInterface.HookHandle findBefore(
            Class<?> clazz, String methodName, String id, Before before, Class<?>... paramTypes) {
        return hook(XpReflect.findMethodExact(clazz, methodName, paramTypes), id, before, null);
    }

    public static XposedInterface.HookHandle findAfter(
            Class<?> clazz, String methodName, After after, Class<?>... paramTypes) {
        return hook(XpReflect.findMethodExact(clazz, methodName, paramTypes), null, null, after);
    }

    public static XposedInterface.HookHandle findAfter(
            Class<?> clazz, String methodName, String id, After after, Class<?>... paramTypes) {
        return hook(XpReflect.findMethodExact(clazz, methodName, paramTypes), id, null, after);
    }

    // Exact-match find-and-hook on a class name plus loader, with and without stable ids.

    public static XposedInterface.HookHandle findBefore(
            String className, ClassLoader loader, String methodName, Before before, Class<?>... paramTypes) {
        return findBefore(XpReflect.findClass(className, loader), methodName, before, paramTypes);
    }

    public static XposedInterface.HookHandle findBefore(
            String className, ClassLoader loader, String methodName, String id,
            Before before, Class<?>... paramTypes) {
        return findBefore(XpReflect.findClass(className, loader), methodName, id, before, paramTypes);
    }

    public static XposedInterface.HookHandle findAfter(
            String className, ClassLoader loader, String methodName, After after, Class<?>... paramTypes) {
        return findAfter(XpReflect.findClass(className, loader), methodName, after, paramTypes);
    }

    public static XposedInterface.HookHandle findAfter(
            String className, ClassLoader loader, String methodName, String id,
            After after, Class<?>... paramTypes) {
        return findAfter(XpReflect.findClass(className, loader), methodName, id, after, paramTypes);
    }

    // All-constructors and all-methods, before and after, with and without stable ids.

    public static void hookAllConstructors(Class<?> clazz, After after) {
        hookAllConstructors(clazz, null, null, after);
    }

    public static void hookAllConstructors(Class<?> clazz, String id, After after) {
        hookAllConstructors(clazz, id, null, after);
    }

    public static void hookAllConstructors(Class<?> clazz, Before before) {
        hookAllConstructors(clazz, null, before, null);
    }

    public static void hookAllConstructors(Class<?> clazz, String id, Before before) {
        hookAllConstructors(clazz, id, before, null);
    }

    private static void hookAllConstructors(Class<?> clazz, String id, Before before, After after) {
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            try {
                constructor.setAccessible(true);
                hook(constructor, id, before, after);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void hookAllMethods(Class<?> clazz, String methodName, After after) {
        hookAllMethods(clazz, methodName, null, null, after);
    }

    public static void hookAllMethods(Class<?> clazz, String methodName, String id, After after) {
        hookAllMethods(clazz, methodName, id, null, after);
    }

    public static void hookAllMethods(Class<?> clazz, String methodName, Before before) {
        hookAllMethods(clazz, methodName, null, before, null);
    }

    public static void hookAllMethods(Class<?> clazz, String methodName, String id, Before before) {
        hookAllMethods(clazz, methodName, id, before, null);
    }

    private static void hookAllMethods(Class<?> clazz, String methodName, String id, Before before, After after) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.getName().equals(methodName)) continue;
            try {
                method.setAccessible(true);
                hook(method, id, before, after);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Stable id helper: {@code feature + executable}. */
    public static String id(String feature, Executable executable) {
        return feature + ":" + executable.toString();
    }

    /** Stable id helper: {@code feature + class + method}. */
    public static String id(String feature, Class<?> clazz, String methodName) {
        return feature + ":" + clazz.getName() + "#" + methodName;
    }
}
