package com.eza.spicyex.hooks;

import com.eza.spicyex.lyrics.ai.AiRequestStartResult;
import com.eza.spicyex.lyrics.session.LayerKind;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class AgentCommandActionsTest {
    private AgentCommandChannel channel;
    private File out;
    private final List<Runnable> posted = new ArrayList<>();
    private final Map<String, Object[]> calls = new HashMap<>();
    private String currentUri = "";

    @Before public void setup() {
        LyricsHost host = (LyricsHost) Proxy.newProxyInstance(LyricsHost.class.getClassLoader(),
                new Class<?>[]{LyricsHost.class}, (proxy, method, args) -> {
                    calls.put(method.getName(), args);
                    switch (method.getName()) {
                        case "catalogTrackUri": return currentUri;
                        case "requestAiLyricsLayer": return AiRequestStartResult.NOT_CONFIGURED;
                        case "replayPaidLyricsLayer":
                            ((LyricsHost.CatalogActionCallback) args[1]).onComplete(true, "reused");
                            return null;
                        case "paidLyricsStatus":
                            ((LyricsHost.CatalogActionCallback) args[0]).onComplete(true, "identity-only");
                            return null;
                        case "paidLyricsEntries":
                            ((LyricsHost.CatalogActionCallback) args[0]).onComplete(true, "[]");
                            return null;
                        case "forgetPaidLyricsEntry":
                            ((LyricsHost.CatalogActionCallback) args[1]).onComplete(true, "removed");
                            return null;
                        case "replaySavedMeaning":
                            ((LyricsHost.CatalogActionCallback) args[1]).onComplete(true, "reused");
                            return null;
                        case "deleteCatalogTrack":
                            ((LyricsHost.CatalogActionCallback) args[0]).onComplete(false, "storage refused");
                            return null;
                        default:
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == long.class) return 0L;
                            return null;
                    }
                });
        FakeAndroidContext context = new FakeAndroidContext();
        File directory = new File(context.getFilesDir(), "spicy-agent");
        assertTrue(directory.mkdir());
        out = new File(directory, "out");
        channel = new AgentCommandChannel(host, context, posted::add);
    }

    private void drain() {
        while (!posted.isEmpty()) posted.remove(0).run();
    }

    private String reply() throws Exception {
        return new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void layerRestoreRunsOnMainAndOnlyTouchesTheRequestedLayer() throws Exception {
        channel.dispatch("layer restore meaning #restore");
        assertFalse(calls.containsKey("restoreLyricsLayer"));
        drain();
        assertEquals(LayerKind.MEANING, calls.get("restoreLyricsLayer")[0]);
        assertFalse(calls.containsKey("refreshLyricsLayer"));
        assertEquals("SPICY_AGENT ok layer requested action=restore layer=MEANING #restore\n", reply());
    }

    @Test public void paidRequestRefusalIsAnErrorWithItsOriginalReason() throws Exception {
        channel.dispatch("layer ai SOUND #paid");
        drain();
        assertEquals("SPICY_AGENT error layer not_configured #paid\n", reply());
        assertFalse(calls.containsKey("refreshLyricsLayer"));
    }

    @Test public void cacheOnlyReplayNeverInvokesRefreshOrPaidRequest() throws Exception {
        channel.dispatch("layer replay MEANING #replay");
        assertFalse(calls.containsKey("replayPaidLyricsLayer"));
        drain();
        assertEquals(LayerKind.MEANING, calls.get("replayPaidLyricsLayer")[0]);
        assertFalse(calls.containsKey("refreshLyricsLayer"));
        assertFalse(calls.containsKey("requestAiLyricsLayer"));
        assertEquals("SPICY_AGENT ok layer reused #replay\n", reply());
    }

    @Test public void paidStatusUsesTheReadOnlyStorageOwner() throws Exception {
        channel.dispatch("paid status #status");
        drain();
        assertTrue(calls.containsKey("paidLyricsStatus"));
        assertFalse(calls.containsKey("requestAiLyricsLayer"));
        assertEquals("SPICY_AGENT ok paid identity-only #status\n", reply());
    }

    @Test public void offscreenReplayUsesOnlyTheSavedSourceOwner() throws Exception {
        channel.dispatch("paid replay retained-candidate #saved");
        drain();
        assertEquals("retained-candidate", calls.get("replaySavedMeaning")[0]);
        assertFalse(calls.containsKey("requestAiLyricsLayer"));
        assertFalse(calls.containsKey("selectCatalogCandidate"));
        assertFalse(calls.containsKey("refreshCatalogSource"));
        assertEquals("SPICY_AGENT ok paid reused #saved\n", reply());
    }

    @Test public void exactSavedEntryDeletionDoesNotStartAnotherRequest() throws Exception {
        String key = "paid-v1|" + String.join("", java.util.Collections.nCopies(64, "a"));
        channel.dispatch("paid forget " + key + " confirm #forget");
        assertFalse(calls.containsKey("forgetPaidLyricsEntry"));
        drain();
        assertEquals(key, calls.get("forgetPaidLyricsEntry")[0]);
        assertFalse(calls.containsKey("clearLyricsCache"));
        assertFalse(calls.containsKey("requestAiLyricsLayer"));
        assertFalse(calls.containsKey("refreshLyricsLayer"));
        assertEquals("SPICY_AGENT ok paid removed #forget\n", reply());
    }

    @Test public void savedEntryListingUsesOnlyTheStorageOwner() throws Exception {
        channel.dispatch("paid entries #entries"); drain();
        assertTrue(calls.containsKey("paidLyricsEntries"));
        assertFalse(calls.containsKey("requestAiLyricsLayer"));
        assertEquals("SPICY_AGENT ok paid [] #entries\n", reply());
    }

    @Test public void savedEntryDeletionRejectsMissingConfirmationAndBroadKeys() throws Exception {
        String key = "paid-v1|" + String.join("", java.util.Collections.nCopies(64, "a"));
        for (String args : new String[]{key, "* confirm", "paid-v1|a confirm", key + " confirm extra"}) {
            channel.dispatch("paid forget " + args + " #bad");
        }
        drain();
        assertFalse(calls.containsKey("forgetPaidLyricsEntry"));
        assertFalse(calls.containsKey("clearLyricsCache"));
        assertEquals(4, reply().split("\n").length);
        for (String line : reply().split("\n")) assertTrue(line.startsWith("SPICY_AGENT error"));
    }

    @Test public void deletionRequiresConfirmationEvenForRawCommands() throws Exception {
        channel.dispatch("delete-track spotify:track:old #delete");
        channel.dispatch("remove-candidate candidate #remove");
        channel.dispatch("clear-cache AI #clear");
        drain();
        assertFalse(calls.containsKey("deleteCatalogTrack"));
        assertFalse(calls.containsKey("removeCatalogCandidate"));
        assertFalse(calls.containsKey("clearLyricsCache"));
        assertEquals(3, reply().split("\n").length);
        for (String line : reply().split("\n")) assertTrue(line.startsWith("SPICY_AGENT error"));
    }

    @Test public void confirmationCannotDeleteANewTrackAfterPlaybackChanges() throws Exception {
        currentUri = "spotify:track:new";
        channel.dispatch("delete-track spotify:track:old confirm #delete");
        drain();
        assertFalse(calls.containsKey("deleteCatalogTrack"));
        assertEquals("SPICY_AGENT error delete-track current track changed #delete\n", reply());
    }

    @Test public void catalogDeletionReportsTheSessionOutcomeExactlyOnce() throws Exception {
        currentUri = "spotify:track:current";
        channel.dispatch("delete-track spotify:track:current confirm #delete");
        drain();
        assertTrue(calls.containsKey("deleteCatalogTrack"));
        assertEquals("SPICY_AGENT error delete-track storage refused #delete\n", reply());
    }

    @Test public void unavailableSeekCannotReachThePlaybackTransport() throws Exception {
        channel.dispatch("playback seek 3000 #seek");
        drain();
        assertFalse(calls.containsKey("seekSpotifyTo"));
        assertEquals("SPICY_AGENT error playback accepted=false #seek\n", reply());
    }
}
