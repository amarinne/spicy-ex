package com.eza.spicyex.lyrics.ai;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Assigns a row its {@link AiLineClass}.
 *
 * <p>The rule is intentionally conservative in one direction only. A row is structural or ad-lib
 * when it matches one of the two closed lists below; everything else is ordinary. Proper nouns get
 * no special treatment, and no heuristic tries to guess whether a short line "looks like" filler,
 * because guessing wrong there means a real lyric line is never translated.
 */
public final class AiLineClassifier {
    private static final Set<String> MUSICAL_NOTES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("♪", "♫", "♬")));

    private static final Pattern HEADING = Pattern.compile("^\\[([^\\]]+)\\]$");
    private static final Pattern STRUCTURAL_HEADINGS = Pattern.compile(
            "^(?:intro|verse(?: \\d+)?|pre-chorus|chorus|post-chorus|refrain|hook|bridge"
                    + "|interlude|instrumental|break|solo|outro)$");

    private static final Set<String> ADLIB_TOKENS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("ah", "aah", "eh", "hey", "hm", "hmm", "la", "na", "oh", "ooh", "uh",
                    "woo", "woah", "yeah", "yo")));

    private AiLineClassifier() {
    }

    public static AiLineClass classify(String sourceText) {
        String trimmed = AiText.trim(sourceText);
        if (trimmed.isEmpty() || MUSICAL_NOTES.contains(trimmed)) return AiLineClass.STRUCTURAL;

        java.util.regex.Matcher heading = HEADING.matcher(trimmed);
        if (heading.matches()) {
            String label = AiText.collapseWhitespace(
                    AiText.trim(AiText.lower(AiText.nfkc(heading.group(1)))));
            if (isAscii(label) && STRUCTURAL_HEADINGS.matcher(label).matches()) {
                return AiLineClass.STRUCTURAL;
            }
        }

        String normalized = AiText.trim(
                AiText.stripPunctuationAndSymbols(AiText.lower(AiText.nfkc(trimmed))));
        if (normalized.isEmpty()) return AiLineClass.ORDINARY;
        boolean everyTokenIsAdlib = true;
        int tokens = 0;
        for (String token : splitOnWhitespace(normalized)) {
            tokens++;
            if (!ADLIB_TOKENS.contains(token)) {
                everyTokenIsAdlib = false;
                break;
            }
        }
        if (tokens > 0 && everyTokenIsAdlib) return AiLineClass.ADLIB;
        return AiLineClass.ORDINARY;
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7f) return false;
        }
        return true;
    }

    private static java.util.List<String> splitOnWhitespace(String value) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (AiText.isJsWhitespace(c)) {
                if (token.length() > 0) {
                    out.add(token.toString());
                    token.setLength(0);
                }
                continue;
            }
            token.append(c);
        }
        if (token.length() > 0) out.add(token.toString());
        return out;
    }
}
