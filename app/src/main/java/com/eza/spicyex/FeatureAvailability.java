package com.eza.spicyex;

public final class FeatureAvailability {
    private FeatureAvailability() {
    }

    /**
     * The ambient background is an AGSL {@code RuntimeShader}, which is API 33+. Unlike the flags
     * above this is a device limit, not a build flavour one, so it can never become true on an
     * older device — pre-33 devices get no animated background at all rather than a lesser mimic.
     */
    public static boolean animatedBackgroundAvailable() {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU;
    }
}
