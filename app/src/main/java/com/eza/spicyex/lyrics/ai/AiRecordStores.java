package com.eza.spicyex.lyrics.ai;

import android.content.Context;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.providers.SpicyOrgPolicy;

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

    /** Paid outputs remain durable; org input text is reconstructed from the live source. */
    public static AiRecordStore forRun(Context context, LyricsDocument document) {
        AiRecordStore store = forRun(context);
        if (!SpicyOrgPolicy.isRestricted(document)) return store;
        return new AiRecordStore() {
            @Override public AiPaidRecord read(AiRunConfig config) { return store.read(config); }
            @Override public Reservation reserve(AiRunConfig config, long maxRecordBytes) {
                return store.reserve(config, maxRecordBytes);
            }
            @Override public boolean commit(AiRunConfig config, AiPaidRecord record) {
                return store.commit(config, AiPaidRecordCodec.decode(AiPaidRecordCodec.encode(record, false)));
            }
            @Override public void release(AiRunConfig config) { store.release(config); }
            @Override public void forget(AiRunConfig config) { store.forget(config); }
        };
    }

    /** Test seam; pass null to restore the durable store. */
    public static void installFactoryForTest(Function<Context, AiRecordStore> replacement) {
        factory = replacement == null ? AiPaidRecords::new : replacement;
    }
}
