package com.eza.spicyex.lyrics.ai;

import android.content.Context;

import java.util.function.Function;

/**
 * Where a run's paid store comes from.
 *
 * <p>The durable store is SQLite inside Spotify's private storage, so it exists only on a device.
 * Both AI runs ask this for their store: production always gets {@link AiPaidRecords}, and a JVM
 * test that needs to see a run reach the wire installs an in-memory store and puts the durable one
 * back afterwards.
 */
public final class AiRecordStores {

    private static volatile Function<Context, AiRecordStore> factory = AiPaidRecords::new;

    private AiRecordStores() {
    }

    /** The store for this run: the durable one, unless a test installed another. */
    public static AiRecordStore forRun(Context context) {
        return factory.apply(context);
    }

    /** Test seam; pass null to restore the durable store. */
    public static void installFactoryForTest(Function<Context, AiRecordStore> replacement) {
        factory = replacement == null ? AiPaidRecords::new : replacement;
    }
}
