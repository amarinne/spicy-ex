package com.eza.spicyex.auto;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoWeatherSizeObserverRegressionTest {
    public interface Function { Object invoke(Object value) throws Exception; }
    public static final class Element {
        private final Function callback;
        Element(Function callback) { this.callback = callback; }
    }
    /** Mirrors Modifier.all traversal and then concatenation, including nested chains. */
    public static final class Modifier {
        final List<Object> elements;
        Modifier(Object... elements) { this.elements = Arrays.asList(elements); }
        public boolean all(Function predicate) throws Exception {
            for (Object element : elements) {
                if (element instanceof Modifier) {
                    if (!((Modifier) element).all(predicate)) return false;
                } else if (!(Boolean) predicate.invoke(element)) return false;
            }
            return true;
        }
    }
    public static Modifier append(Modifier modifier, Function observer) {
        return new Modifier(modifier, new Element(observer));
    }

    @Test public void nativeRestartUsesItsCapturedModifierWithoutAddingAnotherObserver() throws Exception {
        AutoWeatherSizeObserver attachment = attachment();
        Function observer = value -> null;
        Function predicate = value -> attachment.isOtherElement(value, observer);
        Object captured = attachment.attach(new Modifier("native padding"), observer, predicate);
        assertSame(captured, attachment.attach(captured, observer, predicate));
        assertSame(captured, attachment.attach(captured, observer, predicate));
        assertEquals(1, count((Modifier) captured, observer));
    }

    @Test public void hostModifiersAroundCapturedChainStillContainOurObserver() throws Exception {
        AutoWeatherSizeObserver attachment = attachment();
        Function observer = value -> null;
        Function predicate = value -> attachment.isOtherElement(value, observer);
        Modifier captured = (Modifier) attachment.attach(new Modifier("native"), observer, predicate);
        Modifier wrapped = new Modifier("host prefix", captured, "host suffix");
        assertSame(wrapped, attachment.attach(wrapped, observer, predicate));
        assertEquals(1, count(wrapped, observer));
    }

    @Test public void anotherSizeObserverCannotPreventOursOrGetRemoved() throws Exception {
        AutoWeatherSizeObserver attachment = attachment();
        Function nativeObserver = value -> null;
        Function observer = value -> null;
        Function predicate = value -> attachment.isOtherElement(value, observer);
        Modifier initial = new Modifier(new Element(nativeObserver));
        Modifier result = (Modifier) attachment.attach(initial, observer, predicate);
        assertEquals(1, count(result, nativeObserver));
        assertEquals(1, count(result, observer));
        assertSame(result, attachment.attach(result, observer, predicate));
    }

    private static AutoWeatherSizeObserver attachment() throws Exception {
        Field callback = Element.class.getDeclaredField("callback"); callback.setAccessible(true);
        return new AutoWeatherSizeObserver(Modifier.class.getMethod("all", Function.class),
                AutoWeatherSizeObserverRegressionTest.class.getMethod("append", Modifier.class, Function.class), callback);
    }
    private static int count(Modifier modifier, Object callback) {
        int count = 0;
        for (Object element : modifier.elements) {
            if (element instanceof Modifier) count += count((Modifier) element, callback);
            else if (element instanceof Element && ((Element) element).callback == callback) count++;
        }
        return count;
    }
}
