package com.eza.spicyex.lyrics.session;

import com.eza.spicyex.lyrics.ScriptClassifier;
import com.eza.spicyex.lyrics.SpicyProcessing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves low-signal rows from bounded passage and deduplicated document evidence. */
public final class ContextLanguageRouter {
    private static final int CONTEXT_RADIUS = 3;

    private ContextLanguageRouter() {
    }

    /**
     * Keeps every direct result authoritative. Context fills terminal uncertainty only when nearby
     * evidence agrees, or when a document aggregate has one unopposed Latin-language result.
     */
    public static List<DetectionResult> resolve(CanonicalBase base, List<DetectionResult> rows,
                                                DetectionResult latinAggregate) {
        Map<String, DetectionResult> evidence = new HashMap<>();
        Set<String> directLatinLanguages = new HashSet<>();
        for (DetectionResult row : rows) {
            evidence.put(row.rowId, row);
            if (row.scriptClass == ScriptClassifier.ScriptClass.LATIN && row.hasLanguage()) {
                directLatinLanguages.add(row.language);
            }
        }

        String aggregateLatin = latinAggregate != null && latinAggregate.hasLanguage()
                ? latinAggregate.language : "";
        if (directLatinLanguages.size() > 1
                || directLatinLanguages.size() == 1
                && !directLatinLanguages.contains(aggregateLatin)) {
            aggregateLatin = "";
        }

        List<DetectionResult> resolved = new ArrayList<>(rows.size());
        // Document-level Han majority: an unsure line inherits the song, so one abstaining row
        // can never surface as a random untransliterated gap. Only confident ja/zh votes count;
        // Latin and Korean rows vote for their own scripts, never for Han.
        String hanWinner = hanMajority(rows);
        for (DetectionResult row : rows) {
            if (row.hasLanguage() || row.status == DetectionStatus.ERROR) {
                resolved.add(row);
                continue;
            }
            int index = base.indexOfRow(row.rowId);
            String language = "";
            if (row.scriptClass == ScriptClassifier.ScriptClass.CHINESE) {
                language = hanWinner;
                if (language.isEmpty() && usefulLetters(row.sourceText) <= 4) {
                    language = cjkContext(base, evidence, index);
                }
            } else if (row.scriptClass == ScriptClassifier.ScriptClass.LATIN) {
                language = matchingNeighbours(base, evidence, index,
                        ScriptClassifier.ScriptClass.LATIN);
                if (language.isEmpty()) language = aggregateLatin;
            }
            resolved.add(language.isEmpty() ? row : DetectionResult.detected(row.rowId,
                    row.sourceText, row.scriptClass, language, 0.8)
                    .withEvidence(DetectionEvidence.CONTEXT));
        }
        return resolved;
    }

    /**
     * Majority Han vote over confident rows. Kana-script rows vote {@code ja} by script evidence,
     * so a Japanese song's kanji-only lines inherit correctly; a tie or no votes yields no winner
     * and callers fall back to passage context instead of guessing.
     */
    private static String hanMajority(List<DetectionResult> rows) {
        int japanese = 0;
        int chinese = 0;
        for (DetectionResult row : rows) {
            if (row == null || !row.hasLanguage()) continue;
            if ("ja".equals(row.language)) japanese++;
            else if ("zh".equals(row.language)) chinese++;
        }
        if (japanese > chinese) return "ja";
        if (chinese > japanese) return "zh";
        return "";
    }

    private static String cjkContext(CanonicalBase base, Map<String, DetectionResult> evidence,
                                      int index) {
        String before = anchor(base, evidence, index, -1, ScriptClassifier.ScriptClass.CHINESE);
        String after = anchor(base, evidence, index, 1, ScriptClassifier.ScriptClass.CHINESE);
        if (!before.isEmpty() && before.equals(after)) return before;

        String hint = SpicyProcessing.toIso2(base.language);
        if (("ja".equals(hint) || "zh".equals(hint))
                && (hint.equals(before) && after.isEmpty()
                    || hint.equals(after) && before.isEmpty())) {
            return hint;
        }
        return "";
    }

    private static String matchingNeighbours(CanonicalBase base,
                                              Map<String, DetectionResult> evidence, int index,
                                              ScriptClassifier.ScriptClass script) {
        String before = anchor(base, evidence, index, -1, script);
        String after = anchor(base, evidence, index, 1, script);
        return !before.isEmpty() && before.equals(after) ? before : "";
    }

    private static String anchor(CanonicalBase base, Map<String, DetectionResult> evidence,
                                 int index, int direction,
                                 ScriptClassifier.ScriptClass expectedScript) {
        if (index < 0) return "";
        for (int distance = 1; distance <= CONTEXT_RADIUS; distance++) {
            int i = index + distance * direction;
            if (i < 0 || i >= base.rows.size()) break;
            CanonicalRow candidate = base.rows.get(i);
            if (candidate == null || candidate.interlude || candidate.text.trim().isEmpty()) break;
            DetectionResult result = evidence.get(candidate.rowId);
            if (result == null || !matchesScript(result.scriptClass, expectedScript)) return "";
            if (result.hasLanguage()) return result.language;
        }
        return "";
    }

    private static boolean matchesScript(ScriptClassifier.ScriptClass actual,
                                         ScriptClassifier.ScriptClass expected) {
        if (expected != ScriptClassifier.ScriptClass.CHINESE) return actual == expected;
        return actual == ScriptClassifier.ScriptClass.CHINESE
                || actual == ScriptClassifier.ScriptClass.JAPANESE;
    }

    private static long usefulLetters(String text) {
        return text == null ? 0 : text.codePoints().filter(Character::isLetter).count();
    }
}
