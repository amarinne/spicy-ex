package com.eza.spicyex.settings;

import com.eza.spicyex.Settings;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences.RankingMode;
import com.eza.spicyex.lyrics.session.LyricsSourcePreferences.Source;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit adapter for the source-selection namespace ({@code LyricsSourcePreferences}),
 * which lives beside the ordinary {@code SettingsStore} and can never share a transaction
 * with it.
 *
 * <p>Commit order is the contract: ordinary ranking values first (override pinned to Auto,
 * then the ranking mode label), then the source-namespace ranking mode, order, and enabled
 * flags. Callers refresh the panel after committing; a crash between namespaces leaves the
 * ordinary values ahead of the source namespace, never the reverse.
 */
public final class SourcePreferencesAdapter {
    /** Source-namespace writes; production targets {@code LyricsSourcePreferences}. */
    public interface Sink {
        void setRankingMode(RankingMode mode);

        void setSourceOrder(List<Source> order);

        void setSourceEnabled(Source source, boolean enabled);
    }

    /** One merged source Save: ranking label plus working order plus per-source toggles. */
    public static final class Commit {
        public final String rankingLabel;
        public final String override;
        public final List<Source> order;
        public final Map<Source, Boolean> enabled;

        public Commit(String rankingLabel, List<Source> order, Map<Source, Boolean> enabled) {
            this(rankingLabel, "Auto", order, enabled);
        }

        public Commit(String rankingLabel, String override, List<Source> order, Map<Source, Boolean> enabled) {
            this.rankingLabel = rankingLabel;
            this.override = override == null ? "Auto" : override;
            this.order = order == null
                    ? new ArrayList<Source>()
                    : new ArrayList<Source>(order);
            this.enabled = enabled == null
                    ? new EnumMap<Source, Boolean>(Source.class)
                    : new EnumMap<Source, Boolean>(enabled);
        }
    }

    private final Sink sink;

    public SourcePreferencesAdapter(Sink sink) {
        if (sink == null) throw new IllegalArgumentException("sink == null");
        this.sink = sink;
    }

    public void commit(SettingsWriter writer, Commit commit) {
        writer.put(Settings.LYRICS_SOURCE_OVERRIDE, commit.override);
        writer.put(Settings.LYRICS_SOURCE_MODE, commit.rankingLabel);
        sink.setRankingMode(RankingMode.parse(commit.rankingLabel));
        sink.setSourceOrder(commit.order);
        // Every source: a hand-kept list here silently dropped QQ Music's toggle on save.
        for (Source source : Source.values()) {
            Boolean on = commit.enabled.get(source);
            sink.setSourceEnabled(source, on != null && on);
        }
    }
}
