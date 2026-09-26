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
 * Auto ranks by quality or by source order. The persisted source settings are translated here,
 * once; runtime code reads this snapshot and never consults the preference store itself.
 */
public final class CatalogPolicy {
    /** Enabled sources in user order. Disabled sources are absent. */
    public final List<SourceId> enabledOrder;
    public final boolean sourceOrderMode;
    public final boolean karaokeOriginalLyrics;

    public CatalogPolicy(List<SourceId> enabledOrder, boolean sourceOrderMode) {
        this(enabledOrder, sourceOrderMode, false);
    }

    public CatalogPolicy(List<SourceId> enabledOrder, boolean sourceOrderMode,
                         boolean karaokeOriginalLyrics) {
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
            if (mapped != null && !order.contains(mapped)) order.add(mapped);
        }
        return new CatalogPolicy(order, LyricsSourcePreferences.rankingMode(context)
                == LyricsSourcePreferences.RankingMode.SOURCE_ORDER,
                context != null && SpotifyPlusConfig.from(context)
                        .get(Settings.KARAOKE_ORIGINAL_LYRICS));
    }

    public boolean enabled(SourceId source) {
        return source != null && enabledOrder.contains(source);
    }

    /** Automatic selection obeys the global karaoke substitution option; manual pins do not. */
    public boolean eligibleForAuto(CatalogCandidate candidate) {
        return candidate != null && enabled(candidate.sourceId)
                && (karaokeOriginalLyrics
                || candidate.matchMethod != CatalogSource.MatchMethod.KARAOKE_SUBSTITUTION);
    }

    /** Settings-level source to catalog identity. The retired Spicy route is Apple. */
    public static SourceId sourceId(LyricsSourcePreferences.Source source) {
        if (source == null) return null;
        switch (source) {
            case APPLE_MUSIC:
            case SPICY:
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
                && enabledOrder.equals(o.enabledOrder);
    }

    @Override public int hashCode() {
        return Objects.hash(enabledOrder, sourceOrderMode, karaokeOriginalLyrics);
    }

    @Override public String toString() {
        return (sourceOrderMode ? "source-order" : "auto") + enabledOrder
                + (karaokeOriginalLyrics ? "+karaoke-original" : "+karaoke-verbatim");
    }
}
