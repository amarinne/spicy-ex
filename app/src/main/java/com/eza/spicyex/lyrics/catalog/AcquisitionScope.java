package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The sources one automatic fetch may ask, as decided by {@link AcquisitionPlanner}. The
 * repository consults this instead of the source settings, so a source the planner held back
 * (disabled, or answered recently) is never requested by the automatic chain.
 */
public final class AcquisitionScope {
    public static final AcquisitionScope NONE =
            new AcquisitionScope(Collections.<SourceId>emptyList(), false, false);

    /** Allowed sources, in ask order for source-order mode. */
    public final List<SourceId> sources;
    public final boolean sourceOrderMode;
    public final boolean karaokeOriginalLyrics;

    public AcquisitionScope(List<SourceId> sources, boolean sourceOrderMode) {
        this(sources, sourceOrderMode, false);
    }

    public AcquisitionScope(List<SourceId> sources, boolean sourceOrderMode,
                            boolean karaokeOriginalLyrics) {
        List<SourceId> copy = new ArrayList<>();
        if (sources != null) {
            for (SourceId source : sources) {
                if (source != null && !copy.contains(source)) copy.add(source);
            }
        }
        this.sources = Collections.unmodifiableList(copy);
        this.sourceOrderMode = sourceOrderMode;
        this.karaokeOriginalLyrics = karaokeOriginalLyrics;
    }

    public boolean allows(SourceId source) {
        return source != null && sources.contains(source);
    }

    public boolean isEmpty() {
        return sources.isEmpty();
    }

    /** Stable identity for in-flight de-duplication. */
    public String key() {
        StringBuilder out = new StringBuilder(sourceOrderMode ? "order:" : "auto:");
        for (SourceId source : sources) out.append(source.id).append(',');
        out.append(karaokeOriginalLyrics ? "karaoke-original" : "karaoke-verbatim");
        return out.toString();
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof AcquisitionScope)) return false;
        AcquisitionScope o = (AcquisitionScope) other;
        return sourceOrderMode == o.sourceOrderMode
                && karaokeOriginalLyrics == o.karaokeOriginalLyrics
                && sources.equals(o.sources);
    }

    @Override public int hashCode() {
        return Objects.hash(sources, sourceOrderMode, karaokeOriginalLyrics);
    }
}
