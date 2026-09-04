package com.eza.spicyex.xposed;

/**
 * Package metadata for hooks, replacing the legacy load-package param surface. Only the fields this module
 * reads are carried: target package name and its class loader.
 */
public final class XpPackage {
    private final String packageName;
    private final ClassLoader classLoader;

    public XpPackage(String packageName, ClassLoader classLoader) {
        this.packageName = packageName;
        this.classLoader = classLoader;
    }

    public String packageName() {
        return packageName;
    }

    public ClassLoader classLoader() {
        return classLoader;
    }
}
