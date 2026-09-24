package com.eza.spicyex.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.atilika.kuromoji.dict.ConnectionCosts;
import com.atilika.kuromoji.unidic.Tokenizer;
import com.atilika.kuromoji.util.ResourceResolver;
import com.atilika.kuromoji.util.SimpleResourceResolver;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Guards the memory-mapped connectionCosts path, which on a device fails silently: every failure
 * returns null and falls back to kuromoji's own loader, so a broken mapping looks exactly like a
 * working one except that none of the memory is saved.
 */
public class MappedConnectionCostsTest {
    private static File extracted;

    @BeforeClass
    public static void extract() throws Exception {
        extracted = File.createTempFile("connectionCosts", ".bin");
        extracted.deleteOnExit();
        try (InputStream in = resolver().resolve(ConnectionCosts.CONNECTION_COSTS_FILENAME);
             OutputStream out = new FileOutputStream(extracted)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        }
    }

    /**
     * A SimpleResourceResolver resolves class-relative, so it only finds the dictionary when it is
     * pointed at a class in kuromoji's own unidic package. Building it from a Builder subclass's
     * {@code getClass()} - which is what Tokenizer.Builder's constructor does - would look in the
     * subclass's package instead and find nothing.
     */
    @Test
    public void resolverPointsAtKuromojisOwnPackage() throws Exception {
        try (InputStream in = resolver().resolve(ConnectionCosts.CONNECTION_COSTS_FILENAME)) {
            assertNotNull(in);
        }
        assertTrue(extracted.length() > 1_000_000L);
    }

    @Test
    public void mappedCostsMatchKuromojisOwnReader() throws Exception {
        ConnectionCosts mapped = MappedKuromojiDictionaries.map(extracted);
        assertNotNull("mapping returned null, so the device would silently fall back", mapped);

        ConnectionCosts reference = ConnectionCosts.newInstance(resolver());

        // The table is square and its side is the id count; sweep a coprime stride over both axes
        // so the sample crosses rows rather than walking one, and include the corners, where an
        // off-by-one in the two header ints would land first.
        int side = side(reference);
        assertEquals(side, side(mapped));
        for (int forward = 0; forward < side; forward += 97) {
            for (int backward = 0; backward < side; backward += 101) {
                assertEquals(reference.get(forward, backward), mapped.get(forward, backward));
            }
        }
        int last = side - 1;
        assertEquals(reference.get(0, 0), mapped.get(0, 0));
        assertEquals(reference.get(last, last), mapped.get(last, last));
        assertEquals(reference.get(0, last), mapped.get(0, last));
        assertEquals(reference.get(last, 0), mapped.get(last, 0));
    }

    private static int side(ConnectionCosts costs) throws Exception {
        java.lang.reflect.Field field = ConnectionCosts.class.getDeclaredField("size");
        field.setAccessible(true);
        return field.getInt(costs);
    }

    private static ResourceResolver resolver() {
        return new SimpleResourceResolver(Tokenizer.class);
    }
}
