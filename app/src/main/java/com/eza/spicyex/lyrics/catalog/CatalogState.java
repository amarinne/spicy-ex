package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.catalog.CatalogSource.ProviderStatus;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the catalog knows about one track, read inside one transaction. Decisions are pure
 * functions of this snapshot, the policy, and the clock, so equal inputs always decide the same.
 */
public final class CatalogState {
    public final String trackId;
    /** Stored candidates, oldest first. */
    public final List<CatalogCandidate> candidates;
    public final Map<SourceId, ProviderRecord> providers;
    /** Persisted seat; never null (an unseated track is Auto with no candidate). */
    public final CatalogSelection selection;
    /** Rejected provider items, as {@link #rejectionKey} values. */
    public final Set<String> rejections;
    /** True once the catalog has seen this track (its metadata row exists). */
    public final boolean known;

    public CatalogState(String trackId, List<CatalogCandidate> candidates,
                        Map<SourceId, ProviderRecord> providers, CatalogSelection selection,
                        Set<String> rejections, boolean known) {
        this.trackId = trackId == null ? "" : trackId;
        this.known = known;
        this.candidates = Collections.unmodifiableList(candidates == null
                ? new ArrayList<CatalogCandidate>() : new ArrayList<>(candidates));
        Map<SourceId, ProviderRecord> copy = new EnumMap<>(SourceId.class);
        if (providers != null) copy.putAll(providers);
        this.providers = Collections.unmodifiableMap(copy);
        this.selection = selection == null ? CatalogSelection.auto(this.trackId) : selection;
        this.rejections = Collections.unmodifiableSet(rejections == null
                ? new HashSet<String>() : new HashSet<>(rejections));
    }

    public static CatalogState empty(String trackId) {
        return new CatalogState(trackId, null, null, null, null, false);
    }

    public CatalogCandidate candidate(String candidateId) {
        if (candidateId == null || candidateId.isEmpty()) return null;
        for (CatalogCandidate candidate : candidates) {
            if (candidateId.equals(candidate.candidateId)) return candidate;
        }
        return null;
    }

    public ProviderRecord provider(SourceId source) {
        ProviderRecord record = providers.get(source);
        return record == null ? ProviderRecord.notChecked(source) : record;
    }

    /** Status view for the picker: absent sources are omitted, meaning not checked. */
    public Map<SourceId, ProviderStatus> statuses() {
        Map<SourceId, ProviderStatus> out = new LinkedHashMap<>();
        for (Map.Entry<SourceId, ProviderRecord> entry : providers.entrySet()) {
            out.put(entry.getKey(), entry.getValue().status);
        }
        return out;
    }

    public boolean isRejected(SourceId source, String providerItemId) {
        return rejections.contains(rejectionKey(source, providerItemId));
    }

    /**
     * True once anything was recorded for this track. A track with history never re-imports the
     * public release's record, so an emptied or partly deleted track stays as the owner left it.
     */
    public boolean hasHistory() {
        return known || !candidates.isEmpty() || !providers.isEmpty() || !rejections.isEmpty()
                || !selection.candidateId.isEmpty()
                || selection.mode != CatalogSource.SelectionMode.AUTO;
    }

    public static String rejectionKey(SourceId source, String providerItemId) {
        return (source == null ? "unknown" : source.id) + "|"
                + (providerItemId == null ? "" : providerItemId);
    }
}
