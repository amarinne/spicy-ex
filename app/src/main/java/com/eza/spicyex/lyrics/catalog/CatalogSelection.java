package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.catalog.CatalogSource.SelectionMode;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;

import java.util.Objects;

/**
 * What a track renders: Auto with the persisted automatic winner, or one manual pick. The stable
 * candidate ID (not a provider name or content digest alone) is what a pin holds, so a refreshed
 * provider item and a replaced provider item are distinguishable.
 */
public final class CatalogSelection {
    public final String trackId;
    public final SelectionMode mode;
    public final String candidateId;
    public final SourceId sourceId;
    public final String providerItemId;
    public final String lastAcceptedDigest;

    public CatalogSelection(String trackId, SelectionMode mode, String candidateId,
                            SourceId sourceId, String providerItemId, String lastAcceptedDigest) {
        this.trackId = nz(trackId);
        this.mode = mode == null ? SelectionMode.AUTO : mode;
        this.candidateId = nz(candidateId);
        this.sourceId = sourceId;
        this.providerItemId = nz(providerItemId);
        this.lastAcceptedDigest = nz(lastAcceptedDigest);
    }

    public static CatalogSelection auto(String trackId) {
        return new CatalogSelection(trackId, SelectionMode.AUTO, "", null, "", "");
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof CatalogSelection)) return false;
        CatalogSelection o = (CatalogSelection) other;
        return trackId.equals(o.trackId) && mode == o.mode && candidateId.equals(o.candidateId)
                && sourceId == o.sourceId && providerItemId.equals(o.providerItemId)
                && lastAcceptedDigest.equals(o.lastAcceptedDigest);
    }

    @Override public int hashCode() {
        return Objects.hash(trackId, mode, candidateId, sourceId, providerItemId, lastAcceptedDigest);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
