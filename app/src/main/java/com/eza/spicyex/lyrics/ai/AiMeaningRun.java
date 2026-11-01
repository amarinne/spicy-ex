package com.eza.spicyex.lyrics.ai;

import android.content.Context;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.LayerAuthority;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.LayerProvenance;
import com.eza.spicyex.lyrics.session.MeaningArtifact;
import com.eza.spicyex.lyrics.session.MeaningEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One AI translation of one document, from canonical rows to a finished artifact.
 *
 * <p>The lane knows when to run and on which thread; this knows what a run is. Keeping them apart
 * means the expensive half — identity, reuse, resume, accounting — stays testable without a
 * {@code Context}, an executor, or a network.
 *
 * <p>Terminal partial output preserves accepted rows. The lane selects an artifact only when it
 * covers the required translation rows. Interrupted work remains stored until it can finish or
 * prove exact fallback rows.
 */
public final class AiMeaningRun {

    private AiMeaningRun() {
    }

    /** What the lane needs back: something to publish, or a reason there is nothing. */
    public static final class Result {
        public final MeaningArtifact artifact;
        public final AiRunOutcome outcome;
        /** Harness epoch of the returned artifact, or of the attempted run when it is empty. */
        public final int epoch;
        /** True when the artifact is older-epoch output that was kept for display. */
        public final boolean legacy;

        Result(MeaningArtifact artifact, AiRunOutcome outcome) {
            this(artifact, outcome, AiHarnessEpoch.forLayer(LayerKind.MEANING), false);
        }

        Result(MeaningArtifact artifact, AiRunOutcome outcome, int epoch, boolean legacy) {
            this.artifact = artifact;
            this.outcome = outcome;
            this.epoch = epoch;
            this.legacy = legacy;
        }

        public boolean hasArtifact() {
            return artifact != null && !artifact.isEmpty();
        }
    }

    /**
     * Runs, or explains why not.
     *
     * @param base      canonical rows and the digest the answer is keyed to
     * @param document  parsed document, read only for the layout voice hint
     * @param targetLang translation target
     * @param signal    cancels the run and the call under it
     */
    public static Result run(Context context, AiSettings settings, CanonicalBase base,
                             LyricsDocument document, MeaningArtifact googleBaseline,
                             boolean refineGoogle, String targetLang,
                             boolean allowProviderRequest, AiSignal signal,
                             AiRunMonitor monitor) {
        return run(context, settings, base, document, googleBaseline, refineGoogle, targetLang,
                allowProviderRequest, signal, monitor, false);
    }

