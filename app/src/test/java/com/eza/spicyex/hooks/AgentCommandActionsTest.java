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
