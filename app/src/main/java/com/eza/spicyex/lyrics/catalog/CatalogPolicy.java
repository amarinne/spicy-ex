package com.eza.spicyex.lyrics.catalog;

import android.content.Context;

import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Acquisition and selection policy snapshot: which sources are enabled, their order, and whether
 * Auto uses the primary stack or the user source order. Persisted settings are translated here
 * once; runtime code reads this snapshot and never consults the preference store itself.
 */
public final class CatalogPolicy {
    /** Enabled sources in user order. Disabled sources are absent. */
    public final List<SourceId> enabledOrder;
    public final boolean sourceOrderMode;
    public final boolean karaokeOriginalLyrics;
    public final long spicyOrgKeyEpoch;
    public final boolean syncUpgradeEnabled;
    public final boolean spicyOrgTerminated;
    public final long spicyOrgAccessRevision;

    public CatalogPolicy(List<SourceId> enabledOrder, boolean sourceOrderMode) {
        this(enabledOrder, sourceOrderMode, false);
    }

    public CatalogPolicy(List<SourceId> enabledOrder, boolean sourceOrderMode,
                         boolean karaokeOriginalLyrics) {
        this(enabledOrder, sourceOrderMode, karaokeOriginalLyrics, false);
    }

    public CatalogPolicy(List<SourceId> enabledOrder, boolean sourceOrderMode,
                         boolean karaokeOriginalLyrics, boolean syncUpgradeEnabled) {
        this(enabledOrder, sourceOrderMode, karaokeOriginalLyrics, syncUpgradeEnabled, 0L);
    }

    private CatalogPolicy(List<SourceId> enabledOrder, boolean sourceOrderMode,
                          boolean karaokeOriginalLyrics, boolean syncUpgradeEnabled,
                          long spicyOrgKeyEpoch) {
        this(enabledOrder, sourceOrderMode, karaokeOriginalLyrics, syncUpgradeEnabled,
                spicyOrgKeyEpoch, false, 0);
    }

    CatalogPolicy(List<SourceId> enabledOrder, boolean sourceOrderMode,
                  boolean karaokeOriginalLyrics, boolean syncUpgradeEnabled,
                  long spicyOrgKeyEpoch, boolean spicyOrgTerminated, long accessRevision) {
        this.spicyOrgTerminated = spicyOrgTerminated;
        this.spicyOrgAccessRevision = accessRevision;
        this.syncUpgradeEnabled = syncUpgradeEnabled;
        this.spicyOrgKeyEpoch = spicyOrgKeyEpoch;
        List<SourceId> order = new ArrayList<>();
        if (enabledOrder != null) {
            for (SourceId source : enabledOrder) {
                if (source != null && !order.contains(source)) order.add(source);
            }
        }
        this.enabledOrder = Collections.unmodifiableList(order);
        this.sourceOrderMode = sourceOrderMode;
        this.karaokeOriginalLyrics = karaokeOriginalLyrics;
    }

    public static CatalogPolicy read(Context context) {
        List<SourceId> order = new ArrayList<>();
        for (LyricsSourcePreferences.Source source
                : LyricsSourcePreferences.enabledSourceOrder(context)) {
            SourceId mapped = sourceId(source);
            if (mapped == SourceId.SPICY_ORG
                    && !com.eza.spicyex.lyrics.providers.SpicyOrgAccessState.canAcquire(context)) continue;
            if (mapped != null && !order.contains(mapped)) order.add(mapped);
        }
        return new CatalogPolicy(order, LyricsSourcePreferences.rankingMode(context)
                == LyricsSourcePreferences.RankingMode.SOURCE_ORDER,
                context != null && SpotifyPlusConfig.from(context)
                        .get(Settings.KARAOKE_ORIGINAL_LYRICS),
                context != null && SpotifyPlusConfig.from(context).get(Settings.SYNC_UPGRADE),
                com.eza.spicyex.lyrics.providers.SpicyOrgKeyStore.epoch(context),
                com.eza.spicyex.lyrics.providers.SpicyOrgAccessState.isTerminated(context),
                com.eza.spicyex.lyrics.providers.SpicyOrgAccessState.revision(context));
    }

    public boolean enabled(SourceId source) {
        return source != null && enabledOrder.contains(source);
    }

    /** Automatic selection obeys the global karaoke substitution option; manual pins do not. */
    public boolean eligibleForAuto(CatalogCandidate candidate) {
        return eligibleForDisplay(candidate) && enabled(candidate.sourceId)
                && (karaokeOriginalLyrics
                || candidate.matchMethod != CatalogSource.MatchMethod.KARAOKE_SUBSTITUTION);
    }

