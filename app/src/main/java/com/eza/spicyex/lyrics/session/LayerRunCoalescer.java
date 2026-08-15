package com.eza.spicyex.lyrics.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coalesces identical in-flight derived-layer work so multiple render surfaces never duplicate a
 * run — and never duplicate a billable provider call.
 *
 * <p>Keyed by {@link LayerRunIdentity#artifactCacheKey()}: the same canonical digest under the same
 * layer configuration is the same work regardless of which surface asked for it.
 */
public final class LayerRunCoalescer {
    private final Map<String, String> inFlight = new LinkedHashMap<>();
    private final Map<String, List<Runnable>> deferred = new LinkedHashMap<>();

    /**
     * Registers {@code identity} as the owner of its work key, or reports the run already doing it.
     *
     * @return the run ID that owns the work — {@code identity.runId} when this caller won, or the
     *         existing owner's run ID when this caller should join instead of starting
     */
    public synchronized String beginOrJoin(LayerRunIdentity identity) {
        if (identity == null) return "";
        String key = identity.artifactCacheKey();
        String owner = inFlight.get(key);
        if (owner != null) return owner;
        inFlight.put(key, identity.runId);
        return identity.runId;
    }

    /** True when this caller started the work rather than joining an existing run. */
    public synchronized boolean isOwner(LayerRunIdentity identity) {
        return identity != null && identity.runId.equals(inFlight.get(identity.artifactCacheKey()));
    }

    public synchronized boolean isInFlight(LayerRunIdentity identity) {
        return identity != null && inFlight.containsKey(identity.artifactCacheKey());
    }

    /**
     * Claims the work, or queues {@code whenOwnerFinishes} to run once the owning run completes.
     *
     * <p>The deferred caller re-runs after the owner has written its results, so the second surface
     * still gets its layer filled — from cache, without a second provider call.
     *
     * @return true when this caller owns the work and should start it now
     */
    public synchronized boolean beginOrDefer(LayerRunIdentity identity, Runnable whenOwnerFinishes) {
        if (identity == null) return false;
        String key = identity.artifactCacheKey();
        if (!inFlight.containsKey(key)) {
            inFlight.put(key, identity.runId);
            return true;
        }
        if (whenOwnerFinishes != null) {
            List<Runnable> queued = deferred.get(key);
            if (queued == null) {
                queued = new ArrayList<>();
                deferred.put(key, queued);
            }
            queued.add(whenOwnerFinishes);
        }
        return false;
    }

    /** Releases the work key, but only if {@code identity} still owns it, then drains its queue. */
    public void finish(LayerRunIdentity identity) {
        if (identity == null) return;
        List<Runnable> queued;
        synchronized (this) {
            String key = identity.artifactCacheKey();
            if (!identity.runId.equals(inFlight.get(key))) return;
            inFlight.remove(key);
            queued = deferred.remove(key);
        }
        if (queued == null) return;
        // Run continuations outside the lock: a deferred caller may immediately claim the key again.
        for (Runnable runnable : queued) {
            try {
                runnable.run();
            } catch (Throwable ignored) {
                // One stalled surface must not block the others' continuations.
            }
        }
    }

    public synchronized void clear() {
        inFlight.clear();
        deferred.clear();
    }

    public synchronized int inFlightCount() {
        return inFlight.size();
    }
}
