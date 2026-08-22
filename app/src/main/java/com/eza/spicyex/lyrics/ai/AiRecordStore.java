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

    /** @return the stored record for this exact question, or null when there is none to trust */
    AiPaidRecord read(AiRunConfig config);

    /**
     * Stores {@code record}, overwriting any earlier state for the same question.
     *
     * @return true when the write is durable. False means the result is still valid and still
     *         displayable, but the owner must not be told it was saved — they will be asked to pay
     *         for it again next time.
     */
    boolean commit(AiRunConfig config, AiPaidRecord record);

    /** Forgets this question's record. Only ever an explicit owner action. */
    void forget(AiRunConfig config);
}
