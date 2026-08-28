package com.eza.spicyex.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable, implementation-neutral Japanese reading-policy evidence contracts. */
public final class JapaneseReadingPolicyModels {
    private JapaneseReadingPolicyModels() {
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(values == null ? new ArrayList<>() : new ArrayList<>(values));
    }

    public static final class CodePointRange {
        public final int start;
        public final int end;

        public CodePointRange(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    public static final class BoundaryEvidence {
        public final int offset;
        public final String kind;
        public final String strength;
        public final String sourceId;

        public BoundaryEvidence(int offset, String kind, String strength, String sourceId) {
            this.offset = offset;
            this.kind = kind == null ? "" : kind;
            this.strength = strength == null ? "" : strength;
            this.sourceId = sourceId;
        }
    }

    public static final class ReadingCandidate {
        public final String id;
        public final String kana;
        public final String source;
        public final String status;

        public ReadingCandidate(String id, String kana, String source, String status) {
            this.id = id == null ? "" : id;
            this.kana = kana == null ? "" : kana;
            this.source = source == null ? "" : source;
            this.status = status == null ? "" : status;
        }
    }

    public static final class ReadingTokenEvidence {
        public final String id;
        public final String surface;
        public final CodePointRange canonicalRange;
        public final String pos1;
        public final String pos2;
        public final String pos3;
        public final String pos4;
        public final String lemma;
        public final String orthographicBase;
        public final String occurrenceReading;
        public final String baseReading;
        public final String pronunciation;
        public final String conjugationType;
        public final String conjugationForm;
        public final List<ReadingCandidate> candidates;

        public ReadingTokenEvidence(String id, String surface, CodePointRange canonicalRange,
                                    String pos1, String pos2,
                                    String pos3, String pos4,
                                    String lemma, String orthographicBase,
                                    String occurrenceReading, String baseReading,
                                    String pronunciation, String conjugationType,
                                    String conjugationForm, List<ReadingCandidate> candidates) {
            this.id = id == null ? "" : id;
            this.surface = surface == null ? "" : surface;
            this.canonicalRange = canonicalRange;
            this.pos1 = pos1;
            this.pos2 = pos2;
            this.pos3 = pos3;
            this.pos4 = pos4;
            this.lemma = lemma;
            this.orthographicBase = orthographicBase;
            this.occurrenceReading = occurrenceReading;
            this.baseReading = baseReading;
            this.pronunciation = pronunciation;
            this.conjugationType = conjugationType;
            this.conjugationForm = conjugationForm;
            this.candidates = immutable(candidates);
        }
    }

    public static final class ProviderReadingEvidence {
        public final String id;
        public final String providerId;
        public final CodePointRange targetRange;
        public final String candidateId;
        public final String kana;
        public final String status;
        public final String reasonId;

        public ProviderReadingEvidence(String id, String providerId, CodePointRange targetRange,
                                       String candidateId, String kana, String status, String reasonId) {
            this.id = id == null ? "" : id;
            this.providerId = providerId == null ? "" : providerId;
            this.targetRange = targetRange;
            this.candidateId = candidateId;
            this.kana = kana;
            this.status = status == null ? "" : status;
            this.reasonId = reasonId == null ? "" : reasonId;
        }
    }

    public static final class TimingOwnershipOverlap {
        public final String timingId;
        public final CodePointRange targetRange;
        public final CodePointRange overlapRange;
        public final long startMs;
        public final long endMs;

        public TimingOwnershipOverlap(String timingId, CodePointRange targetRange,
                                      CodePointRange overlapRange, long startMs, long endMs) {
            this.timingId = timingId == null ? "" : timingId;
            this.targetRange = targetRange;
            this.overlapRange = overlapRange;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    public static final class ReadingDiagnostic {
        public final String id;
        public final String severity;
        public final CodePointRange targetRange;
        public final List<String> evidenceIds;

        public ReadingDiagnostic(String id, String severity, CodePointRange targetRange,
                                 List<String> evidenceIds) {
            this.id = id == null ? "" : id;
            this.severity = severity == null ? "" : severity;
            this.targetRange = targetRange;
            this.evidenceIds = immutable(evidenceIds);
        }
    }

    public static final class ReadingDecision {
        public final String action;
        public final String status;
        public final String reasonId;
        public final String ruleId;
        public final Integer ruleVersion;
        public final CodePointRange targetRange;
        public final String previousCandidateId;
        public final String selectedCandidateId;
        public final String fallbackCandidateId;
        public final String selectedKana;
        public final List<String> evidenceIds;
        public final List<ReadingDiagnostic> diagnostics;

        public ReadingDecision(String action, String status, String reasonId,
                               String ruleId, Integer ruleVersion, CodePointRange targetRange,
                               String previousCandidateId, String selectedCandidateId,
                               String fallbackCandidateId, String selectedKana,
                               List<String> evidenceIds, List<ReadingDiagnostic> diagnostics) {
            this.action = action == null ? "" : action;
            this.status = status == null ? "" : status;
            this.reasonId = reasonId == null ? "" : reasonId;
            this.ruleId = ruleId;
            this.ruleVersion = ruleVersion;
            this.targetRange = targetRange;
            this.previousCandidateId = previousCandidateId;
            this.selectedCandidateId = selectedCandidateId;
            this.fallbackCandidateId = fallbackCandidateId == null ? "" : fallbackCandidateId;
            this.selectedKana = selectedKana == null ? "" : selectedKana;
            this.evidenceIds = immutable(evidenceIds);
            this.diagnostics = immutable(diagnostics);
        }
    }

    public static final class ReadingContext {
        public final String schema = "lyrics-language-lab-japanese-reading-context";
        public final int schemaVersion = 1;
        public final String canonicalText;
        public final String sourceText;
        public final List<ReadingTokenEvidence> tokens;
        public final List<BoundaryEvidence> boundaries;
        public final List<ProviderReadingEvidence> providerEvidence;
        public final List<TimingOwnershipOverlap> timingOwnership;
        public final List<String> capabilities;

        public ReadingContext(String canonicalText, String sourceText,
                              List<ReadingTokenEvidence> tokens,
                              List<BoundaryEvidence> boundaries,
                              List<ProviderReadingEvidence> providerEvidence,
                              List<TimingOwnershipOverlap> timingOwnership,
                              List<String> capabilities) {
            this.canonicalText = canonicalText == null ? "" : canonicalText;
            this.sourceText = sourceText;
            this.tokens = immutable(tokens);
            this.boundaries = immutable(boundaries);
            this.providerEvidence = immutable(providerEvidence);
            this.timingOwnership = immutable(timingOwnership);
            this.capabilities = immutable(capabilities);
        }

        public static ReadingContext empty(String text) {
            return new ReadingContext(text, text, null, null, null, null, null);
        }
    }
}
