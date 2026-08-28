package com.eza.spicyex.lyrics.ai;

import java.nio.charset.StandardCharsets;

/** Conservative encoded-record bound used only for pre-provider durable-space reservation. */
final class AiStorageBudget {
    private static final long JSON_ESCAPE_MULTIPLIER = 6L;
    private static final long CODEC_OVERHEAD_BYTES = 256L * 1024L;

    private AiStorageBudget() {
    }

    static long maxRecordBytes(AiPaidRecord current) {
        long encoded = current == null ? 0L : AiPaidRecordCodec.encode(current)
                .getBytes(StandardCharsets.UTF_8).length;
        long response = multiply(AiContract.MAX_RESPONSE_BYTES, JSON_ESCAPE_MULTIPLIER);
        long request = multiply(AiContract.MAX_REQUEST_BYTES, 2L);
        return add(add(encoded, response), add(request, CODEC_OVERHEAD_BYTES));
    }

    private static long multiply(long value, long factor) {
        if (value <= 0L || factor <= 0L) return 0L;
        return value > Long.MAX_VALUE / factor ? Long.MAX_VALUE : value * factor;
    }

    private static long add(long left, long right) {
        long a = Math.max(0L, left);
        long b = Math.max(0L, right);
        return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b;
    }
}
