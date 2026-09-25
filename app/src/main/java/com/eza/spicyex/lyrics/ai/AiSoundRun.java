package com.eza.spicyex.lyrics.ai;

import android.content.Context;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.LayerAuthority;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.lyrics.session.LayerProvenance;
import com.eza.spicyex.lyrics.session.SoundArtifact;
import com.eza.spicyex.lyrics.session.SoundEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One AI pronunciation pass over the rows the local engines could not read.
 *
 * <p>Unlike Meaning, this is a gap filler and not an authority. It is handed only the rows the
 * deterministic pipeline left uncovered, and what it returns is line-level by construction —
 * {@link AiSoundOverlay} builds entries that carry display text and no spans, so nothing here can
 * disturb word-level timing or overwrite a curated reading.
 *
 * <p>If the deterministic engines covered everything, this never runs and nothing is billed. That
 * is the common case for Japanese, Korean, Chinese and Cyrillic, and it is the point: AI is for
 * Thai, for dialects, and for the scripts no packaged engine exists for.
 */
public final class AiSoundRun {

    private AiSoundRun() {
    }

    public static final class Result {
        public final SoundArtifact artifact;
        public final AiRunOutcome outcome;

        Result(SoundArtifact artifact, AiRunOutcome outcome) {
            this.artifact = artifact;
            this.outcome = outcome;
        }

        public boolean hasArtifact() {
            return artifact != null && !artifact.isEmpty();
        }
    }

    /**
     * @param existing    what the Sound lane already produced, so covered rows are excluded
     * @param orthography target orthography the readings are wanted in
     * @param layered     true sends the existing Google line as a baseline; false sends source only
     */
    public static Result run(Context context, AiSettings settings, CanonicalBase base,
                             LyricsDocument document, SoundArtifact existing, String orthography,
                             boolean layered, boolean allowProviderRequest, AiSignal signal,
                             AiRunMonitor monitor) {
        if (settings == null || base == null || base.rows.isEmpty()) {
            return new Result(null, AiRunOutcome.nothingToDo());
        }
        if (!AiContract.isKnownOrthography(orthography)) {
            return new Result(null, AiRunOutcome.nothingToDo());
        }

        List<AiLine> rows = AiRows.forSound(base, document, existing, orthography, layered);
        // Every row covered locally: the engines did their job and this costs nothing.
        if (!AiRows.hasWork(rows)) return new Result(null, AiRunOutcome.nothingToDo());

        AiProviderConfig providerConfig = settings.providerConfig(LayerKind.SOUND, orthography);
        if (providerConfig == null) return new Result(null, AiRunOutcome.nothingToDo());

        AiLyricContext lyricContext = AiLyricContext.EMPTY;
        // Sound includes the baseline in its digest: what was sent as `p` is part of what the
        // answer was derived from, so a changed baseline is a different question.
        String docDigest = AiIdentity.buildDocDigest(rows, lyricContext, layered);
        String sourceLanguage = AiText.nz(base.language).toLowerCase(java.util.Locale.ROOT);
        if (sourceLanguage.isEmpty()) sourceLanguage = "und";

        AiIdentity.Config identity = new AiIdentity.Config();
        identity.layer = LayerKind.SOUND;
        identity.provider = settings.providerId();
        identity.providerVersion = providerConfig.providerVersion;
        identity.endpoint = providerConfig.endpoint;
        identity.modelName = providerConfig.model.name;
        identity.targetLang = orthography;
        identity.targetOrthography = orthography;
        identity.sourceLanguage = sourceLanguage;
        identity.soundMode = "whole_line_v1";
        identity.soundBaselineMode = layered ? "existing_output_v1" : "raw_source_v1";
        identity.instructions = settings.instructions(LayerKind.SOUND);
        identity.promptVersion = AiContract.SOUND_PROMPT_VERSION;

        AiRunConfig config = new AiRunConfig(LayerKind.SOUND, docDigest,
                AiIdentity.buildConfigId(identity), settings.providerId(), lyricContext,
                providerConfig, identity.instructions, sourceLanguage, null);

        AiLayerRunner.Args args = new AiLayerRunner.Args();
        args.config = config;
        args.provider = settings.provider();
        args.store = AiRecordStores.forRun(context);
        args.rows = rows;
        args.signal = signal;
        args.monitor = monitor;
        args.useBaseline = layered;
        // The runner still reads the paid store first. A missing/deleted credential therefore
        // keeps exact reuse available, but can never fall through into a live request.
        args.allowProviderRequest = allowProviderRequest && settings.canRequest();

        AiRunOutcome outcome = AiLayerRunner.run(args);
        if (!outcome.hasOutput()) return new Result(null, outcome);
        AiChunkFailure storedFailure = validateStoredOutput(outcome.record, rows, orthography);
        if (storedFailure != null) {
            return new Result(null, AiRunOutcome.failed(outcome.record, storedFailure, true));
        }
        return new Result(artifactOf(base, config, outcome.record, orthography), outcome);
    }

    /** Revalidates durable/reused items so an old or corrupt paid record cannot reach rendering. */
    private static AiChunkFailure validateStoredOutput(AiPaidRecord record, List<AiLine> rows,
                                                       String orthography) {
        if (record == null) return AiChunkFailure.of(AiFailureReason.PROTOCOL_INVALID);
        for (AiLine row : rows) {
            if (row == null || !row.isSent()) continue;
            String output = record.item(row.id);
            if (output == null) {
                return new AiChunkFailure(AiFailureReason.PROTOCOL_INVALID, 0,
                        "id_set_mismatch:missing:" + row.id);
            }
            if (!AiResponseValidator.soundOrthographyAccepts(
                    output, orthography, row.sourceText)) {
                return new AiChunkFailure(AiFailureReason.PROTOCOL_INVALID, 0,
                        "target_orthography_mismatch:" + row.id);
            }
        }
        return null;
    }

    private static SoundArtifact artifactOf(CanonicalBase base, AiRunConfig config,
                                            AiPaidRecord record, String orthography) {
        List<AiResponseItem> items = new ArrayList<>();
        for (Map.Entry<String, String> item : record.items().entrySet()) {
            if (base.row(item.getKey()) == null) continue;
            items.add(new AiResponseItem(item.getKey(), item.getValue()));
        }
        List<SoundEntry> entries = AiSoundOverlay.entriesOf(items, orthography);
        if (entries.isEmpty()) return null;
        return new SoundArtifact(base.digest, config.configId,
                new LayerProvenance(LayerAuthority.AI, config.providerId,
                        AiIdentity.promptContractId(LayerKind.SOUND, config.configId),
                        config.modelName, System.currentTimeMillis()),
                entries, false);
    }
}
