package com.eza.spicyex.lyrics.providers;

import com.eza.spicyex.lyrics.LyricResponseReader;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.zip.DeflaterOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class F5QrcExpansionTest {
    private byte[] inflate(byte[] compressed) throws Exception {
        Method method = LyricsParser.class.getDeclaredMethod("zlibInflate", byte[].class);
        method.setAccessible(true);
        return (byte[]) method.invoke(null, (Object) compressed);
    }
    private byte[] compress(int bytes) throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream out = new DeflaterOutputStream(compressed)) {
            byte[] block = new byte[4096];
            for (int left = bytes; left > 0; left -= Math.min(left, block.length)) {
                out.write(block, 0, Math.min(left, block.length));
            }
        }
        return compressed.toByteArray();
    }
    @Test public void rejectsCompressedExpansionBeyondBudget() throws Exception {
        try {
            inflate(compress(LyricResponseReader.LYRIC_BYTES + 1));
            fail("decoded QRC exceeded budget");
        } catch (InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof java.io.IOException);
        }
    }
    @Test public void exactDecodedBoundaryIsComplete() throws Exception {
        assertEquals(LyricResponseReader.LYRIC_BYTES,
                inflate(compress(LyricResponseReader.LYRIC_BYTES)).length);
    }
    @Test public void incompleteCompressedPayloadCannotReturnPartialSuccess() throws Exception {
        byte[] valid = compress(4096);
        try {
            inflate(java.util.Arrays.copyOf(valid, valid.length - 3));
            fail("incomplete compressed stream accepted");
        } catch (InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof java.io.IOException);
        }
    }
}
