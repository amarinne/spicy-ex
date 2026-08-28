package com.eza.spicyex.lyrics.ai;

/**
 * Where paid records are kept, as the run loop sees it.
 *
 * <p>An interface rather than a direct call into the durable store, for the same reason the desktop
 * fork injects its cache: the run loop is the part worth testing exhaustively — resume, partial
 * persistence, accounting across attempts — and none of that should require a device, a
 * {@code Context}, or {@code SharedPreferences}. The Android implementation and the in-memory test
 * double answer the same three questions.
 */
public interface AiRecordStore {

    /** Result of claiming enough durable space before one provider dispatch. */
    final class Reservation {
        public enum Status { ADMITTED, FULL, BUSY, UNAVAILABLE }

        public final Status status;
        public final String reason;

        private Reservation(Status status, String reason) {
            this.status = status;
            this.reason = AiText.nz(reason);
        }

        public boolean accepted() {
            return status == Status.ADMITTED;
        }

        public static Reservation admitted() {
            return new Reservation(Status.ADMITTED, "");
        }

        public static Reservation rejected(Status status, String reason) {
            return new Reservation(status, reason);
        }
    }

    /** @return the stored record for this exact question, or null when there is none to trust */
    AiPaidRecord read(AiRunConfig config);

    /** Reserves the maximum encoded record size before the next provider call. */
    Reservation reserve(AiRunConfig config, long maxRecordBytes);

    /**
     * Stores {@code record}, overwriting any earlier state for the same question.
     *
     * @return true when the write is durable. False means the result is still valid and still
     *         displayable, but the owner must not be told it was saved — they will be asked to pay
     *         for it again next time.
     */
    boolean commit(AiRunConfig config, AiPaidRecord record);

    /** Releases this run's capacity claim on every terminal path. */
    void release(AiRunConfig config);

    /** Forgets this question's record. Only ever an explicit owner action. */
    void forget(AiRunConfig config);
}
