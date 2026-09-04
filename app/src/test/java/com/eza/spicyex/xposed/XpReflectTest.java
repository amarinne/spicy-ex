package com.eza.spicyex.xposed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.lang.reflect.Method;

/** Best-match and unchecked-discipline coverage for the reflect facade. */
public class XpReflectTest {
    static class Fixture {
        String value = "initial";

        String greet(String name) {
            return "hi " + name;
        }

        String greet(Object name) {
            return "obj " + name;
        }

        String noArgs() {
            return "none";
        }

        static String concat(String left, String right) {
            return left + right;
        }

        int add(int left, int right) {
            return left + right;
        }

        String nullable(String name) {
            return name == null ? "null" : name;
        }
    }

    static class Child extends Fixture {
    }

    @Test
    public void exactOverloadWinsOverAssignable() {
        Fixture fixture = new Fixture();
        assertEquals("hi bob", XpReflect.callMethod(fixture, "greet", "bob"));
    }

    @Test
    public void primitiveArgsMatchPrimitiveParams() {
        assertEquals(7, XpReflect.callMethod(new Fixture(), "add", 3, 4));
    }

    @Test
    public void nullArgMatchesReferenceParam() {
        assertEquals("null", XpReflect.callMethod(new Fixture(), "nullable", (Object) null));
    }

    @Test
    public void inheritedMethodResolves() {
        assertEquals("none", XpReflect.callMethod(new Child(), "noArgs"));
    }

    @Test
    public void staticBestMatchResolves() {
        assertEquals("ab", XpReflect.callStaticMethod(Fixture.class, "concat", "a", "b"));
    }

    @Test
    public void fieldAccessWalksHierarchy() {
        Child child = new Child();
        assertEquals("initial", XpReflect.getObjectField(child, "value"));
        XpReflect.setObjectField(child, "value", "changed");
        assertEquals("changed", XpReflect.getObjectField(child, "value"));
    }

    @Test
    public void findClassLoadsWithGivenLoader() {
        assertSame(Fixture.class,
                XpReflect.findClass(Fixture.class.getName(), Fixture.class.getClassLoader()));
    }

    @Test
    public void findClassIfExistsReturnsNull() {
        assertNull(XpReflect.findClassIfExists("com.eza.spicyex.xposed.NoSuchClass",
                Fixture.class.getClassLoader()));
    }

    @Test
    public void missingMembersThrowUnchecked() {
        try {
            XpReflect.callMethod(new Fixture(), "missing");
            fail("expected NoSuchMethodError");
        } catch (NoSuchMethodError expected) {
        }
        try {
            XpReflect.getObjectField(new Fixture(), "missing");
            fail("expected NoSuchFieldException");
        } catch (Exception expected) {
            assertTrue(expected instanceof NoSuchFieldException);
        }
        try {
            XpReflect.findClass("com.eza.spicyex.xposed.NoSuchClass",
                    Fixture.class.getClassLoader());
            fail("expected NoClassDefFoundError");
        } catch (NoClassDefFoundError expected) {
        }
    }

    @Test
    public void exactLookupFindsNoArgOverload() {
        Method method = XpReflect.findMethodExact(Fixture.class, "noArgs");
        assertEquals(0, method.getParameterTypes().length);
    }
}
