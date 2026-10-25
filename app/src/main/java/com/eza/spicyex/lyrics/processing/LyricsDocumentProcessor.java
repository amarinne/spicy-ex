package com.eza.spicyex.lyrics.processing;

import com.eza.spicyex.lyrics.language.ProviderTextDetectionStore;
import com.eza.spicyex.lyrics.language.RomanizationOptions;
import com.eza.spicyex.lyrics.language.SpicyTextDetection;

import android.content.Context;

import com.eza.spicyex.SpotifyPlusConfig;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.CanonicalRow;
import com.eza.spicyex.lyrics.session.Digests;
import com.eza.spicyex.lyrics.session.LayerConfigIds;
import com.eza.spicyex.lyrics.session.LyricsSourcePolicy;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import com.eza.spicyex.lyrics.AppliedLine;
import com.eza.spicyex.lyrics.BackgroundLine;
import com.eza.spicyex.lyrics.LyricTimeline;
import com.eza.spicyex.lyrics.LyricUtils;
import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.LyricsRenderConfig;
import com.eza.spicyex.lyrics.SyllableSegment;
import com.eza.spicyex.lyrics.TranslationConfig;
import com.eza.spicyex.lyrics.cache.LyricCaches;
import com.eza.spicyex.lyrics.cache.ProcessedLyricsCache;

/** Shared post-parse and post-processing document helpers. */
public final class LyricsDocumentProcessor {
    private LyricsDocumentProcessor() {
    }

    public static void finalizeParsedDocument(Context context, LyricsDocument doc, int processingVersion) {
        if (doc == null) return;
        LyricTimeline.rebalanceStaticTimings(doc);
        LyricTimeline.fillMissingEndTimes(doc);
        applyProviderTranslations(context, doc);
        applyCachedGoogleEnhancements(context, doc, processingVersion);
        initProcessing(context, doc);
    }

