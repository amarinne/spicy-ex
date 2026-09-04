package com.eza.spicyex.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Unchecked reflection facade covering only the legacy helper subset
 * this module actually uses: class lookup, field access, best-match invocation.
 *
 * <p>Legacy helpers throw unchecked, so this facade throws unchecked too —
 * otherwise every call site would need a {@code throws} audit.
 */
public final class XpReflect {
    private XpReflect() {
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            XpReflect.<RuntimeException>sneakyThrow(new NoClassDefFoundError(className + ": " + e));
            throw new AssertionError("unreachable");
        }
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    public static Object callMethod(Object receiver, String methodName, Object... args) {
        if (receiver == null) throw new NullPointerException("receiver == null for " + methodName);
        Method best = findBestMatch(receiver.getClass(), methodName, false, argTypes(args));
        try {
            best.setAccessible(true);
            return best.invoke(receiver, args);
        } catch (Throwable e) {
            XpReflect.<RuntimeException>sneakyThrow(e);
            throw new AssertionError("unreachable");
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        Method best = findBestMatch(clazz, methodName, true, argTypes(args));
        try {
            best.setAccessible(true);
            return best.invoke(null, args);
        } catch (Throwable e) {
            XpReflect.<RuntimeException>sneakyThrow(e);
            throw new AssertionError("unreachable");
        }
    }

    public static Object getObjectField(Object receiver, String fieldName) {
        try {
            return findField(receiver.getClass(), fieldName).get(receiver);
        } catch (Throwable e) {
            XpReflect.<RuntimeException>sneakyThrow(e);
            throw new AssertionError("unreachable");
        }
    }

    public static void setObjectField(Object receiver, String fieldName, Object value) {
        try {
            findField(receiver.getClass(), fieldName).set(receiver, value);
        } catch (Throwable e) {
            XpReflect.<RuntimeException>sneakyThrow(e);
            throw new AssertionError("unreachable");
        }
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        try {
            Method method = clazz.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            XpReflect.<RuntimeException>sneakyThrow(new NoSuchMethodError(
                    clazz.getName() + "#" + methodName + ": " + e));
            throw new AssertionError("unreachable");
        }
    }

    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(clazz.getName() + "#" + fieldName);
    }

    private static Class<?>[] argTypes(Object[] args) {
        if (args == null) return new Class<?>[0];
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] == null ? null : args[i].getClass();
        }
        return types;
    }

    private static Method findBestMatch(Class<?> clazz, String name, boolean staticOnly, Class<?>[] argTypes) {
        Method exact = null;
        Method best = null;
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                if (staticOnly != Modifier.isStatic(method.getModifiers())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != argTypes.length) continue;
                if (isExact(params, argTypes)) {
                    if (exact == null) exact = method;
                    continue;
                }
                if (isAssignable(params, argTypes) && best == null) best = method;
            }
            current = current.getSuperclass();
        }
        // Interfaces and Object-declared fallbacks (e.g. toString-shaped probes).
        if (exact == null && best == null) {
            for (Method method : clazz.getMethods()) {
                if (!method.getName().equals(name)) continue;
                if (staticOnly != Modifier.isStatic(method.getModifiers())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != argTypes.length) continue;
                if (isExact(params, argTypes)) {
                    exact = method;
                    break;
                }
                if (isAssignable(params, argTypes) && best == null) best = method;
            }
        }
        Method winner = exact != null ? exact : best;
        if (winner == null) {
            throw new NoSuchMethodError(clazz.getName() + "#" + name
                    + " staticOnly=" + staticOnly + " args=" + argTypes.length);
        }
        return winner;
    }

    private static boolean isExact(Class<?>[] params, Class<?>[] actual) {
        for (int i = 0; i < params.length; i++) {
            if (actual[i] == null || !params[i].equals(actual[i])) return false;
        }
        return true;
    }

    private static boolean isAssignable(Class<?>[] params, Class<?>[] actual) {
        for (int i = 0; i < params.length; i++) {
            if (actual[i] == null) {
                if (params[i].isPrimitive()) return false;
                continue;
            }
            if (!wrap(params[i]).isAssignableFrom(wrap(actual[i]))) return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return Void.class;
    }

    /** Exact-match method lookup that mirrors the legacy no-param-types failure mode. */
    public static Method findMethodNoArgs(Class<?> clazz, String methodName) {
        return findMethodExact(clazz, methodName);
    }

    public static Constructor<?> findConstructorExact(Class<?> clazz, Class<?>... parameterTypes) {
        try {
            Constructor<?> constructor = clazz.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException e) {
            XpReflect.<RuntimeException>sneakyThrow(new NoSuchMethodError(
                    clazz.getName() + "#<init>: " + e));
            throw new AssertionError("unreachable");
        }
    }

    static String describe(Member member) {
        return member == null ? "null" : member.toString();
    }
}
