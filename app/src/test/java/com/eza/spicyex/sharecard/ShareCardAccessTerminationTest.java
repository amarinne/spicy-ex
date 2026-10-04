package com.eza.spicyex.sharecard;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.providers.SpicyOrgAccessState;
import com.eza.spicyex.lyrics.providers.SpicyOrgPolicy;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import org.junit.Test;
import static org.junit.Assert.*;

public class ShareCardAccessTerminationTest {
    private LyricsDocument org() {
        LyricsDocument document = new LyricsDocument();
        document.fetchSource = "spicy_org";
        document.spicyOrgSource = "apple_music";
        document.spicyOrgFetchedAtMs = 1000L;
        return document;
    }

    @Test public void retainedCardSnapshotStopsWorkingAfterConfirmedAccessLoss() {
        FakeAndroidContext context = new FakeAndroidContext();
        LyricsDocument snapshot = org();
        long now = 1000L + SpicyOrgPolicy.REFRESH_AFTER_MS;
        assertTrue(ShareCardPolicy.usable(context, snapshot, now));
        assertTrue(SpicyOrgAccessState.terminate(context,
                SpicyOrgAccessState.beginRequest(context, false), "application_suspended"));
        assertFalse(ShareCardPolicy.usable(context, snapshot, now));
        assertEquals(1000L, snapshot.spicyOrgFetchedAtMs);
        assertTrue(SpicyOrgAccessState.acceptValidatedResponse(context,
                SpicyOrgAccessState.beginRequest(context, true)));
        assertTrue(ShareCardPolicy.usable(context, snapshot, now));
        assertFalse(ShareCardPolicy.usable(context, snapshot, 1000L + SpicyOrgPolicy.RETENTION_MS));
    }

    @Test public void blockedOrgDoesNotChangeDirectProviderOrTrackCards() {
        FakeAndroidContext context = new FakeAndroidContext();
        assertTrue(SpicyOrgAccessState.terminate(context,
                SpicyOrgAccessState.beginRequest(context, false), "key_revoked"));
        for (String source : new String[]{"apple_music_lenerd", "spotify_native_model", "qq", "netease"}) {
            LyricsDocument direct = new LyricsDocument();
            direct.fetchSource = source;
            assertTrue(ShareCardPolicy.usable(context, direct, 1000L + SpicyOrgPolicy.RETENTION_MS));
            assertFalse(ShareCardPolicy.requiresImage(direct));
        }
        assertTrue(ShareCardPolicy.usable(context, null, 1000L));
    }

    @Test public void transientFailureDoesNotBlockExistingOrgSnapshot() {
        FakeAndroidContext context = new FakeAndroidContext();
        assertFalse(SpicyOrgAccessState.terminate(context,
                SpicyOrgAccessState.beginRequest(context, false), "verification_unavailable"));
        assertTrue(ShareCardPolicy.usable(context, org(), 1001L));
    }
}
