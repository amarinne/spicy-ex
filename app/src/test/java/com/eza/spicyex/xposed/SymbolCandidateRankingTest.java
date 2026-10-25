package com.eza.spicyex.xposed;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class SymbolCandidateRankingTest {
    public static final class OrderFixture {
        public final Object alpha() { return null; }
        public final Object bravo() { return null; }
        public final native Object zulu();
    }

    public static final class StaticFixture {
        public static final Object value() { return null; }
    }

    public abstract static class AbstractFixture {
        public abstract Object value();
    }

    public static final class TieFixture {
        public final Object alpha() { return null; }
        public final Object bravo() { return null; }
    }

    public static final class EmptyFixture {
    }

    public static final class DuplicateFixture {
        public final Object value() { return null; }
    }

    @Test public void rankingIsIndependentOfInputOrder() throws Exception {
        Method alpha = OrderFixture.class.getDeclaredMethod("alpha");
        Method bravo = OrderFixture.class.getDeclaredMethod("bravo");
        Method zulu = OrderFixture.class.getDeclaredMethod("zulu");
        List<Method> expected = Arrays.asList(alpha, bravo, zulu);
        Method[][] orders = {
                {alpha, bravo, zulu},
                {zulu, bravo, alpha},
                {bravo, alpha, zulu}
        };
        for (Method[] order : orders) {
            SpotifySymbolResolver.Ranked ranked =
                    SpotifySymbolResolver.rankTrackCandidates(OrderFixture.class, Object.class, order);
            assertEquals(expected, ranked.ordered);
            assertEquals(3, ranked.topScore);
        }
    }

    @Test public void staticAndAbstractCandidatesAreIneligible() throws Exception {
        SpotifySymbolResolver.Ranked ranked =
                SpotifySymbolResolver.rankTrackCandidates(StaticFixture.class, Object.class);
        assertTrue(ranked.ordered.isEmpty());
        assertEquals(-1, ranked.topScore);
        try {
            SpotifySymbolResolver.findTrackMethod(StaticFixture.class, Object.class);
            fail();
        } catch (NoSuchMethodException expected) {
            assertTrue(expected.getMessage().contains(StaticFixture.class.getName()));
            assertTrue(expected.getMessage().contains(Object.class.getName()));
        }
        assertTrue(SpotifySymbolResolver.rankTrackCandidates(AbstractFixture.class, Object.class)
                .ordered.isEmpty());
    }

    @Test public void syntheticAndBridgeCandidatesRankBelowRealMethod() throws Exception {
        Class<?> fixture = new FixtureLoader().define(generatedFixtureBytes());
        Method real = fixture.getDeclaredMethod("real");
        Method nativeMethod = fixture.getDeclaredMethod("nativeMethod");
        Method bridge = fixture.getDeclaredMethod("bridge");
        Method synthetic = fixture.getDeclaredMethod("synthetic");
        SpotifySymbolResolver.Ranked ranked =
                SpotifySymbolResolver.rankTrackCandidates(fixture, Object.class);
        assertEquals(Arrays.asList(real, nativeMethod, bridge, synthetic), ranked.ordered);
        assertEquals(3, ranked.topScore);
        assertFalse(ranked.ambiguous);
    }

    @Test public void noEligibleCandidateNamesWrapperAndReturnType() {
        try {
            SpotifySymbolResolver.findTrackMethod(EmptyFixture.class, Object.class);
            fail();
        } catch (NoSuchMethodException expected) {
            assertTrue(expected.getMessage().contains(EmptyFixture.class.getName()));
            assertTrue(expected.getMessage().contains(Object.class.getName()));
        }
    }

    @Test public void ambiguityIsFlaggedAndPolicyAReturnsTheDeterministicWinner() throws Exception {
        Method alpha = TieFixture.class.getDeclaredMethod("alpha");
        Method bravo = TieFixture.class.getDeclaredMethod("bravo");
        SpotifySymbolResolver.Ranked ranked =
                SpotifySymbolResolver.rankTrackCandidates(TieFixture.class, Object.class);
        assertTrue(ranked.ambiguous);
        assertEquals(Arrays.asList(alpha, bravo), ranked.ordered);
        Method winner = SpotifySymbolResolver.selectTrackMethod(ranked, TieFixture.class, Object.class);
        assertEquals(alpha, winner);
        assertEquals(winner,
                SpotifySymbolResolver.selectTrackMethod(ranked, TieFixture.class, Object.class));
    }

    @Test public void duplicateDescriptorsCollapse() throws Exception {
        Method first = DuplicateFixture.class.getDeclaredMethod("value");
        Method second = DuplicateFixture.class.getDeclaredMethod("value");
        assertNotSame(first, second);
        SpotifySymbolResolver.Ranked ranked = SpotifySymbolResolver.rankTrackCandidates(
                DuplicateFixture.class, Object.class, new Method[]{first, second});
        assertEquals(1, ranked.ordered.size());
        assertEquals(first, ranked.ordered.get(0));
        assertFalse(ranked.ambiguous);
    }

    private static byte[] generatedFixtureBytes() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);
            out.writeShort(55);
            out.writeShort(11);
            out.writeByte(1);
            out.writeUTF("GeneratedAccessorFixture");
            out.writeByte(7);
            out.writeShort(1);
            out.writeByte(1);
            out.writeUTF("java/lang/Object");
            out.writeByte(7);
            out.writeShort(3);
            out.writeByte(1);
            out.writeUTF("real");
            out.writeByte(1);
            out.writeUTF("synthetic");
            out.writeByte(1);
            out.writeUTF("bridge");
            out.writeByte(1);
            out.writeUTF("nativeMethod");
            out.writeByte(1);
            out.writeUTF("()Ljava/lang/Object;");
            out.writeByte(1);
            out.writeUTF("Code");
            out.writeShort(0x0031);
            out.writeShort(2);
            out.writeShort(4);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(4);
            writeMethod(out, 0x0011, 5);
            writeMethod(out, 0x1011, 6);
            writeMethod(out, 0x1051, 7);
            out.writeShort(0x0111);
            out.writeShort(8);
            out.writeShort(9);
            out.writeShort(0);
            out.writeShort(0);
            return bytes.toByteArray();
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    private static void writeMethod(DataOutputStream out, int access, int name) throws IOException {
        out.writeShort(access);
        out.writeShort(name);
        out.writeShort(9);
        out.writeShort(1);
        out.writeShort(10);
        out.writeInt(14);
        out.writeShort(1);
        out.writeShort(1);
        out.writeInt(2);
        out.writeByte(0x01);
        out.writeByte(0xB0);
        out.writeShort(0);
        out.writeShort(0);
    }

    private static final class FixtureLoader extends ClassLoader {
        FixtureLoader() {
            super(SymbolCandidateRankingTest.class.getClassLoader());
        }

        Class<?> define(byte[] bytes) {
            return defineClass("GeneratedAccessorFixture", bytes, 0, bytes.length);
        }
    }
}
