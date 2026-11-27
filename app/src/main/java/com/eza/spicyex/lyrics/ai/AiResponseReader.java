package com.eza.spicyex.lyrics.ai;

import java.util.List;
import java.util.Map;

/**
 * Turns a provider's raw text into the response's {@code items} array.
 *
 * <p>This is the one place a code fence is stripped, and it exists here rather than in each adapter
 * for exactly that reason: the desktop fork implements the same tolerance twice, once per provider,
 * so a third adapter would be one forgotten line away from rejecting every fenced response. Every
 * adapter hands its text to this reader, so none of them can be the one that forgets.
 *
 * <p>At most one leading and trailing fence, and nothing else. Whitespace drift is tolerated
 * because it changes no meaning; anything beyond that is a contract failure and is reported as one.
 */
public final class AiResponseReader {
    private AiResponseReader() {
    }

    /** Removes at most one leading/trailing code fence. The sole cosmetic tolerance. */
    public static String stripSingleFence(String raw) {
        String trimmed = AiText.trim(raw);
        if (trimmed.length() < 6 || !trimmed.startsWith("```")
                || !trimmed.endsWith("```")) return trimmed;
        int contentStart = 3;
        if (trimmed.regionMatches(true, contentStart, "json", 0, 4)) contentStart += 4;
        int contentEnd = trimmed.length() - 3;
        if (contentStart > contentEnd) return trimmed;
        return AiText.trim(trimmed.substring(contentStart, contentEnd));
    }

    /**
     * @return the raw {@code items} array, still untyped — the validator, not the reader, decides
     *         whether its contents satisfy the contract
     * @throws AiProtocolException {@code invalid_json} or {@code items_not_array}
     */
    public static List<Object> readItems(String raw) {
        Object parsed = AiJson.parseStrict(stripSingleFence(raw));
        Object items = parsed instanceof Map ? ((Map<?, ?>) parsed).get("items") : null;
        if (!(items instanceof List)) throw new AiProtocolException("items_not_array");
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) items;
        return list;
    }

    /**
     * Extracts every already-closed item object from a streamed {@code {"items":[...}} prefix.
     *
     * <p>Only complete {@code {...}} elements inside the {@code items} array are parsed; any
     * trailing half-written object is left alone until more bytes arrive.
     */
    public static List<Object> readPartialItems(String raw) {
        String text = stripLeadingFence(raw);
        List<Object> items = new java.util.ArrayList<>();
        int arrayStart = findItemsArrayStart(text);
        if (arrayStart < 0) return items;

        int i = arrayStart + 1;
        int len = text.length();
        while (i < len) {
            while (i < len && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == ',')) i++;
            if (i >= len || text.charAt(i) == ']') break;
            if (text.charAt(i) != '{') break;
            int end = findObjectEnd(text, i);
            if (end < 0) break;
            try {
                Object parsed = AiJson.parseStrict(text.substring(i, end + 1));
                if (parsed instanceof Map) items.add(parsed);
            } catch (RuntimeException ignored) {
                break;
            }
            i = end + 1;
        }
        return items;
    }

    private static String stripLeadingFence(String raw) {
        String trimmed = AiText.trim(raw);
        if (!trimmed.startsWith("```")) return trimmed;
        int newline = trimmed.indexOf('\n');
        return newline < 0 ? "" : trimmed.substring(newline + 1).trim();
    }

    private static int findItemsArrayStart(String text) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '{') {
                depth++;
                continue;
            }
            if (c == '}') {
                depth--;
                continue;
            }
            if (c == '"') {
                if (depth == 1 && text.startsWith("\"items\"", i)) {
                    int j = i + 7;
                    while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
                    if (j < text.length() && text.charAt(j) == ':') {
                        j++;
                        while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
                        if (j < text.length() && text.charAt(j) == '[') return j;
                    }
                }
                inString = true;
            }
        }
        return -1;
    }

    private static int findObjectEnd(String text, int start) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
