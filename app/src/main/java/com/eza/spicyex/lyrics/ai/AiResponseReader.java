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
}
