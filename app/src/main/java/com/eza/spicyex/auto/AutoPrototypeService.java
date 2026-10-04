package com.eza.spicyex.auto;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

/** Explicit Binder transport for hosts that cannot query the module's provider. */
public final class AutoPrototypeService extends Service {
    private final IAutoPrototype.Stub binder = new IAutoPrototype.Stub() {
        @Override public Bundle call(String method, Bundle extras) {
            return AutoPrototypeProvider.instance.call(method, null, extras);
        }
    };
    @Override public IBinder onBind(Intent intent) { return binder; }
}
