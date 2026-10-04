package com.eza.spicyex.hooks;

import com.eza.spicyex.Settings;
import com.eza.spicyex.lyrics.providers.SpicyOrgKeyStore;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpicyOrgCredentialChannelTest {
    @Test public void keyStatusAndRejectedPayloadContainNoKey() throws Exception {
        FakeAndroidContext context = new FakeAndroidContext();
        LyricsHost host = (LyricsHost) Proxy.newProxyInstance(LyricsHost.class.getClassLoader(),
                new Class<?>[]{LyricsHost.class}, (proxy, method, args) -> null);
        File directory = new File(context.getFilesDir(), "spicy-agent");
        assertTrue(directory.mkdir());
        File payload = new File(directory, "spicy-key");
        String secret = "sl_sk_do-not-log-this-key";
        Files.write(payload.toPath(), secret.getBytes(StandardCharsets.UTF_8));
        AgentCommandChannel channel = new AgentCommandChannel(host, context, Runnable::run);
        channel.dispatch("spicy-key set #save");
        assertFalse(payload.exists());
        channel.dispatch("spicy-key status #status");
        channel.dispatch("spicy-key remove confirm #remove");
        String reply = new String(Files.readAllBytes(new File(directory, "out").toPath()),
                StandardCharsets.UTF_8);
        assertFalse(reply.contains(secret));
        assertTrue(reply.contains("SPICY_AGENT error spicy-key client key not saved #save"));
        assertTrue(reply.contains("SPICY_AGENT ok spicy-key configured=false #status"));
        assertTrue(reply.contains("SPICY_AGENT ok spicy-key configured=false #remove"));
        assertTrue(SpicyOrgKeyStore.epoch(context) > 0L);
        assertTrue(AgentCommandChannel.isAdapterOwnedSetting(Settings.SPICY_ORG_CLIENT_KEY));
    }
}
