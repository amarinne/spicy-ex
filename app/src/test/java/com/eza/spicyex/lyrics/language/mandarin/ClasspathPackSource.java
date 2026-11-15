package com.eza.spicyex.lyrics.language.mandarin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Test source: reads the Mandarin g2pM files from the language pack source folder on the test classpath. */
public final class ClasspathPackSource implements MandarinReader.PackSource {
    private static final String ROOT = "mandarin-g2pm/";
    private final ClassLoader loader;

    public ClasspathPackSource() {
        this(MandarinReader.class.getClassLoader());
    }

    public ClasspathPackSource(ClassLoader loader) {
        this.loader = loader;
    }

    @Override
    public byte[] manifest() throws IOException {
        return read("manifest.json");
    }

    @Override
    public byte[] file(String fileName) throws IOException {
        return read(fileName);
    }

    /** Attribution and source notices shipped beside the pack. */
    public byte[] notice() throws IOException {
        return read("BUNDLE-NOTICE.txt");
    }

    /** One shipped licence text, named relative to the pack directory. */
    public byte[] licence(String fileName) throws IOException {
        return read(fileName);
    }

    private byte[] read(String name) throws IOException {
        InputStream stream = loader == null ? null : loader.getResourceAsStream(ROOT + name);
        if (stream == null) throw new IOException("Missing Mandarin g2pM resource: " + name);
        try (InputStream in = stream;
             ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
