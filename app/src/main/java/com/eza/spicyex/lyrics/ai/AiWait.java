package com.eza.spicyex.lyrics.ai;

/**
 * The abortable pause a rate-limit retry serves.
 *
 * <p>A seam rather than a sleep so tests can assert what was waited for without waiting for it, and
 * so a cancelled run stops waiting immediately instead of holding the lane for half a minute.
 */
public interface AiWait {
    /** @throws AiCancelledException if the signal aborts before the wait is over */
    void await(long millis, AiSignal signal);

    AiWait DEFAULT = new AiWait() {
        @Override public void await(long millis, AiSignal signal) {
            if (signal == null) {
                sleep(millis);
                return;
            }
            signal.throwIfAborted();
            try {
                if (signal.awaitAbort(millis)) throw new AiCancelledException(signal.reason());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AiCancelledException("interrupted");
            }
        }

        private void sleep(long millis) {
            try {
                Thread.sleep(Math.max(0L, millis));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AiCancelledException("interrupted");
            }
        }
    };
}
