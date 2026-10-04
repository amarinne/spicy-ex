package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.blend.SyncUpgradeEngine;
import com.eza.spicyex.lyrics.session.CanonicalSourceCodec;
import java.util.ArrayList;
import java.util.List;

/** Derives timing from stored donors without changing the catalog seat or provider payloads. */
public final class CatalogSyncUpgrade {
    private CatalogSyncUpgrade() { }

    public static LyricsDocument project(CatalogState state, CatalogPolicy policy,
                                         CatalogResolver.Resolution resolution,
                                         LyricsDocument anchor, long targetDurationMs, long nowMs) {
        if (state == null || policy == null || !policy.syncUpgradeEnabled || anchor == null
                || resolution == null || resolution.winner == null
                || state.selection.mode == CatalogSource.SelectionMode.MANUAL
                || !CatalogPolicy.primary(resolution.winner.sourceId)) return null;
        SyncUpgradeEngine.Result best = null;
        for (CatalogSource.SourceId source : policy.automaticOrder()) {
            if (!CatalogPolicy.syncDonor(source)) continue;
            List<CatalogCandidate> donors = new ArrayList<>();
            for (CatalogCandidate candidate : state.candidates) {
                if (candidate.sourceId == source && candidate.timingHealthy
                        && policy.eligibleForAuto(candidate)
                        && !state.isRejected(source, candidate.providerItemId)) donors.add(candidate);
            }
            for (CatalogCandidate donor : CatalogResolver.ranked(donors)) {
                CanonicalSourceCodec.Record record = LyricsCatalog.decode(donor);
                if (record == null) continue;
                SyncUpgradeEngine.Result result = SyncUpgradeEngine.upgrade(state.trackId,
                        targetDurationMs, anchor, record.document, resolution.winner, donor, nowMs);
                if (result.upgradedRows > 0 && (best == null || result.upgradedRows > best.upgradedRows)) {
                    best = result;
                }
            }
        }
        return best == null ? null : best.document;
    }
}
