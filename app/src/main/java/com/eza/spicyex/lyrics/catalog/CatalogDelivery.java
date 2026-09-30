package com.eza.spicyex.lyrics.catalog;

/** Exact provider provenance and the outcome of its one catalog write attempt. */
public final class CatalogDelivery {
    public final CatalogSource.SourceId source;
    public final String providerItemId;
    public final CatalogSource.MatchMethod matchMethod;
    public final boolean stored;

    public CatalogDelivery(CatalogSource.SourceId source, String providerItemId,
                           CatalogSource.MatchMethod matchMethod, boolean stored) {
        this.source = source;
        this.providerItemId = providerItemId == null ? "" : providerItemId;
        this.matchMethod = matchMethod == null
                ? CatalogSource.MatchMethod.STRONG_SEARCH : matchMethod;
        this.stored = stored;
    }
}