    /** Access termination also blocks manual pins without removing the stored decision. */
    public boolean eligibleForDisplay(CatalogCandidate candidate) {
        return candidate != null && candidate.hasValidProviderTiming()
                && (candidate.sourceId != SourceId.SPICY_ORG || !spicyOrgTerminated);
    }

    public boolean hasCurrentOrgAccess(Context context) {
        synchronized (com.eza.spicyex.lyrics.providers.SpicyOrgKeyStore.class) {
            return spicyOrgKeyEpoch == com.eza.spicyex.lyrics.providers.SpicyOrgKeyStore.epoch(context)
                    && spicyOrgAccessRevision == com.eza.spicyex.lyrics.providers.SpicyOrgAccessState.revision(context)
                    && spicyOrgTerminated == com.eza.spicyex.lyrics.providers.SpicyOrgAccessState.isTerminated(context);
        }
    }

    /** Auto keeps the router and direct primary stack before user-ordered fallbacks. */
    public List<SourceId> automaticOrder() {
        if (sourceOrderMode) return enabledOrder;
        List<SourceId> order = new ArrayList<>();
        for (SourceId source : new SourceId[]{SourceId.SPICY_ORG, SourceId.APPLE,
                SourceId.SPOTIFY_NATIVE}) {
            if (enabled(source)) order.add(source);
        }
        for (SourceId source : enabledOrder) if (!order.contains(source)) order.add(source);
        return Collections.unmodifiableList(order);
    }

    public static boolean primary(SourceId source) {
        return source == SourceId.SPICY_ORG || source == SourceId.APPLE
                || source == SourceId.SPOTIFY_NATIVE;
    }

    public static boolean syncDonor(SourceId source) {
        return source == SourceId.QQ || source == SourceId.NETEASE;
    }

    /** Settings-level source to catalog acquisition identity. */
    public static SourceId sourceId(LyricsSourcePreferences.Source source) {
        if (source == null) return null;
        switch (source) {
            case SPICY:
                return SourceId.SPICY_ORG;
            case APPLE_MUSIC:
                return SourceId.APPLE;
            case SPOTIFY:
                return SourceId.SPOTIFY_NATIVE;
            case AMLL:
                return SourceId.AMLL;
            case LRCLIB:
                return SourceId.LRCLIB;
            case QQ:
                return SourceId.QQ;
            case NETEASE:
                return SourceId.NETEASE;
            default:
                return null;
        }
    }

    /** Catalog identity to the repository's strict-source route. */
    public static LyricsSourcePreferences.Source preferenceSource(SourceId source) {
        if (source == null) return null;
        switch (source) {
            case SPICY_ORG:
                return LyricsSourcePreferences.Source.SPICY;
            case APPLE:
                return LyricsSourcePreferences.Source.APPLE_MUSIC;
            case SPOTIFY_NATIVE:
                return LyricsSourcePreferences.Source.SPOTIFY;
            case AMLL:
                return LyricsSourcePreferences.Source.AMLL;
            case LRCLIB:
                return LyricsSourcePreferences.Source.LRCLIB;
            case QQ:
                return LyricsSourcePreferences.Source.QQ;
            case NETEASE:
                return LyricsSourcePreferences.Source.NETEASE;
            default:
                return null;
        }
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof CatalogPolicy)) return false;
        CatalogPolicy o = (CatalogPolicy) other;
        return sourceOrderMode == o.sourceOrderMode
                && karaokeOriginalLyrics == o.karaokeOriginalLyrics
                && syncUpgradeEnabled == o.syncUpgradeEnabled
                && spicyOrgKeyEpoch == o.spicyOrgKeyEpoch
                && spicyOrgTerminated == o.spicyOrgTerminated
                && spicyOrgAccessRevision == o.spicyOrgAccessRevision
                && enabledOrder.equals(o.enabledOrder);
    }

    @Override public int hashCode() {
        return Objects.hash(enabledOrder, sourceOrderMode, karaokeOriginalLyrics, syncUpgradeEnabled,
                spicyOrgKeyEpoch, spicyOrgTerminated, spicyOrgAccessRevision);
    }

    @Override public String toString() {
        return (sourceOrderMode ? "source-order" : "auto") + enabledOrder
                + (karaokeOriginalLyrics ? "+karaoke-original" : "+karaoke-verbatim")
                + (syncUpgradeEnabled ? "+sync-upgrade" : "");
    }
}
