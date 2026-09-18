package com.eza.spicyex.hooks;

import com.eza.spicyex.xposed.XpPackage;
import com.eza.spicyex.xposed.SpotifySymbolResolver;

public abstract class SpotifyHook {
    protected XpPackage lpparm;
    protected SpotifySymbolResolver symbols;

    public void init(XpPackage lpparm, SpotifySymbolResolver symbols) {
        this.lpparm = lpparm;
        this.symbols = symbols;
        hook();
    }

    protected abstract void hook();
}
