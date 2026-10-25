package com.eza.spicyex.hooks;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;

import com.eza.hyperglow.bridge.ISpicyLyricBridge;
import com.eza.spicyex.Diagnostics;

import com.eza.spicyex.xposed.XpLog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.FileOutputStream;
import java.io.IOException;

final class SpicyLyricBridgePublisher {
    static final int PROTOCOL_VERSION = 1;
    /**
     * Rest between attempts against a bridge that keeps refusing: a bind that returns false or
     * throws (the host cannot see the companion package) and a failed provider call both start
     * this window. Every new publication demand after the window may attempt once more, whatever
     * earlier attempts achieved: a provider success cannot strand the binder retry. Normal clear
     * demands ride the same cadence, at most one per window; the disable clear ignores it.
     */
    private static final long RETRY_BACKOFF_MS = 30_000L;
    private static final ComponentName BRIDGE_COMPONENT = new ComponentName(
            "com.eza.hyperglow",
            "com.eza.hyperglow.bridge.SpicyLyricBridgeService"
    );
    private static final Uri BRIDGE_URI = Uri.parse(
            "content://com.eza.hyperglow.spicybridge/session"
    );

    private final Context context;
    private final java.util.concurrent.ThreadPoolExecutor providerExecutor =
            new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(2));
    /** A staged provider payload stamped with the enable lifetime that queued it. */
    private static final class ProviderEntry {
        final String method;
        final Bundle payload;
        final int enableGeneration;

        ProviderEntry(String method, Bundle payload, int enableGeneration) {
            this.method = method;
            this.payload = payload;
            this.enableGeneration = enableGeneration;
        }
    }

    private final java.util.ArrayList<ProviderEntry> pendingProvider = new java.util.ArrayList<>();
    private boolean providerDrainQueued;
    private ISpicyLyricBridge bridge;
    private Bundle retainedState;
    private String pendingClearProducerId;
    private long pendingClearGeneration;
    private final SpicyBridgeReplayState stateReplayState = new SpicyBridgeReplayState();
    private final SpicyBridgeReplayState documentReplayState = new SpicyBridgeReplayState();
    private Bundle retainedDocumentMetadata;
    private byte[] retainedDocument;
    private boolean binding;
    private boolean bound;
    private boolean providerFallback;
    private boolean enabled;
    /** Enable lifetime of queued work; a completion from an older one cannot steer a new one. */
    private int enableGeneration;
    /** Earliest time another attempt may run; open at zero. Set whenever an attempt fails. */
    private long retryAfterMs;
    /** When a normal clear last went out over the provider; zero until the first one does. */
    private long lastProviderClearSentMs;
    SpicyLyricBridgePublisher(Context context) {
        Context app = context.getApplicationContext();
        this.context = app != null ? app : context;
    }

    synchronized void enable() {
        enabled = true;
        enableGeneration++;
        // A deliberate enable is its own epsilon: retry the bind immediately and leave any stale
        // refusal behind — the new lifetime is not bound by an old one's window.
        providerFallback = false;
        retryAfterMs = 0L;
        Diagnostics.setHyperGlowBridgeStatus("connecting");
        Diagnostics.event("hyperglow_bridge", "connection_state",
                Diagnostics.context("status", "connecting"));
        connect();
    }

    private synchronized void connect() {
        if (!enabled) return;
        if (bridge != null || binding || providerFallback) return;
        if (SystemClock.elapsedRealtime() < retryAfterMs) return;
        if (context.getApplicationContext() == null) {
            // The hook starts inside Application.attach, before the framework installs the
            // application context. LoadedApk keys a bound connection by the outer context it
            // sees at bind time, so a bind issued here would be registered under the attaching
            // context; the later unbind looks under the application, misses, and the connection
            // leaks past toggle-off. The next publication demand after attach opens it instead.
            return;
        }
        binding = true;
        try {
            Intent intent = new Intent().setComponent(BRIDGE_COMPONENT);
            if (context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                bound = true;
            } else {
                bindAttemptFailed(null);
            }
        } catch (Throwable t) {
            bindAttemptFailed(t);
        }
    }

    /**
     * One refused or throwing bind: report the provider fallback once, flush the retained
     * payloads through it, then hold the next attempt until the backoff window expires.
     */
    private synchronized void bindAttemptFailed(Throwable cause) {
        binding = false;
        providerFallback = true;
        if (cause == null) {
            XpLog.log("[SpotifyPlusBridge] bindService returned false");
        } else {
            log("bind failed", cause);
        }
        Diagnostics.setHyperGlowBridgeStatus("provider_fallback");
        Diagnostics.event("hyperglow_bridge", "connection_state",
                Diagnostics.context("status", "provider_fallback"));
        publishPendingLocked();
        retryAfterMs = SystemClock.elapsedRealtime() + RETRY_BACKOFF_MS;
    }

    /**
     * After a refused cycle the bridge is not tapped again on its own; the next publication
     * demand reopens the attempt when the window allows it. The window alone decides, so a
     * provider success cannot strand the binder retry after an earlier refusal.
     */
    private synchronized void maybeRetryConnect() {
        if (bridge != null || binding) return;
        if (SystemClock.elapsedRealtime() < retryAfterMs) return;
        providerFallback = false;
        connect();
    }

    synchronized void publish(Bundle state) {
        if (!enabled) return;
        retainedState = new Bundle(state);
        stateReplayState.retainPayload();
        pendingClearProducerId = null;
        if (bridge == null) {
            maybeRetryConnect();
            publishPendingLocked();
            return;
        }
        publishPendingLocked();
    }

    synchronized void clear(String producerId, long generation) {
        clearRetainedStateLocked();
        pendingClearProducerId = producerId;
        pendingClearGeneration = generation;
        clearRetainedDocumentLocked();
        if (bridge == null) {
            maybeRetryConnect();
            publishPendingLocked();
            return;
        }
        publishPendingLocked();
    }

    synchronized void publishDocument(Bundle metadata, byte[] compressedDocument) {
        if (!enabled) {
            documentSkipped("publisher_disabled", 0);
            return;
        }
        if (metadata == null || compressedDocument == null ||
                compressedDocument.length > SpicyLyricBridgeDocumentSerializer.MAX_COMPRESSED_BYTES) {
            documentSkipped(metadata == null ? "metadata_missing"
                    : compressedDocument == null ? "payload_missing" : "payload_oversized",
                    compressedDocument == null ? 0 : compressedDocument.length);
            return;
        }
        retainedDocumentMetadata = new Bundle(metadata);
        retainedDocument = compressedDocument.clone();
        documentReplayState.retainPayload();
        if (bridge == null) {
            maybeRetryConnect();
            return;
        }
        publishPendingDocumentLocked();
    }

    private static void documentSkipped(String reason, int bytes) {
        Diagnostics.event("hyperglow_bridge", "document_skipped",
                Diagnostics.context("reason", reason, "bytes", String.valueOf(bytes)));
    }

    synchronized void clearAndDisconnect(String producerId, long generation) {
        enabled = false;
        clearRetainedStateLocked();
        pendingClearProducerId = producerId;
        pendingClearGeneration = generation;
        clearRetainedDocumentLocked();
        if (bridge != null) {
            publishPendingLocked();
        } else {
            // The disable clear goes out over the provider immediately, past the one-clear-per-
            // window cadence: a consumer's view of this producer ends when the setting does.
            publishPendingProviderLocked(true);
        }
        bridge = null;
        binding = false;
        providerFallback = false;
        retryAfterMs = 0L;
        Diagnostics.setHyperGlowBridgeStatus("disabled");
        Diagnostics.event("hyperglow_bridge", "connection_state",
                Diagnostics.context("status", "disabled"));
        unbindLocked();
    }

    private void publishPendingDocumentLocked() {
        long revision = documentReplayState.pendingRevision();
        if (bridge == null || revision == 0L
                || retainedDocumentMetadata == null || retainedDocument == null) return;
        ParcelFileDescriptor[] pipe = null;
        try {
            pipe = ParcelFileDescriptor.createPipe();
            ParcelFileDescriptor readSide = pipe[0];
            ParcelFileDescriptor writeSide = pipe[1];
            bridge.publishDocument(new Bundle(retainedDocumentMetadata), readSide);
            readSide.close();
            byte[] payload = retainedDocument;
            providerExecutor.execute(() -> writeDocument(writeSide, payload));
            documentReplayState.markPublished(revision);
        } catch (Throwable t) {
            if (pipe != null) {
                try { pipe[0].close(); } catch (IOException ignored) {}
                try { pipe[1].close(); } catch (IOException ignored) {}
            }
            log("document publish failed", t);
            if (t instanceof RemoteException) handleDisconnectLocked(t);
        }
    }

    private void clearRetainedDocumentLocked() {
        retainedDocumentMetadata = null;
        retainedDocument = null;
        documentReplayState.clearPayload();
    }

    private void clearRetainedStateLocked() {
        retainedState = null;
        stateReplayState.clearPayload();
    }

    private void publishPendingLocked() {
        // No binder means the provider path carries everything retained, window permitting or
        // not: a provider success leaves no fallback flag, and state must not strand behind it.
        if (bridge == null) {
            publishPendingProviderLocked(false);
            return;
        }
        try {
            long stateRevision = stateReplayState.pendingRevision();
            if (stateRevision != 0L && retainedState != null) {
                bridge.publishState(new Bundle(retainedState));
                stateReplayState.markPublished(stateRevision);
            } else if (pendingClearProducerId != null) {
                bridge.clearState(pendingClearProducerId, pendingClearGeneration);
                pendingClearProducerId = null;
            }
            publishPendingDocumentLocked();
        } catch (RemoteException e) {
            handleDisconnectLocked(e);
        }
    }

    private void publishPendingProviderLocked(boolean force) {
        long stateRevision = stateReplayState.pendingRevision();
        if (stateRevision != 0L && retainedState != null) {
            // A recent refusal holds provider attempts off the heartbeat, but the newest state
            // stays retained and rides the next publication demand after the window expires.
            if (SystemClock.elapsedRealtime() < retryAfterMs) return;
            Bundle payload = new Bundle();
            payload.putBundle("state", new Bundle(retainedState));
            queueProvider("publish", payload);
            stateReplayState.markPublished(stateRevision);
        } else if (pendingClearProducerId != null) {
            // The no-track sample cadence keeps demanding clears, so a normal clear goes out at
            // most once per window; the forced disable clear ignores that cadence.
            long sentAtMs = lastProviderClearSentMs;
            long now = SystemClock.elapsedRealtime();
            if (!force && sentAtMs != 0L && now - sentAtMs < RETRY_BACKOFF_MS) return;
            lastProviderClearSentMs = now;
            Bundle payload = new Bundle();
            payload.putString("producerId", pendingClearProducerId);
            payload.putLong("generation", pendingClearGeneration);
            queueProvider("clear", payload);
            pendingClearProducerId = null;
        }
    }

    /**
     * Stage one provider payload: a kind replaces its older pending entry in place, so at most
     * one state and one clear wait, and a queued clear keeps its place ahead of a later state.
     * Entries keep the enable lifetime that queued them; completions from a past lifetime
     * cannot steer the current one.
     */
    private synchronized void queueProvider(String method, Bundle payload) {
        ProviderEntry entry = new ProviderEntry(method, payload, enableGeneration);
        boolean replaced = false;
        for (int i = 0; i < pendingProvider.size(); i++) {
            if (pendingProvider.get(i).method.equals(method)) {
                pendingProvider.set(i, entry);
                replaced = true;
                break;
            }
        }
        if (!replaced) pendingProvider.add(entry);
        if (providerDrainQueued) return;
        providerDrainQueued = true;
        try {
            providerExecutor.execute(() -> {
                while (true) {
                    ProviderEntry next;
                    synchronized (SpicyLyricBridgePublisher.this) {
                        if (pendingProvider.isEmpty()) {
                            providerDrainQueued = false;
                            return;
                        }
                        next = pendingProvider.remove(0);
                        // A state publication observed after disable is dropped: the disable
                        // path queues its own clear, so the consumer never sees a late state.
                        if (!enabled && "publish".equals(next.method)) continue;
                    }
                    callProvider(next.method, next.payload, next.enableGeneration);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            providerDrainQueued = false;
            // The staged entries survive; the next publication demand retries the send.
        }
    }

    private void callProvider(String method, Bundle payload, int queuedGeneration) {
        try {
            context.getContentResolver().call(BRIDGE_URI, method, null, payload);
            synchronized (this) {
                if (!enabled || queuedGeneration != enableGeneration) return;
                Diagnostics.setHyperGlowBridgeStatus("provider_connected");
                Diagnostics.event("hyperglow_bridge", "publication_result",
                        Diagnostics.context("result", "success", "status", "provider"));
                if (providerFallback) {
                    providerFallback = false;
                    connect();
                }
            }
        } catch (Throwable t) {
            synchronized (this) {
                if (!enabled || queuedGeneration != enableGeneration) return;
                log("provider call failed", t);
                Diagnostics.setHyperGlowBridgeStatus("provider_failed");
                Diagnostics.event("hyperglow_bridge", "publication_result", t,
                        Diagnostics.context("result", "error", "status", "provider"));
                // The answer to a failed provider call is a held attempt, not an immediate
                // rebind: the old path re-armed the bind/provider cycle from every heartbeat.
                providerFallback = true;
                retryAfterMs = SystemClock.elapsedRealtime() + RETRY_BACKOFF_MS;
            }
        }
    }

    private void writeDocument(ParcelFileDescriptor destination, byte[] payload) {
        try (ParcelFileDescriptor fd = destination;
             FileOutputStream output = new FileOutputStream(fd.getFileDescriptor())) {
            output.write(payload);
        } catch (Throwable t) {
            log("document pipe failed", t);
        }
    }

    private void handleDisconnectLocked(Throwable cause) {
        bridge = null;
        binding = false;
        unbindLocked();
        if (enabled) {
            log("bridge disconnected", cause);
            Diagnostics.setHyperGlowBridgeStatus("disconnected");
            Diagnostics.event("hyperglow_bridge", "connection_state", cause,
                    Diagnostics.context("status", "disconnected"));
            connect();
        }
    }

    private void unbindLocked() {
        if (!bound) return;
        try {
            context.unbindService(connection);
        } catch (Throwable ignored) {
        }
        bound = false;
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (SpicyLyricBridgePublisher.this) {
                binding = false;
                if (!enabled) {
                    unbindLocked();
                    return;
                }
                stateReplayState.onConnectionOpened();
                documentReplayState.onConnectionOpened();
                bridge = ISpicyLyricBridge.Stub.asInterface(service);
                Diagnostics.setHyperGlowBridgeStatus("connected");
                Diagnostics.event("hyperglow_bridge", "connection_state",
                        Diagnostics.context("status", "connected"));
                XpLog.log("[SpotifyPlusBridge] connected");
                publishPendingLocked();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (SpicyLyricBridgePublisher.this) {
                bridge = null;
                if (enabled) {
                    Diagnostics.setHyperGlowBridgeStatus("disconnected");
                    XpLog.log("[SpotifyPlusBridge] service disconnected, rebinding");
                }
                binding = false;
                unbindLocked();
                if (enabled) connect();
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            synchronized (SpicyLyricBridgePublisher.this) {
                bridge = null;
                if (enabled) {
                    XpLog.log("[SpotifyPlusBridge] binding died, rebinding");
                }
                binding = false;
                unbindLocked();
                if (enabled) connect();
            }
        }
    };

    private static void log(String message, Throwable t) {
        XpLog.log("[SpotifyPlusBridge] " + message + ": " + t.getClass().getSimpleName());
    }
}