    public static String collectText(LyricsDocument doc) {
        if (doc == null || doc.lines == null || doc.lines.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (LyricsLine line : doc.lines) {
            if (line == null || isBlank(line.text)) continue;
            if (out.length() > 0) out.append('\n');
            out.append(line.text);
        }
        return out.toString();
    }

    /**
     * Sound layer identity: reading options and source language only. Never contains a translation
     * backend or target, so changing the translation target cannot invalidate a reading artifact.
     */
    public static String soundConfigId(Context context, RomanizationOptions opts, String sourceLanguage) {
        return LayerConfigIds.sound(true,
                (opts == null ? RomanizationOptions.DEFAULTS : opts).cacheKey(),
                LyricCaches.sourceLanguageForCache(sourceLanguage),
                ProcessedLyricsCache.READING_SCHEMA_VERSION);
    }

    /**
     * Meaning layer identity: translation backend, target, and source-language selection only.
     * Never contains a romanization option, so cycling reading modes cannot discard translations.
     */
    public static String meaningConfigId(Context context) {
        SpotifyPlusConfig config = context == null ? null : SpotifyPlusConfig.from(context);
        // The stored pipeline value passes Settings coercion first: an unknown value reads as the
        // shipped default flow, so a downgrade can never keep a preview-shaped run identity.
        return TranslationConfig.read(config).identityInputs().configId();
    }

    public static CanonicalBase canonicalBaseOf(LyricsDocument doc) {
        return CanonicalBase.fromDocument(doc == null ? "" : doc.trackId, doc);
    }

    /**
     * Sound config identity read from <b>live</b> settings, not from options captured when a run
     * started. A run compares its captured ID against this at publish time, so a reading-mode
     * change mid-flight retires the run instead of letting it apply the old mode's output.
     */
    public static String currentSoundConfigId(Context context, LyricsDocument doc) {
        if (context == null) return soundConfigId(null, RomanizationOptions.DEFAULTS, "");
        SpotifyPlusConfig config = SpotifyPlusConfig.from(context);
        LyricsRenderConfig render = LyricsRenderConfig.read(context, config);
        RomanizationOptions options = new RomanizationOptions(render.defaultChineseMode,
                render.koreanMode, render.chineseTones, render.defaultCyrillicMode,
                render.cyrillicKeepSigns);
        return soundConfigId(context, options,
                effectiveSourceLanguage(TranslationConfig.read(config),
                        doc == null ? "" : doc.language));
    }

    /**
     * Restores both derived layers from their own stores. Each layer clears only its own pending
     * flag, so a cached reading never marks translation done and vice versa.
     */
    public static void applyProcessedCache(Context context, LyricsDocument doc, RomanizationOptions opts,
                                           int processingVersion) {
        applyProcessedCache(context, doc, opts, processingVersion, false);
    }

    /**
     * Restores legacy processed caches without overwriting an AI-authored layer already carried by
     * a session publication. Render surfaces receive composed documents; their old cache pass may
     * fill a missing layer, but must never replace the newer authority already in the document.
     */
    public static void applyProcessedCachePreservingAi(Context context, LyricsDocument doc,
                                                       RomanizationOptions opts,
                                                       int processingVersion) {
        applyProcessedCache(context, doc, opts, processingVersion, true);
    }

    private static void applyProcessedCache(Context context, LyricsDocument doc,
                                            RomanizationOptions opts, int processingVersion,
                                            boolean preserveAi) {
        if (context == null || doc == null || doc.lines.isEmpty()) return;
        CanonicalBase base = canonicalBaseOf(doc);
        SpotifyPlusConfig config = SpotifyPlusConfig.from(context);
        TranslationConfig translation = TranslationConfig.read(config);
        String sourceLanguage = effectiveSourceLanguage(translation, doc.language);
        ProcessedLyricsCache.Applied sound = shouldApplyCachedSound(doc, preserveAi)
                ? ProcessedLyricsCache.applySound(context, doc, base,
                soundConfigId(context, opts, sourceLanguage))
                : ProcessedLyricsCache.Applied.NONE;
        ProcessedLyricsCache.Applied meaning = shouldApplyCachedMeaning(doc, preserveAi)
                ? ProcessedLyricsCache.applyMeaning(context, doc, base,
                translation.identityInputs().configId())
                : ProcessedLyricsCache.Applied.NONE;
        if (sound.present) {
            doc.includesRomanization = true;
            if (sound.complete) doc.romanizationPending = false;
        }
        if (meaning.present) {
            doc.includesTranslation = true;
            if (meaning.complete) doc.translationPending = false;
        }
        doc.processingPending = doc.romanizationPending || doc.translationPending;
    }

    static boolean shouldApplyCachedSound(LyricsDocument doc, boolean preserveAi) {
        return doc != null && (!preserveAi || !doc.readingFromAi);
    }

    static boolean shouldApplyCachedMeaning(LyricsDocument doc, boolean preserveAi) {
        return doc != null && (!preserveAi || !doc.translationFromAi);
    }

    /**
     * Fingerprint of everything a consumer of this document would actually display: canonical
     * identity plus the derived text of every row.
     *
     * <p>Lets a publication boundary that cannot express deltas — the HyperGlow bridge serializes a
     * whole document over IPC — skip republishing when a layer completed without changing anything
     * a viewer would see. Several publications land per track as the lanes settle, and on a track
     * where one lane has no work its completion changes nothing.
     */
    public static String publicationFingerprint(LyricsDocument doc) {
        if (doc == null) return "";
        CanonicalBase base = canonicalBaseOf(doc);
        StringBuilder payload = new StringBuilder(256);
        payload.append(base.digest);
        for (CanonicalRow row : base.rows) {
            if (row.index >= doc.lines.size()) break;
            LyricsLine line = doc.lines.get(row.index);
            if (line == null) continue;
            payload.append('\u001f').append(row.rowId)
                    .append('\u001e').append(safeText(line.romanizedText))
                    .append('\u001e').append(safeText(line.translatedText))
                    .append('\u001e').append(line.readingRenderPlan == null
                            ? "" : safeText(line.readingRenderPlan.joinedDisplayText));
            for (SyllableSegment segment : line.syllables) {
                if (segment != null) payload.append('\u001d').append(safeText(segment.romanizedText));
            }
        }
        return Digests.sha256(payload.toString());
    }

    /**
     * True when the document already carries per-span reading text.
     *
     * <p>The Sound artifact carries span readings and the composer applies them, so a surface that
     * re-derives them is repeating work the session already did. Surfaces still need their own pass
     * for a document published before the Sound lane has produced anything.
     */
    public static boolean hasSpanReadings(LyricsDocument doc) {
        if (doc == null) return false;
        for (LyricsLine line : doc.lines) {
            if (line == null) continue;
            for (SyllableSegment segment : line.syllables) {
                if (segment != null && !isBlank(segment.romanizedText)) return true;
            }
        }
        return false;
    }

    public static boolean hasDisplayedSound(LyricsDocument doc) {
        if (doc == null || doc.lines == null) return false;
        for (LyricsLine line : doc.lines) {
            if (line == null) continue;
            if (line.readingRenderPlan != null
                    && !safeText(line.readingRenderPlan.joinedDisplayText).isEmpty()) return true;
            if (!safeText(line.romanizedText).isEmpty()) return true;
        }
        return false;
    }

    public static boolean hasDisplayedMeaning(LyricsDocument doc) {
        if (doc == null || doc.lines == null) return false;
        for (LyricsLine line : doc.lines) {
            if (line != null && !safeText(line.translatedText).isEmpty()) return true;
        }
        return false;
    }

    /**
     * Surface preparation may fill legacy per-span readings only while the Sound layer is absent.
     * Whole-line AI/Google output deliberately has no span alignment; attempting to derive one can
     * reject the guessed plan and clear the valid line-level text before it reaches rendering.
     */
    public static boolean needsSurfaceLocalRomanization(LyricsDocument doc) {
        return doc != null && !hasDisplayedSound(doc) && !hasSpanReadings(doc);
    }

    public enum DerivedMergeResult {
        DIFFERENT_BASE, UNCHANGED, CHANGED
    }

    /**
     * Copies derived layer text from {@code source} onto {@code target}, addressing rows by stable
     * canonical row ID.
     *
     * <p>Lets a surface absorb a derived-layer update without swapping the document object, which
     * is what preserves the lyric timeline, mounted rows, active-row state, and scroll position
     * across a reading or translation completion. This operation validates the canonical base
     * before any mutation, so callers do not need a separate comparison pass.
     *
     * @return DIFFERENT_BASE when a replacement is required; otherwise CHANGED or UNCHANGED refers
     *         to displayed text. Compatible publications always copy readiness and provenance.
     */
    public static DerivedMergeResult mergeDerivedPublication(LyricsDocument target, LyricsDocument source) {
        if (target == null || source == null) return DerivedMergeResult.DIFFERENT_BASE;
        CanonicalBase targetBase = canonicalBaseOf(target);
        CanonicalBase sourceBase = canonicalBaseOf(source);
        if (!targetBase.digest.equals(sourceBase.digest)) return DerivedMergeResult.DIFFERENT_BASE;
        boolean changed = false;
        for (CanonicalRow row : sourceBase.rows) {
            CanonicalRow targetRow = targetBase.row(row.rowId);
            int targetIndex = targetRow == null ? -1 : targetRow.index;
            if (targetIndex < 0 || targetIndex >= target.lines.size()
                    || row.index >= source.lines.size()) {
                continue;
            }
            changed |= mergeLine(target.lines.get(targetIndex), source.lines.get(row.index));
        }
        target.includesRomanization = source.includesRomanization;
        target.includesTranslation = source.includesTranslation;
        target.romanizationPending = source.romanizationPending;
        target.translationPending = source.translationPending;
        target.translationFailed = source.translationFailed;
        target.processingPending = source.processingPending;
        target.readingFromAi = source.readingFromAi;
        target.translationFromAi = source.translationFromAi;
        target.readingAiPending = source.readingAiPending;
        target.translationAiPending = source.translationAiPending;
        target.translationAiRefinedFromGoogle = source.translationAiRefinedFromGoogle;
        target.readingAiModel = source.readingAiModel;
        target.translationAiModel = source.translationAiModel;
        target.readingAiFailureToken = safeText(source.readingAiFailureToken);
        target.translationAiFailureToken = safeText(source.translationAiFailureToken);
        return changed ? DerivedMergeResult.CHANGED : DerivedMergeResult.UNCHANGED;
    }

    /**
     * Non-destructive per-line merge. A published document only ever carries the derived values its
     * lane produced, so an absent value means "this lane said nothing", never "clear what you have":
     * blanking on absence would wipe a surface's own per-span reading projection. Reading authority
     * stays single — a plan and a legacy string are never left side by side.
     */
    private static boolean mergeLine(LyricsLine target, LyricsLine source) {
        if (target == null || source == null) return false;
        boolean changed = false;
        if (source.detection != null && target.detection != source.detection) {
            target.detection = source.detection;
            changed = true;
        }
        if (source.readingRenderPlan != null) {
            if (target.readingRenderPlan != source.readingRenderPlan) {
                target.readingRenderPlan = source.readingRenderPlan;
                changed = true;
            }
            if (!safeText(target.romanizedText).isEmpty()) {
                target.romanizedText = "";
                changed = true;
            }
        } else if (!safeText(source.romanizedText).isEmpty()) {
            if (!safeText(source.romanizedText).equals(safeText(target.romanizedText))) {
                target.romanizedText = safeText(source.romanizedText);
                changed = true;
            }
            if (target.readingRenderPlan != null) {
                target.readingRenderPlan = null;
                changed = true;
            }
        }
        if (source.japaneseReading != null && target.japaneseReading != source.japaneseReading) {
            target.japaneseReading = source.japaneseReading;
            changed = true;
        }
        if (!safeText(source.chineseMode).isEmpty()
                && !safeText(source.chineseMode).equals(safeText(target.chineseMode))) {
            target.chineseMode = safeText(source.chineseMode);
            changed = true;
        }
        if (!safeText(source.translatedText).isEmpty()
                && !safeText(source.translatedText).equals(safeText(target.translatedText))) {
            target.translatedText = safeText(source.translatedText);
            changed = true;
        }
        int spans = Math.min(target.syllables.size(), source.syllables.size());
        for (int i = 0; i < spans; i++) {
            SyllableSegment to = target.syllables.get(i);
            SyllableSegment from = source.syllables.get(i);
            if (to == null || from == null || safeText(from.romanizedText).isEmpty()) continue;
            if (!safeText(from.romanizedText).equals(safeText(to.romanizedText))) {
                to.romanizedText = safeText(from.romanizedText);
                changed = true;
            }
        }
        int backgrounds = Math.min(target.backgroundLines.size(), source.backgroundLines.size());
        for (int i = 0; i < backgrounds; i++) {
            BackgroundLine to = target.backgroundLines.get(i);
            BackgroundLine from = source.backgroundLines.get(i);
            if (to == null || from == null) continue;
            if (!safeText(from.romanizedText).isEmpty()
                    && !safeText(from.romanizedText).equals(safeText(to.romanizedText))) {
                to.romanizedText = safeText(from.romanizedText);
                changed = true;
            }
            if (!safeText(from.translatedText).isEmpty()
                    && !safeText(from.translatedText).equals(safeText(to.translatedText))) {
                to.translatedText = safeText(from.translatedText);
                changed = true;
            }
        }
        return changed;
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    /**
     * Invalidates the Sound layer in place after a reading setting change: drops generated readings
     * and recomputes only Sound's flags. The Meaning layer is not touched.
     */
    public static void resetSoundLayer(Context context, LyricsDocument doc) {
        if (doc == null || doc.lines.isEmpty()) return;
        for (LyricsLine line : doc.lines) {
            if (line == null) continue;
            line.romanizedText = "";
            line.readingRenderPlan = null;
            line.japaneseReading = null;
            line.chineseMode = "";
            for (SyllableSegment segment : line.syllables) {
                if (segment != null) segment.romanizedText = "";
            }
            for (BackgroundLine background : line.backgroundLines) {
                if (background == null) continue;
                background.romanizedText = "";
                for (SyllableSegment segment : background.syllables) {
                    if (segment != null) segment.romanizedText = "";
                }
            }
        }
        for (AppliedLine row : doc.appliedLines) {
            if (row != null) row.romanizedText = "";
        }
        String fullText = collectText(doc);
        TranslationConfig translation = TranslationConfig.read(
                context == null ? null : SpotifyPlusConfig.from(context));
        String sourceLang = effectiveSourceLanguage(translation, doc.language);
        doc.includesRomanization = false;
        doc.readingFromAi = false;
        doc.readingAiPending = false;
        doc.readingAiFailureToken = "";
        doc.romanizationPending = SpicyProcessing.flagsFor(fullText, sourceLang, translation.target)
                .romanizationPending;
        doc.processingPending = doc.romanizationPending || doc.translationPending;
    }

    /** Persists a lane's Sound artifact under the identity it was produced with. */
    public static void saveSoundArtifact(Context context, CanonicalBase base,
                                         com.eza.spicyex.lyrics.session.SoundArtifact artifact) {
        ProcessedLyricsCache.saveSound(context, base, artifact);
    }

    /** Persists a lane's Meaning artifact under the identity it was produced with. */
    public static void saveMeaningArtifact(Context context, CanonicalBase base,
                                           com.eza.spicyex.lyrics.session.MeaningArtifact artifact) {
        ProcessedLyricsCache.saveMeaning(context, base, artifact);
    }

    /** Persists the Sound artifact only. Meaning state is untouched. */
    public static void saveSoundArtifact(Context context, LyricsDocument doc, RomanizationOptions opts,
                                         boolean complete) {
        if (context == null || doc == null || doc.lines.isEmpty()) return;
        String sourceLanguage = effectiveSourceLanguage(
                TranslationConfig.read(SpotifyPlusConfig.from(context)), doc.language);
        ProcessedLyricsCache.saveSound(context, doc, canonicalBaseOf(doc),
                soundConfigId(context, opts, sourceLanguage), complete);
    }

    /**
     * Invalidates the Meaning layer in place after a translation setting change: drops displayed
     * translations, re-applies provider-supplied ones, and recomputes only Meaning's flags.
     *
     * <p>Shared by every surface so fullscreen, now-playing, and HyperGlow cannot disagree about
     * what a translation setting change means. The Sound layer is not touched.
     */
    public static void resetMeaningLayer(Context context, LyricsDocument doc) {
        if (doc == null || doc.lines.isEmpty()) return;
        for (LyricsLine line : doc.lines) {
            if (line == null) continue;
            line.translatedText = "";
            for (BackgroundLine background : line.backgroundLines) {
                if (background != null) background.translatedText = "";
            }
        }
        for (AppliedLine row : doc.appliedLines) {
            if (row != null) row.translatedText = "";
        }
        SpotifyPlusConfig config = context == null ? null : SpotifyPlusConfig.from(context);
        TranslationConfig translation = TranslationConfig.read(config);
        applyProviderTranslations(context, doc);
        doc.includesTranslation = translation.enabled && hasDisplayedTranslation(doc);
        doc.translationFromAi = false;
        doc.translationAiPending = false;
        doc.translationAiRefinedFromGoogle = false;
        doc.translationAiFailureToken = "";
        doc.translationPending = translation.enabled
                && translation.usesGoogleBackend()
                && hasGeneratedTranslationWork(doc,
                        effectiveSourceLanguage(translation, doc.language), translation.target);
        doc.processingPending = doc.romanizationPending || doc.translationPending;
    }

    /** Persists the Meaning artifact only. Sound state is untouched. */
    public static void saveMeaningArtifact(Context context, LyricsDocument doc, boolean complete) {
        if (context == null || doc == null || doc.lines.isEmpty()) return;
        ProcessedLyricsCache.saveMeaning(context, doc, canonicalBaseOf(doc),
                meaningConfigId(context), complete);
    }

    /**
     * Restores per-line values from the cross-track provider cache at parse time.
     *
     * <p>Translations only. The reading half used to be restored here too, from a key that carries
     * no reading mode — so a line romanized once by the Google fallback kept that spelling forever,
     * immune to the user's reading-mode setting, and different tracks ended up displaying different
     * romanization styles. Readings now come exclusively from the mode-keyed Sound artifact, which
     * the Sound lane persists whether the text came from a local romanizer or the network fallback.
     */
    private static void applyCachedGoogleEnhancements(Context context, LyricsDocument doc, int processingVersion) {
        if (context == null || doc == null || doc.lines == null) return;
        SpotifyPlusConfig config = SpotifyPlusConfig.from(context);
        TranslationConfig translation = TranslationConfig.read(config);
        if (!translation.usesGoogleBackend()) return;
        String targetLang = translation.target;
        String sourceLang = effectiveSourceLanguage(translation, doc.language);
        boolean documentNeedsTranslation = SpicyProcessing.flagsFor(
                collectText(doc), sourceLang, targetLang).translationPending;
        if (!documentNeedsTranslation) return;
        for (LyricsLine line : doc.lines) {
            if (line == null || line.interlude || isBlank(line.text)) continue;
            if (!SpicyProcessing.flagsFor(line.text, sourceLang, targetLang,
                    line.detection).translationPending) continue;
            String cachedTranslated = LyricCaches.getProcessingValue(context, processingVersion,
                    LyricCaches.translationKey(doc.trackId, sourceLang, targetLang, line.text));
            if (isBlank(line.translatedText) && !isBlank(cachedTranslated)
                    && !GoogleEnhancer.sameText(cachedTranslated, line.text)) {
                line.translatedText = cachedTranslated;
            }
        }
    }

    private static void initProcessing(Context context, LyricsDocument doc) {
        if (doc == null) return;
        SpotifyPlusConfig config = context != null ? SpotifyPlusConfig.from(context) : null;
        TranslationConfig translation = TranslationConfig.read(config);
        String targetLang = translation.target;

        String fullText = collectText(doc);
        String sourceLang = effectiveSourceLanguage(translation, doc.language);
        SpicyProcessing.ProcessingFlags flags = SpicyProcessing.flagsFor(fullText, sourceLang, targetLang);
        doc.processingVersion = flags.processingVersion;
        doc.romanizationPending = flags.romanizationPending;
        doc.includesTranslation = translation.enabled && hasDisplayedTranslation(doc);
        doc.translationPending = translation.enabled
                && translation.usesGoogleBackend()
                && hasGeneratedTranslationWork(doc, sourceLang, targetLang);
        doc.processingPending = doc.romanizationPending || doc.translationPending;
        doc.detectedScripts.clear();
        doc.detectedScripts.addAll(SpicyTextDetection.detectPresentScripts(fullText, doc.language, ""));
        doc.detectedChinese = doc.detectedScripts.contains(SpicyTextDetection.Script.CHINESE);
    }

    /**
     * Recomputes document-level pending flags after session detection lands on the lines.
     *
     * <p>Detection can change which rows the translation gate accepts, so the flags computed at
     * parse time are refreshed before the lanes read them. Romanization stays a script check.
     */
    public static void recomputePendingFlags(Context context, LyricsDocument doc) {
        if (doc == null || doc.lines == null || doc.lines.isEmpty()) return;
        SpotifyPlusConfig config = context != null ? SpotifyPlusConfig.from(context) : null;
        TranslationConfig translation = TranslationConfig.read(config);
        String targetLang = translation.target;
        String sourceLang = effectiveSourceLanguage(translation, doc.language);
        doc.romanizationPending = SpicyProcessing.flagsFor(
                collectText(doc), sourceLang, targetLang).romanizationPending;
        doc.translationPending = translation.enabled
                && translation.usesGoogleBackend()
                && hasGeneratedTranslationWork(doc, sourceLang, targetLang);
        doc.processingPending = doc.romanizationPending || doc.translationPending;
    }

    private static String effectiveSourceLanguage(TranslationConfig translation, String documentLanguage) {
        return LyricsSourcePolicy.isStrictSourceLanguage(translation.sourceMode)
                ? translation.sourceLanguage : documentLanguage;
    }

    private static void applyProviderTranslations(Context context, LyricsDocument doc) {
        if (context == null || doc == null) return;
        SpotifyPlusConfig config = SpotifyPlusConfig.from(context);
        TranslationConfig translation = TranslationConfig.read(config);
        if (!translation.enabled) return;
        ProviderTranslationResolver.applyTranslations(doc, translation.target,
                ProviderTextDetectionStore.lookup(context));
    }

    /**
     * Re-runs provider translation selection once session detection has produced the auxiliary
     * detections a provider text needs to prove its language.
     */
    public static void reapplyProviderTranslations(Context context, LyricsDocument doc) {
        applyProviderTranslations(context, doc);
    }

    public static boolean hasGeneratedTranslationWork(LyricsDocument doc, String sourceLanguage, String targetLanguage) {
        if (doc == null || doc.lines == null) return false;
        // With session detection already attached, the whole-document gate is redundant and would
        // call the detector once more over the joined text. Rows own the decision now.
        if (!hasAnyDetection(doc)
                && !SpicyProcessing.flagsFor(collectText(doc), sourceLanguage,
                        targetLanguage).translationPending) {
            return false;
        }
        for (LyricsLine line : doc.lines) {
            if (line == null || line.interlude || isBlank(line.text) || !isBlank(line.translatedText)) continue;
            if (SpicyProcessing.flagsFor(line.text, sourceLanguage, targetLanguage,
                    line.detection).translationPending) return true;
        }
        return false;
    }

    private static boolean hasAnyDetection(LyricsDocument doc) {
        if (doc == null || doc.lines == null) return false;
        for (LyricsLine line : doc.lines) {
            if (line != null && line.detection != null) return true;
        }
        return false;
    }

    public static boolean hasDisplayedTranslation(LyricsDocument doc) {
        if (doc == null || doc.lines == null) return false;
        for (LyricsLine line : doc.lines) {
            if (line == null) continue;
            if (!isBlank(line.translatedText)) return true;
            if (line.backgroundLines == null) continue;
            for (BackgroundLine background : line.backgroundLines) {
                if (background != null && !isBlank(background.translatedText)) return true;
            }
        }
        return false;
    }

}
