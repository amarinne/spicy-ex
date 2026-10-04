package com.eza.spicyex.auto;

import org.junit.Test;
import static org.junit.Assert.*;

public class AutoHookInstallationRegressionTest {
    @Test public void optionalFailureDoesNotPreventNextStageAndKeepsItsDiagnostic() {
        AutoHookInstallation installation = new AutoHookInstallation();
        int[] executions = new int[1];
        assertFalse(installation.attempt("compact-badge", () -> { throw new IllegalStateException("missing badge"); }));
        assertTrue(installation.attempt("weather-legacy", () -> executions[0]++));
        assertEquals(1, executions[0]);
        assertTrue(installation.error("compact-badge").contains("missing badge"));
        assertTrue(installation.summary().contains("weather-legacy=installed"));
    }

    @Test public void partiallyInstalledSurfaceCannotEnableNativeSuppression() {
        AutoHookInstallation installation = new AutoHookInstallation();
        int[] installedHooks = new int[1];
        boolean fullInstalled = installation.attempt("full-slot", () -> {
            installedHooks[0]++;
            throw new IllegalStateException("second entry rejected");
        });
        assertEquals(1, installedHooks[0]);
        assertFalse(fullInstalled);
        assertFalse(fullInstalled && AutoPrototypePolicy.suppressFullProgress(true, true, 300));
        AutoFullBadgeOwnership ownership = new AutoFullBadgeOwnership();
        ownership.enter(new Object(), true, false);
        assertFalse(ownership.suppress(fullInstalled, true));
        ownership.exit();
    }

    @Test public void linkageFailureIsRecordedWithoutAbortingIndependentInstall() {
        AutoHookInstallation installation = new AutoHookInstallation();
        assertFalse(installation.attempt("shared-core", () -> { throw new NoClassDefFoundError("composer"); }));
        assertTrue(installation.error("shared-core").contains("composer"));
        assertTrue(installation.attempt("host-status", () -> { }));
    }

    @Test public void weatherAdjacentNativeThumbnailSurvivesEveryOtherReadyInput() {
        assertFalse(AutoPrototypePolicy.suppressCompactThumbnail(true, true, true));
        assertTrue(AutoPrototypePolicy.suppressCompactThumbnail(true, true, false));
        assertFalse(AutoPrototypePolicy.suppressCompactThumbnail(false, true, false));
        assertFalse(AutoPrototypePolicy.suppressCompactThumbnail(true, false, false));
    }
}