    public static Result run(Context context, AiSettings settings, CanonicalBase base,
                             LyricsDocument document, MeaningArtifact googleBaseline,
                             boolean refineGoogle, String targetLang, boolean allowProviderRequest,
                             AiSignal signal, AiRunMonitor monitor, boolean explicitRetry) {
        if (settings == null || base == null || base.rows.isEmpty()) {
            return new Result(null, AiRunOutcome.nothingToDo());
        }

        List<AiLine> rows = AiRows.forMeaning(base, document, googleBaseline, refineGoogle);
        if (!AiRows.hasWork(rows)) return new Result(null, AiRunOutcome.nothingToDo());

        AiProviderConfig providerConfig = settings.providerConfig(
                LayerKind.MEANING, targetLang, refineGoogle);
        if (providerConfig == null) return new Result(null, AiRunOutcome.nothingToDo());

        AiLyricContext lyricContext = AiLyricContext.EMPTY;
        String docDigest = AiIdentity.buildDocDigest(rows, lyricContext, refineGoogle);
        String instructions = settings.instructions(LayerKind.MEANING);
        int epoch = AiHarnessEpoch.forLayer(LayerKind.MEANING);
        String configId = configIdFor(settings, providerConfig, targetLang, instructions, epoch);
        AiRunConfig config = new AiRunConfig(LayerKind.MEANING, docDigest, configId,
                settings.providerId(), lyricContext, providerConfig, instructions, null, null,
                epoch);

        AiRecordStore records = AiRecordStores.forRun(context, document);
        AiReplayIdentity replay = AiReplayIdentity.of(rows, refineGoogle);
        AiSourceBinding binding = new AiSourceBinding(base, document);
        AiPaidRecord compatible = records.readReplay(config, replay);
        if (config.matchesConfiguration(compatible)) {
            AiPaidRecord projected = replay.project(compatible);
            if (projected != null && projected.hasCompleteItemsForRows(rows)) {
                records.bind(compatible, binding, replay);
                MeaningArtifact artifact = artifactOf(base, config, projected, rows);
                if (refineGoogle && artifact != null && googleBaseline != null) {
                    artifact = artifact.withGoogleBaseline(googleBaseline);
                }
                return new Result(artifact, AiRunOutcome.reused(projected));
            }
        }
        AiPaidRecord cached = records.read(config);
        if (cached != null && cached.isCompleteForRows(rows)) {
            records.bind(cached, binding, replay);
            MeaningArtifact artifact = artifactOf(base, config, cached, rows);
            if (refineGoogle && artifact != null) {
                artifact = artifact.withGoogleBaseline(googleBaseline);
            }
            return new Result(artifact, AiRunOutcome.reused(cached));
        }
        // Older-epoch output stays displayable. Its identity is recomputable from the same
        // settings with the plain instructions, so this needs no ledger or binding scan.
        AiRunConfig legacyConfig = new AiRunConfig(LayerKind.MEANING, docDigest,
                configIdFor(settings, providerConfig, targetLang, instructions,
                        AiHarnessEpoch.LEGACY),
                settings.providerId(), lyricContext, providerConfig, instructions, null,
                null, AiHarnessEpoch.LEGACY);
        AiPaidRecord legacyRecord = records.readReplay(legacyConfig, replay);
        MeaningArtifact legacyArtifact = null;
        if (legacyConfig.matchesConfiguration(legacyRecord)) {
            AiPaidRecord projected = replay.project(legacyRecord);
            if (projected != null && projected.hasCompleteItemsForRows(rows)) {
                records.bind(legacyRecord, binding, replay);
                legacyArtifact = artifactOf(base, legacyConfig, projected, rows);
                if (refineGoogle && legacyArtifact != null) {
                    legacyArtifact = legacyArtifact.withGoogleBaseline(googleBaseline);
                }
            }
        }
        boolean legacyFound = legacyRecord != null;
        if (legacyArtifact == null) {
            AiPaidRecord storedLegacy = records.readLegacy(legacyConfig);
            legacyFound |= storedLegacy != null;
            legacyRecord = storedLegacy;
            if (storedLegacy != null && storedLegacy.isCompleteForRows(rows)) {
                records.bind(storedLegacy, binding, replay);
                legacyArtifact = artifactOf(base, legacyConfig, storedLegacy, rows);
                if (refineGoogle && legacyArtifact != null) {
                    legacyArtifact = legacyArtifact.withGoogleBaseline(googleBaseline);
                }
            }
        }
        boolean canBuy = allowProviderRequest && settings.canRequest();
        if (legacyFound && !canBuy) {
            // A run that may call the provider replaces older-epoch output with a current-epoch
            // purchase. Any other run shows the older answer with no provider call and no runner,
            // and does not resume a partial record of the current epoch either.
            if (legacyArtifact == null) return new Result(null, AiRunOutcome.nothingToDo());
            return new Result(legacyArtifact, AiRunOutcome.reused(legacyRecord),
                    AiHarnessEpoch.LEGACY, true);
        }
        if (refineGoogle && (googleBaseline == null || googleBaseline.isEmpty())) {
            AiRunOutcome outcome = allowProviderRequest ? AiRunOutcome.failed(null,
                    AiChunkFailure.of(AiFailureReason.BASELINE_UNAVAILABLE), true)
                    : AiRunOutcome.nothingToDo();
            return legacyArtifact == null ? new Result(null, outcome)
                    : new Result(legacyArtifact, outcome, AiHarnessEpoch.LEGACY, true);
        }
        AiLayerRunner.Args args = new AiLayerRunner.Args();
        args.config = config;
        args.provider = settings.provider();
        args.store = records;
        args.rows = rows;
        args.signal = signal;
        args.monitor = monitor;
        args.allowProviderRequest = canBuy;
        args.explicitRetry = explicitRetry;
        args.replay = replay;
        args.binding = binding;
        args.useBaseline = refineGoogle;

        AiRunOutcome outcome = AiLayerRunner.run(args);
        if (!outcome.hasOutput()) {
            return legacyArtifact == null ? new Result(null, outcome)
                    : new Result(legacyArtifact, outcome, AiHarnessEpoch.LEGACY, true);
        }
        MeaningArtifact artifact = artifactOf(base, config, outcome.record, rows);
        if (refineGoogle && artifact != null) artifact = artifact.withGoogleBaseline(googleBaseline);
        if (artifact == null && legacyArtifact != null) {
            return new Result(legacyArtifact, outcome, AiHarnessEpoch.LEGACY, true);
        }
        return new Result(artifact, outcome, epoch, false);
    }

    private static String configIdFor(AiSettings settings, AiProviderConfig providerConfig,
                                      String targetLang, String instructions, int harnessEpoch) {
        AiIdentity.Config input = new AiIdentity.Config();
        input.layer = LayerKind.MEANING;
        input.provider = settings.providerId();
        input.providerVersion = providerConfig.providerVersion;
        input.modelName = providerConfig.model.name;
        input.targetLang = targetLang;
        input.instructions = instructions;
        input.promptVersion = providerConfig.promptVersion;
        input.harnessEpoch = harnessEpoch;
        return AiIdentity.buildConfigId(input);
    }

