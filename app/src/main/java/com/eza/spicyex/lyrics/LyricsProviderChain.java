package com.eza.spicyex.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/** Package-local state machine for provider fallback decisions. */
final class LyricsProviderChain {
    private final int generation;
    private final String cachedRaw;
    private final List<String> candidatesSeen = new ArrayList<>();

    private LyricsDocument pendingStatic;
    private Source pendingStaticSource;
    private String pendingStaticRaw;
    private boolean staticAlreadyShown;
    private boolean deliveredCached;
    private boolean deliveredCachedSynced;

    LyricsProviderChain(int generation, String cachedRaw) {
        this.generation = generation;
        this.cachedRaw = cachedRaw;
    }

    Decision acceptCached(LyricsDocument doc) {
        Result result = fromSpicyDocument(doc, Source.CACHE);
        if (result instanceof Synced) {
            deliveredCached = true;
            deliveredCachedSynced = true;
            addCandidate(Source.CACHE);
            return Decision.deliver(((Synced) result).document, false, null, true);
        }
        if (result instanceof Static) {
            Static statik = (Static) result;
            holdStatic(statik.document, statik.source, null, false);
            addCandidate(Source.CACHE);
            return Decision.holdStatic();
        }
        return Decision.ignore(result);
    }

    Decision spicyUnavailable(String message) {
        if (deliveredCachedSynced) return Decision.suppress();
        return Decision.continueAfter(new TransientFailure(Source.SPICY, message), pendingStatic);
    }

    Decision acceptSpicyNetwork(LyricsDocument doc, String raw) {
        addCandidate(Source.SPICY);
        Result result = fromSpicyDocument(doc, Source.SPICY);
        if (result instanceof Synced) {
            if (deliveredCached && safe(raw).equals(safe(cachedRaw))) {
                return Decision.suppress();
            }
            Synced synced = (Synced) result;
            return Decision.deliver(synced.document, true, raw, false);
        }
        if (result instanceof Static) {
            if (deliveredCachedSynced) return Decision.suppress();
            Static statik = (Static) result;
            String rawToCache = safe(raw).equals(safe(cachedRaw)) ? null : raw;
            holdStatic(statik.document, statik.source, rawToCache, false);
            return Decision.continueAfter(statik, pendingStatic);
        }
        if (deliveredCachedSynced) return Decision.suppress();
        return Decision.continueAfter(result, pendingStatic);
    }

    Decision acceptNative(LyricsDocument doc) {
        Result result = fromDocument(doc, Source.NATIVE);
        if (!(result instanceof Synced) && !(result instanceof Static)) {
            return Decision.continueAfter(result, pendingStatic);
        }
        addCandidate(Source.NATIVE);
        LyricsDocument nativeDoc = result.document();
        if (pendingStatic != null) {
            LyricsDocument winner = LyricQualityRanker.prefer(nativeDoc, pendingStatic) ? nativeDoc : pendingStatic;
            if (staticAlreadyShown) return Decision.suppress();
            if (winner == nativeDoc) {
                return Decision.deliver(nativeDoc, false, null, false);
            }
            return Decision.deliver(pendingStatic, !isBlank(pendingStaticRaw), pendingStaticRaw, false);
        }
        return Decision.deliver(nativeDoc, false, null, false);
    }

    Decision nativeMissAfterRetries(String message) {
        return Decision.continueAfter(new Empty(Source.NATIVE, false), pendingStatic, message);
    }

    Decision acceptLrclib(LyricsDocument doc) {
        Result result = fromDocument(doc, Source.LRCLIB);
        if (!(result instanceof Synced) && !(result instanceof Static)) {
            return finishWithoutStatic(result);
        }
        addCandidate(Source.LRCLIB);
        LyricsDocument lrclibDoc = result.document();
        if (pendingStatic == null) {
            return Decision.deliver(lrclibDoc, false, null, false);
        }
        LyricsDocument winner = LyricQualityRanker.prefer(lrclibDoc, pendingStatic) ? lrclibDoc : pendingStatic;
        if (winner == lrclibDoc) {
            return Decision.deliver(lrclibDoc, false, null, false);
        }
        if (staticAlreadyShown) return Decision.suppress();
        return Decision.deliver(pendingStatic, !isBlank(pendingStaticRaw), pendingStaticRaw, false);
    }

    Decision acceptLrclibError(String error) {
        Result result = LyricsFetchErrors.isDurableNoLyrics(error)
                ? new Empty(Source.LRCLIB, true)
                : new TransientFailure(Source.LRCLIB, error);
        if (pendingStatic != null) {
            if (staticAlreadyShown) return Decision.suppress();
            return Decision.deliver(pendingStatic, !isBlank(pendingStaticRaw), pendingStaticRaw, false);
        }
        return finishWithoutStatic(result, error);
    }

    boolean hasPendingStatic() {
        return pendingStatic != null;
    }

    LyricsDocument pendingStatic() {
        return pendingStatic;
    }

    boolean staticAlreadyShown() {
        return staticAlreadyShown;
    }

    String pendingStaticRaw() {
        return pendingStaticRaw;
    }

    boolean deliveredCachedSynced() {
        return deliveredCachedSynced;
    }

    List<String> candidatesSeen() {
        return candidatesSeen;
    }

