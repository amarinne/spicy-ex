package com.eza.spicyex.lyrics.ai;

/**
 * One enumerated row of the document, as the AI protocol sees it.
 *
 * <p>Every enumerable row gets one of these whether or not it is sent, so the document digest and
 * chunk resume stay stable when the send set changes. Row IDs are the mobile fork's own
 * {@code CanonicalRow} identifiers: paid records never travel between forks, so no cross-fork ID
 * scheme is owed, and addressing rows by anything other than a stable ID is never valid.
 */
public final class AiLine {
    public final String id;
    public final AiLineClass lineClass;
    public final AiSendDisposition sendDisposition;
    public final String sourceText;
    /** Layout-only; null when the row carries no role evidence. */
    public final AiVoiceHint voice;
    /**
     * Digest-only compatibility field. It once decided whether an unchanged row was acceptable;
     * acceptance no longer consults it, but removing it from the digest would orphan every paid
     * record already stored.
     */
    public final boolean allowUnchanged;
    /** Existing deterministic or Google output for this row, or null. Sent as {@code p}. */
    public final String baselineText;
    /** {@code "deterministic"} or {@code "google"}, or null when there is no baseline. */
    public final String baselineProvenance;

    public AiLine(String id, AiLineClass lineClass, AiSendDisposition sendDisposition,
                  String sourceText, AiVoiceHint voice, boolean allowUnchanged,
                  String baselineText, String baselineProvenance) {
        this.id = AiText.nz(id);
        this.lineClass = lineClass == null ? AiLineClass.ORDINARY : lineClass;
        this.sendDisposition = sendDisposition == null ? AiSendDisposition.SENT : sendDisposition;
        this.sourceText = AiText.nz(sourceText);
        this.voice = voice;
        this.allowUnchanged = allowUnchanged;
        this.baselineText = baselineText;
        this.baselineProvenance = baselineProvenance;
    }

    /**
     * A row classified from its own text, with the disposition that classification implies.
     * Structural rows are never sent.
     */
    public static AiLine of(String id, String sourceText, AiVoiceHint voice, boolean allowUnchanged) {
        AiLineClass lineClass = AiLineClassifier.classify(sourceText);
        return new AiLine(id, lineClass, dispositionFor(lineClass), sourceText, voice,
                allowUnchanged, null, null);
    }

    /** As {@link #of}, plus the existing local reading this row already has. */
    public static AiLine withBaseline(String id, String sourceText, AiVoiceHint voice,
                                      boolean allowUnchanged, String baselineText,
                                      String baselineProvenance) {
        AiLineClass lineClass = AiLineClassifier.classify(sourceText);
        return new AiLine(id, lineClass, dispositionFor(lineClass), sourceText, voice,
                allowUnchanged, baselineText, baselineProvenance);
    }

    public static AiSendDisposition dispositionFor(AiLineClass lineClass) {
        return lineClass == AiLineClass.STRUCTURAL
                ? AiSendDisposition.STRUCTURAL : AiSendDisposition.SENT;
    }

    public boolean isSent() {
        return sendDisposition == AiSendDisposition.SENT;
    }
}
