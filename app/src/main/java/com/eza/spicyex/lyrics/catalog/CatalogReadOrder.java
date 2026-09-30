package com.eza.spicyex.lyrics.catalog;

import java.util.function.LongFunction;

/** Serializes snapshot reads and their publication sequence in the same critical section. */
final class CatalogReadOrder {
    private long sequence;

    synchronized <T> T read(LongFunction<T> reader) {
        return reader.apply(++sequence);
    }
}