    List<String> candidatesSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(candidatesSeen));
    }

    private Decision finishWithoutStatic(Result result) {
        return finishWithoutStatic(result, result == null ? "" : result.message());
    }

    private Decision finishWithoutStatic(Result result, String message) {
        if (result instanceof Empty && ((Empty) result).durableNoLyrics) {
            return Decision.error(message, true, result);
        }
        return Decision.error(message, false, result);
    }

    private Result fromSpicyDocument(LyricsDocument doc, Source source) {
        if (doc == null) return new Empty(source, false);
        doc.generation = generation;
        SpicyResponseClassifier.apply(doc);
        if (doc.spicyPoisoned) {
            return new TransientFailure(source, "Spicy response suspicious: " + safe(doc.spicyQualityReason));
        }
        return fromDocument(doc, source);
    }

    private Result fromDocument(LyricsDocument doc, Source source) {
        if (doc == null) return new Empty(source, false);
        doc.generation = generation;
        if (doc.lines == null || doc.lines.isEmpty()) return new Empty(source, false);
        if (LyricQualityRanker.score(doc) == LyricQualityRanker.REJECT) {
            return new TransientFailure(source, "Rejected lyric candidate");
        }
        if (isSyncedType(doc.type)) return new Synced(doc, source);
        return new Static(doc, source);
    }

    private void holdStatic(LyricsDocument doc, Source source, String raw, boolean alreadyShown) {
        pendingStatic = doc;
        pendingStaticSource = source;
        pendingStaticRaw = raw;
        staticAlreadyShown = alreadyShown;
    }

    private void addCandidate(Source source) {
        if (source == null || candidatesSeen.contains(source.label)) return;
        candidatesSeen.add(source.label);
    }

    static boolean isSyncedType(String type) {
        return "Line".equalsIgnoreCase(type) || "Syllable".equalsIgnoreCase(type);
    }

    enum Source {
        CACHE("cache"),
        SPICY("spicy"),
        NATIVE("native"),
        LRCLIB("lrclib"),
        UNSUPPORTED("unsupported");

        final String label;

        Source(String label) {
            this.label = label;
        }
    }

    abstract static class Result {
        final Source source;

        Result(Source source) {
            this.source = source;
        }

        LyricsDocument document() {
            return null;
        }

        String message() {
            return "";
        }
    }

    static final class Synced extends Result {
        final LyricsDocument document;

        Synced(LyricsDocument document, Source source) {
            super(source);
            this.document = document;
        }

        @Override
        LyricsDocument document() {
            return document;
        }
    }

    static final class Static extends Result {
        final LyricsDocument document;

        Static(LyricsDocument document, Source source) {
            super(source);
            this.document = document;
        }

        @Override
        LyricsDocument document() {
            return document;
        }
    }

    static final class Empty extends Result {
        final boolean durableNoLyrics;

        Empty(Source source, boolean durableNoLyrics) {
            super(source);
            this.durableNoLyrics = durableNoLyrics;
        }

        @Override
        String message() {
            return source.label + " empty";
        }
    }

    static final class TransientFailure extends Result {
        final String message;

        TransientFailure(Source source, String message) {
            super(source);
            this.message = safe(message);
        }

        @Override
        String message() {
            return message;
        }
    }

    static final class FatalUnsupported extends Result {
        final String message;

        FatalUnsupported(String message) {
            super(Source.UNSUPPORTED);
            this.message = safe(message);
        }

        @Override
        String message() {
            return message;
        }
    }

    static final class Decision {
        final Action action;
        final LyricsDocument document;
        final boolean cacheDeliveredRaw;
        final String rawToCache;
        final boolean durableNoLyrics;
        final boolean fromCachedSynced;
        final Result result;
        final String message;

        private Decision(Action action, LyricsDocument document, boolean cacheDeliveredRaw, String rawToCache,
                         boolean durableNoLyrics, boolean fromCachedSynced, Result result, String message) {
            this.action = action;
            this.document = document;
            this.cacheDeliveredRaw = cacheDeliveredRaw;
            this.rawToCache = rawToCache;
            this.durableNoLyrics = durableNoLyrics;
            this.fromCachedSynced = fromCachedSynced;
            this.result = result;
            this.message = safe(message);
        }

        static Decision deliver(LyricsDocument doc, boolean cacheDeliveredRaw, String rawToCache, boolean fromCachedSynced) {
            return new Decision(Action.DELIVER, doc, cacheDeliveredRaw, rawToCache, false, fromCachedSynced, null, "");
        }

        static Decision continueAfter(Result result, LyricsDocument pendingStatic) {
            return continueAfter(result, pendingStatic, result == null ? "" : result.message());
        }

        static Decision continueAfter(Result result, LyricsDocument pendingStatic, String message) {
            return new Decision(Action.CONTINUE, pendingStatic, false, null, false, false, result, message);
        }

        static Decision holdStatic() {
            return new Decision(Action.HOLD_STATIC, null, false, null, false, false, null, "");
        }

        static Decision suppress() {
            return new Decision(Action.SUPPRESS, null, false, null, false, false, null, "");
        }

        static Decision ignore(Result result) {
            return new Decision(Action.IGNORE, null, false, null, false, false, result,
                    result == null ? "" : result.message());
        }

        static Decision error(String message, boolean durableNoLyrics, Result result) {
            return new Decision(Action.ERROR, null, false, null, durableNoLyrics, false, result, message);
        }
    }

    enum Action {
        DELIVER,
        CONTINUE,
        HOLD_STATIC,
        SUPPRESS,
        IGNORE,
        ERROR
    }
}
