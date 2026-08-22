package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Whether an AI overlay may be applied right now — the master switch and Restore, together.
 *
 * <p>Both answers are session state and neither is ever persisted, which is the point. Restore
 * means "not this, not now": the owner wants the baseline back for the thing in front of them, not
 * a standing instruction that quietly suppresses a paid answer weeks later on a track they have
 * forgotten they ever pressed it on. It therefore dies with the process, the track, or any change
 * to what was suppressed.
 *
 * <p>Neither switch deletes anything. Disabling the feature drops live overlays and stops new work,
 * but a paid record survives untouched and comes back when the switch goes on again. Deleting paid
 * output is only ever an explicit, informed action.
 */
public final class AiOverlayGate {

    private boolean enabled;
    private final Set<String> suppressed = new LinkedHashSet<>();

    /** True when AI features are on. Off means no requests and no automatic cached overlay. */
    public synchronized boolean isEnabled() {
        return enabled;
    }

    /**
     * Turns the feature on or off.
     *
     * @return true when live overlays must be dropped, i.e. this call turned it off
     */
    public synchronized boolean setEnabled(boolean value) {
        boolean wasEnabled = enabled;
        enabled = value;
        if (!value) {
            // Restore is scoped to a session in which the feature was on. Leaving stale entries
            // behind would silently suppress the first answer after it is switched back on.
            suppressed.clear();
        }
        return wasEnabled && !value;
    }

    /**
     * Drops the overlay for exactly this answer until something about it changes.
     *
     * <p>Keyed on the full identity rather than the track, so re-running with a different model,
     * preset or target — a different question — is not caught by a Restore aimed at the old one.
     */
    public synchronized void restore(LayerKind layer, String trackUri, String configId,
                                     String docDigest) {
        suppressed.add(key(layer, trackUri, configId, docDigest));
    }

    /** True when this exact answer is currently suppressed. */
    public synchronized boolean isSuppressed(LayerKind layer, String trackUri, String configId,
                                             String docDigest) {
        return suppressed.contains(key(layer, trackUri, configId, docDigest));
    }

    /**
     * Clears suppression for this answer. An explicit request is the owner asking for it again,
     * which is precisely the intent Restore was expressing the opposite of.
     */
    public synchronized void clearSuppression(LayerKind layer, String trackUri, String configId,
                                              String docDigest) {
        suppressed.remove(key(layer, trackUri, configId, docDigest));
    }

    /** Forgets every suppression for a track. Called when the track is left. */
    public synchronized void forgetTrack(String trackUri) {
        String prefix = AiText.nz(trackUri) + "|";
        suppressed.removeIf(entry -> entry.startsWith(prefix));
    }

    /**
     * True when a stored answer may be applied without asking anyone.
     *
     * <p>The zero-cost path: it needs the feature on and this answer un-suppressed, and nothing
     * else. Deliberately not a credential check — a stored answer is already paid for, and losing
     * the key must not also lose access to what it bought.
     */
    public synchronized boolean mayAutoApply(LayerKind layer, String trackUri, String configId,
                                             String docDigest) {
        return enabled && !isSuppressed(layer, trackUri, configId, docDigest);
    }

    private static String key(LayerKind layer, String trackUri, String configId, String docDigest) {
        return AiText.nz(trackUri) + "|" + (layer == null ? "none" : layer.name())
                + "|" + AiText.nz(configId) + "|" + AiText.nz(docDigest);
    }
}
