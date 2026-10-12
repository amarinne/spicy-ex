package com.eza.spicyex.lyrics.ai;

import static org.junit.Assert.assertSame;

import org.junit.Test;

/** Exception adapters retain the low-level cause while keeping stable machine-readable messages. */
public class AiExceptionCauseTest {
    @Test
    public void cancellationAndProtocolErrorsKeepTheirCauses() {
        Throwable cancellationCause = new InterruptedException("interrupted");
        Throwable protocolCause = new NumberFormatException("malformed number");

        assertSame(cancellationCause,
                new AiCancelledException("interrupted", cancellationCause).getCause());
        assertSame(protocolCause,
                new AiProtocolException("invalid_json", protocolCause).getCause());
    }
}
