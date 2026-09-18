package com.eza.spicyex.lyrics.session;

import com.eza.spicyex.lyrics.ScriptClassifier;

/**
 * Language/script detection for one canonical row.
 *
 * <p>Detection is a session concern shared by both render surfaces: it is computed once per
 * canonical row, persisted, and consumed by the processing gates. A row whose language is known
 * never re-enters the model, and a row addressed by {@link #rowId} is validated against its
 * source text before reuse.
 */
public final class DetectionResult {
    public final String rowId;
    public final String sourceText;
    public final ScriptClassifier.ScriptClass scriptClass;
    /** ISO-639-1 language code, lower case; empty unless {@link #status} is DETECTED. */
    public final String language;
    /** Detector confidence in {@code [0, 1]}; 1.0 for script-certain languages. */
    public final double confidence;
    public final DetectionStatus status;
    public final DetectionEvidence evidence;

    public DetectionResult(String rowId, String sourceText,
                           ScriptClassifier.ScriptClass scriptClass, String language,
                           double confidence, DetectionStatus status) {
        this(rowId, sourceText, scriptClass, language, confidence, status,
                status == DetectionStatus.DETECTED ? DetectionEvidence.MODEL : DetectionEvidence.NONE);
    }

    public DetectionResult(String rowId, String sourceText,
                           ScriptClassifier.ScriptClass scriptClass, String language,
                           double confidence, DetectionStatus status, DetectionEvidence evidence) {
        this.rowId = rowId == null ? "" : rowId;
        this.sourceText = sourceText == null ? "" : sourceText;
        this.scriptClass = scriptClass == null ? ScriptClassifier.ScriptClass.OTHER : scriptClass;
        this.language = language == null ? "" : language;
        this.confidence = clamp(confidence);
        this.status = status == null ? DetectionStatus.UNKNOWN : status;
        this.evidence = evidence == null ? DetectionEvidence.NONE : evidence;
    }

    public static DetectionResult detected(String rowId, String sourceText,
                                           ScriptClassifier.ScriptClass scriptClass,
                                           String language, double confidence) {
        return new DetectionResult(rowId, sourceText, scriptClass, language, confidence,
                DetectionStatus.DETECTED);
    }

    public static DetectionResult scriptOnly(String rowId, String sourceText,
                                             ScriptClassifier.ScriptClass scriptClass) {
        return new DetectionResult(rowId, sourceText, scriptClass, "", 0.0,
                DetectionStatus.SCRIPT_ONLY);
    }

    public static DetectionResult unknown(String rowId, String sourceText) {
        return new DetectionResult(rowId, sourceText,
                ScriptClassifier.ScriptClass.OTHER, "", 0.0, DetectionStatus.UNKNOWN);
    }

    /** A transient failure; the row is retried on the next detection load. */
    public static DetectionResult error(String rowId, String sourceText,
                                        ScriptClassifier.ScriptClass scriptClass) {
        return new DetectionResult(rowId, sourceText, scriptClass, "", 0.0,
                DetectionStatus.ERROR);
    }

    /** True when this row carries a language the processing gates can trust. */
    public boolean hasLanguage() {
        return status == DetectionStatus.DETECTED && !language.isEmpty();
    }

    /**
     * True when a stored row may be reused without detector work.
     *
     * <p>Negative results are terminal too: a punctuation-only or genuinely ambiguous row must not
     * be recomputed on every load. Only {@link DetectionStatus#ERROR} rows are retried.
     */
    public boolean isReusable() {
        return status.isTerminal();
    }

    public DetectionResult withRow(String rowId, String sourceText) {
        return new DetectionResult(rowId, sourceText, scriptClass, language, confidence, status, evidence);
    }

    public DetectionResult withEvidence(DetectionEvidence evidence) {
        return new DetectionResult(rowId, sourceText, scriptClass, language, confidence, status, evidence);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || value < 0.0) return 0.0;
        return Math.min(value, 1.0);
    }
}
