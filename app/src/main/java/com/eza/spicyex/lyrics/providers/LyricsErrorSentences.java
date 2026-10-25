package com.eza.spicyex.lyrics.providers;

import com.eza.spicyex.diagnostics.DiagnosticEventBuffer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/**
 * Turns a lyric-fetch verdict chain into one or two short lines: what happened and, when the
 * reader can act, what to do next. A confirmed miss stays at one line because an instrumental
 * with no lyrics is a normal state.
 *
 * <p>The chain is the private text the fetch pipeline already hands to the fullscreen surface.
 * Its vocabulary comes from the existing classifiers, so this class adds no new taxonomy:
 * {@link LyricsFetchErrors#isDurableNoLyrics} separates a confirmed miss from a transient
 * failure, {@link LyricsProviderChain} names the stage that ended empty or transient,
 * {@link SpicyQueryEnvelope#failureReason} produces the rate-limit and upstream-error tokens,
 * and {@link SpicyCircuitBreaker.Suppressed} produces the suppression token.
 *
 * <p>The chain itself never reaches the surface. Callers log it through {@code XpLog} and keep
 * {@link #diagnosticToken} for diagnostics capture.
 */
public final class LyricsErrorSentences {
    /** Markers the query envelope and the breaker write when a source asked the app to wait. */
    private static final String[] BUSY_MARKERS = {
            "rate-limited", "rate limited", "queued", "request suppressed", "upstream degraded"
    };
    /** Markers a request leaves when it fails before any source answers. */
    private static final String[] TRANSPORT_MARKERS = {
            "network", "timeout", "timed out", "failed to connect", "unable to resolve host",
            "no address associated", "offline", "unreachable", "socket", "status 0"
    };
    /** Markers of a source that answered with a server failure rather than a rate limit. */
    private static final String[] SERVER_ERROR_MARKERS = {"upstream-error", "http 5"};
    /** Tokens the surface itself passes when a fetch produced no usable rows. */
    private static final String[] EMPTY_RESULT_TOKENS = {
            "empty lyrics response", "empty applied lyrics rows"
    };
    /**
     * Markers the fetch pipeline writes when it had no source to run at all.
     *
     * <p>Producers: {@code LyricsSessionManager.settledReason} for the planner's
     * {@code all-sources-disabled} reason at LyricsSessionManager.java:420-426;
     * {@code LyricsRepository.fetchLyrics} for an empty acquisition scope or a plan with no due
     * source at LyricsRepository.java:85 and :105, and its ordered walk at :134;
     * {@code LyricsSessionManager.acceptProviderView} when a refused delivery leaves the plan
     * with nothing to fetch at LyricsSessionManager.java:819.
     */
    private static final String[] NO_SOURCE_MARKERS = {
            "all lyric sources disabled", "no automatic lyric source is due",
            "source not eligible for this track"
    };
    /** The session manager's token for a finished visit without lyrics. */
    private static final String NO_LYRICS_STATUS = "lyrics unavailable";

    private LyricsErrorSentences() {
    }

    /** Verdict classes a reader can act on. */
    public enum Verdict {
        /** Every enabled source answered, and none had lyrics for this track. */
        NO_MATCH("no_match"),
        /** The request failed before any source answered. */
        NO_CONNECTION("no_connection"),
        /** A source is rate-limited, queued, paused by its breaker, or failing to answer. */
        SOURCE_BUSY("source_busy"),
        /** The plan had no enabled source to ask. */
        NO_SOURCE("no_source"),
        /** A terminal failure the sentences cannot narrow further. */
        GENERIC("generic");

        private final String token;

        Verdict(String token) {
            this.token = token;
        }

        /** Short stable token for diagnostics; never shown to the reader. */
        public String token() {
            return token;
        }
    }

    /** One localizable sentence: the resource name plus the English default it replaces. */
    public static final class Line {
        public final String resource;
        public final String fallback;

        private Line(String resource, String fallback) {
            this.resource = resource;
            this.fallback = fallback;
        }
    }

