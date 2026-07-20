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

import com.eza.hyperglow.bridge.ISpicyLyricBridge;

import de.robv.android.xposed.XposedBridge;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.FileOutputStream;
import java.io.IOException;

final class SpicyLyricBridgePublisher {
    static final int PROTOCOL_VERSION = 1;
    private static final ComponentName BRIDGE_COMPONENT = new ComponentName(
            "com.eza.hyperglow",
            "com.eza.hyperglow.bridge.SpicyLyricBridgeService"
    );
    private static final Uri BRIDGE_URI = Uri.parse(
            "content://com.eza.hyperglow.spicybridge/session"
    );

    private final Context context;
    private final ExecutorService providerExecutor = Executors.newSingleThreadExecutor();
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

    SpicyLyricBridgePublisher(Context context) {
        Context app = context.getApplicationContext();
        this.context = app != null ? app : context;
    }

    synchronized void enable() {
        enabled = true;
        connect();
    }

    private synchronized void connect() {
        if (!enabled) return;
        if (bridge != null || binding || providerFallback) return;
        binding = true;
        try {
            Intent intent = new Intent().setComponent(BRIDGE_COMPONENT);
            if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                binding = false;
                providerFallback = true;
                XposedBridge.log("[SpotifyPlusBridge] bindService returned false");
                publishPendingLocked();
            } else {
                bound = true;
            }
        } catch (Throwable t) {
            binding = false;
            log("bind failed", t);
        }
    }

    synchronized void publish(Bundle state) {
        if (!enabled) return;
        retainedState = new Bundle(state);
        stateReplayState.retainPayload();
        pendingClearProducerId = null;
        if (bridge == null) {
            if (providerFallback) publishPendingLocked(); else connect();
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
            if (providerFallback) publishPendingLocked(); else connect();
            return;
        }
        publishPendingLocked();
    }

    synchronized void publishDocument(Bundle metadata, byte[] compressedDocument) {
        if (!enabled) return;
        if (metadata == null || compressedDocument == null ||
                compressedDocument.length > SpicyLyricBridgeDocumentSerializer.MAX_COMPRESSED_BYTES) return;
        retainedDocumentMetadata = new Bundle(metadata);
        retainedDocument = compressedDocument.clone();
        documentReplayState.retainPayload();
        if (bridge == null) {
            connect();
            return;
        }
        publishPendingDocumentLocked();
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
            providerFallback = true;
            publishPendingProviderLocked();
        }
        bridge = null;
        binding = false;
        providerFallback = false;
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
        if (providerFallback) {
            publishPendingProviderLocked();
            return;
        }
        if (bridge == null) return;
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

    private void publishPendingProviderLocked() {
        long stateRevision = stateReplayState.pendingRevision();
        if (stateRevision != 0L && retainedState != null) {
            Bundle payload = new Bundle();
            payload.putBundle("state", new Bundle(retainedState));
            providerExecutor.execute(() -> callProvider("publish", payload));
            stateReplayState.markPublished(stateRevision);
        } else if (pendingClearProducerId != null) {
            Bundle payload = new Bundle();
            payload.putString("producerId", pendingClearProducerId);
            payload.putLong("generation", pendingClearGeneration);
            providerExecutor.execute(() -> callProvider("clear", payload));
            pendingClearProducerId = null;
        }
    }

    private void callProvider(String method, Bundle payload) {
        try {
            context.getContentResolver().call(BRIDGE_URI, method, null, payload);
            synchronized (this) {
                if (enabled && providerFallback) {
                    providerFallback = false;
                    connect();
                }
            }
        } catch (Throwable t) {
            log("provider call failed", t);
            synchronized (this) {
                if (enabled && providerFallback) {
                    providerFallback = false;
                    connect();
                }
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
        log("bridge disconnected", cause);
        if (enabled) connect();
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
                XposedBridge.log("[SpotifyPlusBridge] connected");
                publishPendingLocked();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (SpicyLyricBridgePublisher.this) {
                bridge = null;
                binding = SpicyBridgeReplayState.shouldAwaitAutomaticReconnect(bound);
                if (!binding && enabled) connect();
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            synchronized (SpicyLyricBridgePublisher.this) {
                bridge = null;
                binding = false;
                unbindLocked();
                if (enabled) connect();
            }
        }
    };

    private static void log(String message, Throwable t) {
        XposedBridge.log("[SpotifyPlusBridge] " + message + ": " + t.getClass().getSimpleName());
    }
}
