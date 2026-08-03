package com.eza.spicyex.diagnostics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure fail-closed setup policy for rooted LSPosed and rootless LSPatch installs. */
public final class SpicySetupCheckPolicy {
    private SpicySetupCheckPolicy() {
    }

    static String installationMode(boolean lspatchMarker, boolean lsposedMarker,
                                   int xposedApiVersion, String... runtimeEvidence) {
        if (lspatchMarker) return "lspatch";
        if (lsposedMarker || hasStrongLsposedEvidence(runtimeEvidence)) return "lsposed";
        if (xposedApiVersion >= MODERN_LSPOSED_API_VERSION) return "lsposed";
        return "xposed_unknown";
    }

    private static boolean hasStrongLsposedEvidence(String[] runtimeEvidence) {
        if (runtimeEvidence == null) return false;
        for (String evidence : runtimeEvidence) {
            if (evidence == null) continue;
            String value = evidence.toLowerCase(java.util.Locale.ROOT);
            if (value.contains("org.lsposed.lspd.")
                    || value.contains("/data/adb/lspd/")
                    || value.contains("/data/adb/modules/zygisk_lsposed/")
                    || value.contains("/data/adb/modules/lsposed/")
                    || value.contains("lspd.dex")) {
                return true;
            }
        }
        return false;
    }

    private static final int MODERN_LSPOSED_API_VERSION = 100;

    static Result resolve(Input input) {
        List<String> failures = new ArrayList<>();
        boolean hardFailure = false;
        if (!input.runtimeHookActive) {
            failures.add("hook_runtime");
            hardFailure = true;
        }
        if (!input.xposedApiAvailable) {
            failures.add("xposed_api");
            hardFailure = true;
        }
        if (!input.spotifyMainProcess) {
            failures.add("spotify_main_process");
            hardFailure = true;
        }
        if (!input.internetPermissionGranted) {
            failures.add("internet_permission");
            hardFailure = true;
        }
        if (!input.requiredFeaturesAvailable) {
            failures.add("required_features");
            hardFailure = true;
        }
        if (input.hyperGlowEnabled && !bridgeReady(input.hyperGlowBridgeStatus)) {
            failures.add("hyperglow_bridge");
        }
        return new Result(
                input.runtimeHookActive,
                input.xposedApiAvailable,
                input.installationMode,
                input.spotifyMainProcess,
                input.moduleRuntimeAvailable,
                input.modulePackageStatus,
                input.internetPermissionGranted,
                input.requiredFeaturesAvailable,
                input.hyperGlowEnabled,
                input.hyperGlowBridgeStatus,
                hardFailure ? "failed" : failures.isEmpty() ? "ready" : "warning",
                failures
        );
    }

    static boolean bridgeReady(String status) {
        return "connected".equals(status) || "provider_connected".equals(status);
    }

    static final class Input {
        final boolean runtimeHookActive;
        final boolean xposedApiAvailable;
        final String installationMode;
        final boolean spotifyMainProcess;
        final boolean moduleRuntimeAvailable;
        final String modulePackageStatus;
        final boolean internetPermissionGranted;
        final boolean requiredFeaturesAvailable;
        final boolean hyperGlowEnabled;
        final String hyperGlowBridgeStatus;

        Input(boolean runtimeHookActive, boolean xposedApiAvailable, String installationMode,
              boolean spotifyMainProcess, boolean moduleRuntimeAvailable,
              String modulePackageStatus, boolean internetPermissionGranted,
              boolean requiredFeaturesAvailable, boolean hyperGlowEnabled,
              String hyperGlowBridgeStatus) {
            this.runtimeHookActive = runtimeHookActive;
            this.xposedApiAvailable = xposedApiAvailable;
            this.installationMode = installationMode;
            this.spotifyMainProcess = spotifyMainProcess;
            this.moduleRuntimeAvailable = moduleRuntimeAvailable;
            this.modulePackageStatus = modulePackageStatus;
            this.internetPermissionGranted = internetPermissionGranted;
            this.requiredFeaturesAvailable = requiredFeaturesAvailable;
            this.hyperGlowEnabled = hyperGlowEnabled;
            this.hyperGlowBridgeStatus = hyperGlowBridgeStatus;
        }
    }

    public static final class Result {
        public final boolean runtimeHookActive;
        public final boolean xposedApiAvailable;
        public final String installationMode;
        public final String rootRequirement = "not_required";
        public final boolean spotifyMainProcess;
        public final boolean moduleRuntimeAvailable;
        public final String modulePackageStatus;
        public final boolean internetPermissionGranted;
        public final boolean requiredFeaturesAvailable;
        public final boolean hyperGlowEnabled;
        public final String hyperGlowBridgeStatus;
        public final String setupState;
        public final List<String> setupFailures;

        Result(boolean runtimeHookActive, boolean xposedApiAvailable, String installationMode,
               boolean spotifyMainProcess, boolean moduleRuntimeAvailable,
               String modulePackageStatus, boolean internetPermissionGranted,
               boolean requiredFeaturesAvailable, boolean hyperGlowEnabled,
               String hyperGlowBridgeStatus, String setupState, List<String> setupFailures) {
            this.runtimeHookActive = runtimeHookActive;
            this.xposedApiAvailable = xposedApiAvailable;
            this.installationMode = installationMode;
            this.spotifyMainProcess = spotifyMainProcess;
            this.moduleRuntimeAvailable = moduleRuntimeAvailable;
            this.modulePackageStatus = modulePackageStatus;
            this.internetPermissionGranted = internetPermissionGranted;
            this.requiredFeaturesAvailable = requiredFeaturesAvailable;
            this.hyperGlowEnabled = hyperGlowEnabled;
            this.hyperGlowBridgeStatus = hyperGlowBridgeStatus;
            this.setupState = setupState;
            this.setupFailures = Collections.unmodifiableList(new ArrayList<>(setupFailures));
        }

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("runtimeHookActive", runtimeHookActive);
            object.addProperty("xposedApiAvailable", xposedApiAvailable);
            object.addProperty("installationMode", installationMode);
            object.addProperty("rootRequirement", rootRequirement);
            object.addProperty("spotifyMainProcess", spotifyMainProcess);
            object.addProperty("moduleRuntimeAvailable", moduleRuntimeAvailable);
            object.addProperty("modulePackageStatus", modulePackageStatus);
            object.addProperty("internetPermissionGranted", internetPermissionGranted);
            object.addProperty("requiredFeaturesAvailable", requiredFeaturesAvailable);
            object.addProperty("hyperGlowEnabled", hyperGlowEnabled);
            object.addProperty("hyperGlowBridgeStatus", hyperGlowBridgeStatus);
            object.addProperty("setupState", setupState);
            JsonArray failures = new JsonArray();
            for (String failure : setupFailures) failures.add(failure);
            object.add("setupFailures", failures);
            return object;
        }

        static Result unknown() {
            return new Result(false, false, "xposed_unknown", false, false,
                    "not_visible_or_missing", false, false, false, "unknown",
                    "warning", Collections.singletonList("requirements_not_checked"));
        }
    }
}
