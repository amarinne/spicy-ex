package com.eza.spicyex.lyrics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SpicyVersionProbeStateTest {
    @Test
    public void extVersionBodyMatchesQueryEnvelope() {
        assertEquals(
                "{\"queries\":[{\"operation\":\"ext_version\"}],\"client\":{\"version\":\"6.1.1\"}}",
                SpicyVersionProbeState.buildExtVersionQueryBody("6.1.1"));
    }

    @Test
    public void parseLatestVersionFromStringData() {
        String raw = "{\"queries\":[{\"result\":{\"data\":\"6.2.0\"}}]}";

        assertEquals("6.2.0", SpicyVersionProbeState.parseLatestVersion(raw));
    }

    @Test
    public void parseLatestVersionFromObjectData() {
        String raw = "{\"queries\":[{\"result\":{\"data\":{\"latestVersion\":\"6.10.0\"}}}]}";

        assertEquals("6.10.0", SpicyVersionProbeState.parseLatestVersion(raw));
    }

    @Test
    public void parseLatestVersionReturnsEmptyForInvalidPayload() {
        assertEquals("", SpicyVersionProbeState.parseLatestVersion("{\"queries\":[{\"result\":{\"data\":\"ok\"}}]}"));
        assertEquals("", SpicyVersionProbeState.parseLatestVersion("not-json"));
    }

    @Test
    public void versionComparisonUsesNumericParts() {
        assertTrue(SpicyVersionProbeState.isNewerVersion("6.10.0", "6.2.0"));
        assertTrue(SpicyVersionProbeState.isNewerVersion("6.1.2", "6.1.1"));
        assertFalse(SpicyVersionProbeState.isNewerVersion("6.1.1", "6.1.1"));
        assertFalse(SpicyVersionProbeState.isNewerVersion("6.1.0", "6.1.1"));
        assertFalse(SpicyVersionProbeState.isNewerVersion("", "6.1.1"));
    }
}
