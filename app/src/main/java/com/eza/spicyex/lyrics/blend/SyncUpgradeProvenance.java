package com.eza.spicyex.lyrics.blend;

/** Immutable provenance for a derived, in-memory timing projection. */
public final class SyncUpgradeProvenance {
    public final int algorithmVersion = 2;
    public final String anchorCandidateId;
    public final String anchorDigest;
    public final String donorCandidateId;
    public final String donorDigest;
    public final String donorSource;
    public final String donorProvider;
    public final String donorTimingType;
    public final String donorIdentity = "unknown_version";
    public final long spicyOrgFetchedAtMs;
    public final int upgradedRows;

    SyncUpgradeProvenance(String anchorCandidateId, String anchorDigest,
                          String donorCandidateId, String donorDigest,
                          String donorSource, String donorProvider, String donorTimingType,
                          long spicyOrgFetchedAtMs, int upgradedRows) {
        this.anchorCandidateId = anchorCandidateId;
        this.anchorDigest = anchorDigest;
        this.donorCandidateId = donorCandidateId;
        this.donorDigest = donorDigest;
        this.donorSource = donorSource;
        this.donorProvider = donorProvider;
        this.donorTimingType = donorTimingType;
        this.spicyOrgFetchedAtMs = spicyOrgFetchedAtMs;
        this.upgradedRows = upgradedRows;
    }
}
