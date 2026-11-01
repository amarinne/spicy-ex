package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.DerivedLayerArtifact;
import com.eza.spicyex.lyrics.session.LayerAuthority;
import com.eza.spicyex.lyrics.session.LayerKind;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Revision of one layer's AI harness, used to keep paid output from different harness revisions
 * apart.
 *
 * <p>The epoch is part of a paid record's configuration identity, so two epochs never mix. It is
 * also stored in the record payload. A payload without the field decodes to {@link #LEGACY}, which
 * keeps every record written before the epoch existed addressable.
 *
 * <p>A new epoch alone never buys anything. Output from an older epoch stays displayable, and only
 * an explicit AI request or retry runs the new epoch. Automatic runs make no provider call when
 * only older output exists.
 */
public final class AiHarnessEpoch {

    /** Output written before the epoch was recorded. Every Sound record uses this epoch. */
    public static final int LEGACY = 0;

    /** The current Meaning harness revision. */
    public static final int MEANING = 1;

    private static final Pattern CONTRACT_EPOCH =
            Pattern.compile("\\|epoch=(\\d+)$");

    private AiHarnessEpoch() {
    }

    /** The epoch the current build runs {@code layer} under. */
    public static int forLayer(LayerKind layer) {
        return layer == LayerKind.MEANING ? MEANING : LEGACY;
    }

    /**
     * True when {@code epoch} is older than the current {@code layer} epoch.
     *
     * <p>An unknown epoch ({@code -1}) is not legacy: no output was identified.
     */
    public static boolean isLegacy(int epoch, LayerKind layer) {
        return epoch >= LEGACY && epoch < forLayer(layer);
    }

    /**
     * Epoch recorded in an AI artifact's provenance contract, or {@code -1} when the artifact was
     * not produced by the AI layer.
     *
     * <p>Diagnostics only. The storage key uses {@link AiIdentity#promptContractId}.
     */
    public static int ofArtifact(DerivedLayerArtifact artifact) {
        if (artifact == null || artifact.provenance == null
                || artifact.provenance.authority != LayerAuthority.AI) {
            return -1;
        }
        return ofContractId(artifact.provenance.contractId);
    }

    /**
     * Parses the optional {@code |epoch=N} suffix that {@link AiIdentity#artifactContractId} adds.
     *
     * <p>An absent suffix is {@link #LEGACY}: the identity stayed byte-identical for epoch zero.
     */
    public static int ofContractId(String contractId) {
        if (contractId == null) return LEGACY;
        Matcher match = CONTRACT_EPOCH.matcher(contractId);
        if (!match.find()) return LEGACY;
        try {
            return Integer.parseInt(match.group(1));
        } catch (NumberFormatException overflow) {
            return LEGACY;
        }
    }

    /**
     * Epoch recorded in one stored entry wrapper written by {@code AIPaidArtifactCache}.
     *
     * @return the epoch, or {@code -1} when the wrapper or payload cannot be read
     */
    public static int ofStoredPayload(String wrapper) {
        return AiPaidRecordCodec.storedEpoch(wrapper);
    }
}