    /**
     * Builds the artifact from the record's accepted items.
     *
     * <p>Rows the model left byte-identical to the source are dropped rather than published. An
     * "translation" that is the original line adds a duplicate line to the screen, and the owner
     * paid for it either way — hiding it is the honest presentation, not a silent failure.
     *
     * <p>The stored record is read raw and never rewritten: repeat reconciliation (when enabled)
     * is a display-time projection only, so identity, resume, and accounting keep pointing at
     * exactly what the provider returned.
     *
     * <p>Package-private so the pure projection stays testable without a {@code Context}.
     *
     * @param rows the run's enumerated rows, the only source of each row's class and voice hint
     */
    static MeaningArtifact artifactOf(CanonicalBase base, AiRunConfig config,
                                      AiPaidRecord record, List<AiLine> rows) {
        Map<String, String> repeatDisplay = isCreativePreset(config)
                ? null : repeatDisplayOf(record, rows);
        List<MeaningEntry> entries = new ArrayList<>();
        for (Map.Entry<String, String> item : record.items().entrySet()) {
            String rowId = item.getKey();
            String raw = AiText.nz(item.getValue());
            if (raw.isEmpty()) continue;
            com.eza.spicyex.lyrics.session.CanonicalRow row = base.row(rowId);
            if (row == null) continue;
            String text = repeatDisplay == null || !repeatDisplay.containsKey(rowId)
                    ? raw : repeatDisplay.get(rowId);
            if (text.equals(row.text)) continue;
            entries.add(new MeaningEntry(rowId, text, config.targetLang));
        }
        if (entries.isEmpty()) return null;
        return new MeaningArtifact(base.digest, config.configId,
                new LayerProvenance(LayerAuthority.AI, config.providerId,
                        AiIdentity.artifactContractId(LayerKind.MEANING, config.configId,
                                config.harnessEpoch),
                        config.modelName, System.currentTimeMillis()),
                entries, false);
    }

    // --- display-time repeat reconciliation --------------------------------------------
    //
    // A weak model breaks identical repeats (7–12% in lab runs against 0.3–0.4% for strong
    // models), and even a strong model may drift between chunks. Whatever the cause, the song asks
    // the same question several times: identical source lines are shown with the first accepted
    // answer, exactly what a repeat is in every other presentation of a song.

    /**
     * Whether this run's display may reconcile repeated source lines.
     *
     * <p>The {@code Creative} preset keeps its own repeat rule and may rephrase freely. Any other
     * instruction, including custom text, is non-creative here.
     */
    private static boolean isCreativePreset(AiRunConfig config) {
        String instructions = config == null ? null : config.instructions;
        if (instructions == null || instructions.isEmpty()) return false;
        return instructions.equals(AiContract.normalizeSteering(
                AiPresets.instructions(LayerKind.MEANING, "Creative")));
    }

    /**
     * The display projection of the record's raw items after repeat reconciliation, or a map
     * touching nothing the record already holds. Keys are the record's row ids; an entry is the
     * accepted output the row should display instead of its own — the first accepted output of
     * its repeat group.
     *
     * <p>"First accepted" means first in the record's item order, which is the document's
     * enumeration order, so the surviving wording is deterministic and stable across replays.
     * Raw accepted text is never inspected for quality; a group of identical sources reading
     * identically changes nothing, and the empty and byte-identical-to-source drops still apply
     * afterwards to the projected text.
     *
     * <p>Grouping is byte-identical source text — the same identity the lab harness uses when it
     * splits ledger rows on the text-hash half of the wire id — narrowed by class and voice so
     * two lines that are the same words but different parts of the song never borrow each
     * other's wording. Row lines cannot contain a newline, so the joined key is unambiguous.
     */
    static Map<String, String> repeatDisplayOf(AiPaidRecord record, List<AiLine> rows) {
        Map<String, String> display = new LinkedHashMap<>();
        if (record == null || rows == null) return display;
        Map<String, AiLine> lineById = new HashMap<>();
        for (AiLine line : rows) {
            if (line != null && line.isSent()) lineById.put(line.id, line);
        }
        Map<String, String> firstOutputByGroup = new HashMap<>();
        for (Map.Entry<String, String> item : record.items().entrySet()) {
            String rowId = item.getKey();
            String text = AiText.nz(item.getValue());
            if (text.isEmpty()) continue;
            display.put(rowId, text);
            AiLine line = lineById.get(rowId);
            if (line == null) continue;
            String group = line.lineClass.token + '\n'
                    + (line.voice == null ? "" : line.voice.token) + '\n' + line.sourceText;
            String first = firstOutputByGroup.get(group);
            if (first == null) {
                firstOutputByGroup.put(group, text);
            } else if (!first.equals(text)) {
                display.put(rowId, first);
            }
        }
        return display;
    }
}
