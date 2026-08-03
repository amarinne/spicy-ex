package com.eza.spicyex.diagnostics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpicySetupCheckPolicyTest {
    @Test
    public void rootlessLspatchIsReadyWithoutSeparateModulePackage() {
        SpicySetupCheckPolicy.Result result = SpicySetupCheckPolicy.resolve(input(
                "lspatch", "not_visible_or_missing", true, true));

        assertEquals("ready", result.setupState);
        assertEquals("not_required", result.rootRequirement);
        assertTrue(result.setupFailures.isEmpty());
    }

    @Test
    public void ambiguousXposedHostIsReportedWithoutFalseFailure() {
        assertEquals("xposed_unknown",
                SpicySetupCheckPolicy.installationMode(false, false, 99));

        SpicySetupCheckPolicy.Result result = SpicySetupCheckPolicy.resolve(input(
                "xposed_unknown", "present", true, true));

        assertEquals("ready", result.setupState);
        assertTrue(result.setupFailures.isEmpty());
    }

    @Test
    public void lspatchMarkerWinsIfSharedLsposedClassesAlsoExist() {
        assertEquals("lspatch", SpicySetupCheckPolicy.installationMode(
                true, true, 102, "/data/adb/lspd/framework/lspd.dex"));
        assertEquals("lsposed", SpicySetupCheckPolicy.installationMode(false, true, 82));
    }

    @Test
    public void modernLsposedApiIdentifiesRootedHostAfterLspatchCheck() {
        assertEquals("lsposed", SpicySetupCheckPolicy.installationMode(
                false, false, 102));
        assertEquals("lspatch", SpicySetupCheckPolicy.installationMode(
                true, false, 102));
        assertEquals("xposed_unknown", SpicySetupCheckPolicy.installationMode(
                false, false, 99));
    }

    @Test
    public void strongRuntimeLoaderEvidenceIdentifiesLsposed() {
        assertEquals("lsposed", SpicySetupCheckPolicy.installationMode(
                false, false, 82,
                "dalvik.system.PathClassLoader[DexPathList[[dex file "
                        + "\"/data/adb/lspd/framework/lspd.dex\"]]]"));
        assertEquals("lsposed", SpicySetupCheckPolicy.installationMode(
                false, false, 82, "/data/adb/modules/zygisk_lsposed/bin/daemon"));
    }

    @Test
    public void genericXposedOrModuleLoaderEvidenceRemainsUnknown() {
        assertEquals("xposed_unknown", SpicySetupCheckPolicy.installationMode(
                false, false, 82,
                "dalvik.system.PathClassLoader", "/data/app/com.eza.spicyex/base.apk"));
        assertEquals("xposed_unknown", SpicySetupCheckPolicy.installationMode(
                false, false, 82, "/data/app/org.lsposed.manager/base.apk"));
    }

    @Test
    public void missingXposedApiOrInternetFailsSetup() {
        SpicySetupCheckPolicy.Input base = input("lsposed", "present", true, true);
        SpicySetupCheckPolicy.Result result = SpicySetupCheckPolicy.resolve(
                new SpicySetupCheckPolicy.Input(
                        base.runtimeHookActive, false, base.installationMode,
                        base.spotifyMainProcess, base.moduleRuntimeAvailable,
                        base.modulePackageStatus, false, base.requiredFeaturesAvailable,
                        base.hyperGlowEnabled, base.hyperGlowBridgeStatus));

        assertEquals("failed", result.setupState);
        assertTrue(result.setupFailures.contains("xposed_api"));
        assertTrue(result.setupFailures.contains("internet_permission"));
        assertFalse(result.xposedApiAvailable);
    }

    @Test
    public void enabledDisconnectedHyperGlowBridgeIsWarningOnly() {
        SpicySetupCheckPolicy.Result result = SpicySetupCheckPolicy.resolve(input(
                "lsposed", "present", true, false));

        assertEquals("warning", result.setupState);
        assertTrue(result.setupFailures.contains("hyperglow_bridge"));
    }

    private static SpicySetupCheckPolicy.Input input(String mode, String packageStatus,
                                                     boolean internet, boolean bridgeReady) {
        return new SpicySetupCheckPolicy.Input(
                true,
                true,
                mode,
                true,
                true,
                packageStatus,
                internet,
                true,
                true,
                bridgeReady ? "connected" : "disconnected"
        );
    }
}
