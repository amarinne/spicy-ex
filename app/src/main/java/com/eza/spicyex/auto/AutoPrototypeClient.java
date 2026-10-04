package com.eza.spicyex.auto;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import com.eza.spicyex.xposed.XpLog;

/** One process-owned explicit connection; Binder calls run on the caller's worker. */
public final class AutoPrototypeClient {
    private final Context context;
    private volatile IAutoPrototype service;
    private boolean binding;
    private long attempted;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (AutoPrototypeClient.this) {
                if (binding) service = IAutoPrototype.Stub.asInterface(binder);
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            synchronized (AutoPrototypeClient.this) { retireBinding(); }
        }
        @Override public void onBindingDied(ComponentName name) {
            service = null;
            synchronized (AutoPrototypeClient.this) { retireBinding(); }
        }
        @Override public void onNullBinding(ComponentName name) {
            synchronized (AutoPrototypeClient.this) { retireBinding(); }
        }
    };
    private void retireBinding() {
        if (binding) {
            binding = false;
            try { context.unbindService(connection); } catch (IllegalArgumentException alreadyRetired) { }
        }
    }
    /** Release the process-owned binding when its publisher becomes dormant. */
    public synchronized void close() {
        service = null;
        retireBinding();
        attempted = 0;
    }
    public AutoPrototypeClient(Context context) { this.context = context; }
    public synchronized Bundle call(String method, Bundle extras) throws android.os.RemoteException {
        if (service != null) {
            try { return service.call(method, extras); }
            catch (android.os.DeadObjectException dead) {
                service = null; retireBinding();
            }
        }
        long now = SystemClock.elapsedRealtime();
        if (!binding && now - attempted > 3000) {
            attempted = now;
            try {
                binding = context.bindService(new Intent().setComponent(new ComponentName("com.eza.spicyex",
                        "com.eza.spicyex.auto.AutoPrototypeService")), connection, Context.BIND_AUTO_CREATE);
                if (!binding) XpLog.log("[SpicyAuto] explicit bind unavailable");
            } catch (RuntimeException error) { XpLog.log("[SpicyAuto] bind " + error.getClass().getSimpleName()); }
        }
        return null;
    }
}
