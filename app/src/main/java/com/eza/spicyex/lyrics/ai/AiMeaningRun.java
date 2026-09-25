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
import java.util.List;
import java.util.Map;

/**
 * One AI translation of one document, from canonical rows to a finished artifact.
 *
 * <p>The lane knows when to run and on which thread; this knows what a run is. Keeping them apart
 * means the expensive half — identity, reuse, resume, accounting — stays testable without a
 * {@code Context}, an executor, or a network.
 *
 * <p>Only a complete answer becomes an artifact. Partial work is stored by the runner so it can be
 * resumed, but a document showing half AI translations beside half Google ones reads as a rendering
 * bug rather than as progress, so display waits for the whole thing.
 */
public final class AiMeaningRun {

    private AiMeaningRun() {
    }

    /** What the lane needs back: something to publish, or a reason there is nothing. */
    public static final class Result {
        public final MeaningArtifact artifact;
        public final AiRunOutcome outcome;

        Result(MeaningArtifact artifact, AiRunOutcome outcome) {
            this.artifact = artifact;
            this.outcome = outcome;
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
        if (settings == null || base == null || base.rows.isEmpty()) {
            return new Result(null, AiRunOutcome.nothingToDo());
        }

        if (refineGoogle && (googleBaseline == null || googleBaseline.isEmpty())) {
            return new Result(null, AiRunOutcome.failed(null,
                    AiChunkFailure.of(AiFailureReason.BASELINE_UNAVAILABLE), true));
        }
        List<AiLine> rows = AiRows.forMeaning(base, document, googleBaseline, refineGoogle);
        if (!AiRows.hasWork(rows)) return new Result(null, AiRunOutcome.nothingToDo());

        AiProviderConfig providerConfig = settings.providerConfig(
                LayerKind.MEANING, targetLang, refineGoogle);
        if (providerConfig == null) return new Result(null, AiRunOutcome.nothingToDo());

        AiLyricContext lyricContext = AiLyricContext.EMPTY;
        String docDigest = AiIdentity.buildDocDigest(rows, lyricContext, refineGoogle);
        String instructions = settings.instructions(LayerKind.MEANING);
        String configId = configIdFor(settings, providerConfig, targetLang, instructions);
        AiRunConfig config = new AiRunConfig(LayerKind.MEANING, docDigest, configId,
                settings.providerId(), lyricContext, providerConfig, instructions, null, null);

        AiRecordStore records = AiRecordStores.forRun(context);
        AiPaidRecord cached = records.read(config);
        if (cached != null && cached.isComplete()) {
            MeaningArtifact artifact = artifactOf(base, config, cached);
            if (refineGoogle && artifact != null) {
                artifact = artifact.withGoogleBaseline(googleBaseline);
            }
            return new Result(artifact, AiRunOutcome.reused(cached));
        }
        // A stored answer may be read without a live credential. A new paid request may not.
        if (!settings.canRequest()) return new Result(null, AiRunOutcome.nothingToDo());

        AiLayerRunner.Args args = new AiLayerRunner.Args();
        args.config = config;
        args.provider = settings.provider();
        args.store = records;
        args.rows = rows;
        args.signal = signal;
        args.monitor = monitor;
        args.allowProviderRequest = allowProviderRequest;
        args.useBaseline = refineGoogle;

        AiRunOutcome outcome = AiLayerRunner.run(args);
        if (!outcome.hasOutput()) return new Result(null, outcome);
        MeaningArtifact artifact = artifactOf(base, config, outcome.record);
        if (refineGoogle && artifact != null) artifact = artifact.withGoogleBaseline(googleBaseline);
        return new Result(artifact, outcome);
    }

    private static String configIdFor(AiSettings settings, AiProviderConfig providerConfig,
                                      String targetLang, String instructions) {
        AiIdentity.Config input = new AiIdentity.Config();
        input.layer = LayerKind.MEANING;
        input.provider = settings.providerId();
        input.providerVersion = providerConfig.providerVersion;
        input.modelName = providerConfig.model.name;
        input.targetLang = targetLang;
        input.instructions = instructions;
        input.promptVersion = providerConfig.promptVersion;
        return AiIdentity.buildConfigId(input);
    }

    /**
     * Builds the artifact from the record's accepted items.
     *
     * <p>Rows the model left byte-identical to the source are dropped rather than published. An
     * "translation" that is the original line adds a duplicate line to the screen, and the owner
     * paid for it either way — hiding it is the honest presentation, not a silent failure.
     */
    private static MeaningArtifact artifactOf(CanonicalBase base, AiRunConfig config,
                                              AiPaidRecord record) {
        List<MeaningEntry> entries = new ArrayList<>();
        for (Map.Entry<String, String> item : record.items().entrySet()) {
            String rowId = item.getKey();
            String text = AiText.nz(item.getValue());
            if (text.isEmpty()) continue;
            com.eza.spicyex.lyrics.session.CanonicalRow row = base.row(rowId);
            if (row == null) continue;
            if (text.equals(row.text)) continue;
            entries.add(new MeaningEntry(rowId, text, config.targetLang));
        }
        if (entries.isEmpty()) return null;
        return new MeaningArtifact(base.digest, config.configId,
                new LayerProvenance(LayerAuthority.AI, config.providerId,
                        AiIdentity.promptContractId(LayerKind.MEANING, config.configId),
                        config.modelName, System.currentTimeMillis()),
                entries, false);
    }
}
