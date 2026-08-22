package com.eza.spicyex.lyrics.ai;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class AiRuntimeFailureLogTest {

    @Test
    public void reportsOnlyTypeAndOwnedFrameForInitializerFailure() {
        NoClassDefFoundError failure = new NoClassDefFoundError(
                "Could not initialize class okhttp3.internal.Util");
        failure.setStackTrace(new StackTraceElement[]{new StackTraceElement(
                "com.eza.spicyex.lyrics.ai.AiHttp", "client", "AiHttp.java", 82)});

        assertEquals("NoClassDefFoundError:at=AiHttp.client",
                AiRuntimeFailureLog.describe(failure));
    }

    @Test
    public void reportsCauseChainWithoutArbitraryMessageText() {
        IllegalStateException cause = new IllegalStateException("token=must-not-appear");
        ExceptionInInitializerError failure = new ExceptionInInitializerError(cause);
        cause.setStackTrace(new StackTraceElement[0]);
        failure.setStackTrace(new StackTraceElement[0]);

        assertEquals("ExceptionInInitializerError>IllegalStateException",
                AiRuntimeFailureLog.describe(failure));
    }
}
