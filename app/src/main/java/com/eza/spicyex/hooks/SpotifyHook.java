package com.eza.spicyex.hooks;

import com.eza.spicyex.xposed.XpPackage;
import org.luckypray.dexkit.DexKitBridge;

public abstract class SpotifyHook {
    protected XpPackage lpparm;
    protected DexKitBridge bridge;

    public void init(XpPackage lpparm, DexKitBridge bridge) {
        this.lpparm = lpparm;
        this.bridge = bridge;
        hook();
    }

    protected abstract void hook();
}
