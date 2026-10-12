package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.catalog.CatalogSource;
import com.eza.spicyex.lyrics.session.CanonicalBase;

/** Source provenance only. A routed provider never changes the acquisition retention policy. */
public final class AiSourceBinding {
    public final String trackId, candidateId, route, upstream, providerItemId, canonicalDigest;

    public AiSourceBinding(CanonicalBase base, LyricsDocument document) {
        trackId = base.trackId;
        candidateId = AiText.nz(document.catalogCandidateId);
        CatalogSource.SourceId source = CatalogSource.inferSourceId(base.fetchSource, base.provider);
        route = source == null ? "unknown" : source.id;
        upstream = CatalogSource.upstream(document);
        providerItemId = document.catalogDelivery == null ? ""
                : document.catalogDelivery.providerItemId;
        canonicalDigest = base.digest;
    }
}