    /** The lines for one verdict, in reading order. Optional lines are {@code null}. */
    public static final class Report {
        public final Verdict verdict;
        public final Line title;
        /** Context line; {@code null} when the verdict needs no detail. */
        public final Line detail;
        /** Next step; {@code null} when there is nothing the reader can do. */
        public final Line action;

        private Report(Verdict verdict, Line title, Line detail, Line action) {
            this.verdict = verdict;
            this.title = title;
            this.detail = detail;
            this.action = action;
        }

        /** The lines a surface renders, skipping the ones this verdict omits. */
        public List<Line> sentences() {
            List<Line> lines = new ArrayList<>(3);
            if (title != null) lines.add(title);
            if (detail != null) lines.add(detail);
            if (action != null) lines.add(action);
            return Collections.unmodifiableList(lines);
        }
    }

    public static Report describe(String rawChain) {
        return forVerdict(classify(rawChain));
    }

    /** The sentences for one verdict, without reading a chain. */
    public static Report forVerdict(Verdict verdict) {
        switch (verdict) {
            case NO_MATCH:
                return new Report(verdict,
                        line("lyrics_error_no_match_title",
                                "No lyrics for this track."),
                        null,
                        null);
            case NO_CONNECTION:
                return new Report(verdict,
                        line("lyrics_error_no_connection_title",
                                "The lyrics could not be downloaded."),
                        null,
                        line("lyrics_error_no_connection_action",
                                "Try again when you are online."));
            case SOURCE_BUSY:
                return new Report(verdict,
                        line("lyrics_error_source_busy_title",
                                "The lyrics source is busy."),
                        null,
                        line("lyrics_error_source_busy_action",
                                "Try again in a minute."));
            case NO_SOURCE:
                return new Report(verdict,
                        line("lyrics_error_no_source_title",
                                "No lyrics source is enabled."),
                        null,
                        line("lyrics_error_no_source_action",
                                "Enable one in settings."));
            case GENERIC:
            default:
                return new Report(Verdict.GENERIC,
                        line("lyrics_error_generic_title",
                                "The lyrics could not be loaded."),
                        null,
                        line("lyrics_error_generic_action",
                                "Try again. Restart Spotify if it keeps failing."));
        }
    }

    /** Classifies the raw chain without changing it, for logging and diagnostics. */
    public static Verdict classify(String rawChain) {
        String chain = safe(rawChain).trim().toLowerCase(Locale.ROOT);
        if (isBlank(chain)) return Verdict.GENERIC;
        if (containsAny(chain, NO_SOURCE_MARKERS)) return Verdict.NO_SOURCE;
        if (LyricsFetchErrors.isDurableNoLyrics(chain)
                || containsAny(chain, EMPTY_RESULT_TOKENS)
                || NO_LYRICS_STATUS.equals(chain)) {
            return Verdict.NO_MATCH;
        }
        // A rate limit outranks the fallback stages that follow it: "rate-limited; LRCLIB empty"
        // is a wait, not a confirmed miss.
        if (containsAny(chain, BUSY_MARKERS)) return Verdict.SOURCE_BUSY;
        if (containsAny(chain, TRANSPORT_MARKERS)) return Verdict.NO_CONNECTION;
        if (containsAny(chain, SERVER_ERROR_MARKERS)) return Verdict.SOURCE_BUSY;
        return Verdict.GENERIC;
    }

    /**
     * Sanitized, bounded form of the chain for diagnostics capture. It keeps the reason tokens
     * the classifiers produced and drops spaces, quotes, and any long payload tail.
     */
    public static String diagnosticToken(String rawChain) {
        return DiagnosticEventBuffer.safeToken(safe(rawChain).trim(), 64);
    }

    private static Line line(String resource, String fallback) {
        return new Line(resource, fallback);
    }

    private static boolean containsAny(String chain, String[] markers) {
        for (String marker : markers) {
            if (chain.contains(marker)) return true;
        }
        return false;
    }
}
