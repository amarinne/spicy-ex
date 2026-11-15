package com.eza.spicyex.auto;

import android.os.SystemClock;
import com.eza.spicyex.testsupport.FakeAndroidContext;
import org.junit.Test;
import static org.junit.Assert.*;

public class AutoPrototypeClientTest {
    @Test public void disconnectNoticeDoesNotStartOrRebindTheModule() throws Exception {
        FakeAndroidContext context = new FakeAndroidContext();
        AutoPrototypeClient client = new AutoPrototypeClient(context);
        try {
            SystemClock.setElapsedRealtimeForTest(10000);
            assertNull(client.callIfConnected("host", null));
            assertEquals(0, context.bindServiceCalls);
            client.call("state", null);
            assertEquals(1, context.bindServiceCalls);
            client.close();
            SystemClock.setElapsedRealtimeForTest(20000);
            assertNull(client.callIfConnected("host", null));
            assertEquals(1, context.bindServiceCalls);
            assertEquals(0, context.liveBindings);
        } finally {
            client.close();
            SystemClock.setElapsedRealtimeForTest(1000);
        }
    }
}
