package com.eza.spicyex.auto;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Keeps one module size observer when native Compose restart closures retain their modifier. */
final class AutoWeatherSizeObserver {
    private final Method allElements, append;
    private final Field callback;

    AutoWeatherSizeObserver(Method allElements, Method append, Field callback) {
        this.allElements = allElements;
        this.append = append;
        this.callback = callback;
    }

    boolean isOtherElement(Object element, Object observer) throws IllegalAccessException {
        return !callback.getDeclaringClass().isInstance(element) || callback.get(element) != observer;
    }

    Object attach(Object modifier, Object observer, Object predicate) throws ReflectiveOperationException {
        return (Boolean) allElements.invoke(modifier, predicate)
                ? append.invoke(null, modifier, observer) : modifier;
    }
}
