package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether a response may be believed.
 *
 * <p>All-or-nothing, by design. A chunk is accepted only when every requested id came back exactly
 * once and every returned row satisfies the shape rules; there is no partial acceptance and no
 * count-only fallback. That is what makes reordering a non-issue rather than a failure mode to
 * detect: the mapping travels in the data, so a shuffled response is simply a correct one.
 *
 * <p>Quality is not checked here, and no heuristic is applied to the text. "I'm sorry" is a lyric,
 * so a refusal is recognised from the provider's finish state and never from what the row says.
 */
public final class AiResponseValidator {
    private AiResponseValidator() {
    }

    /** Convenience for the Meaning layer, whose acceptance does not depend on a target script. */
    public static List<AiResponseItem> validate(List<Object> items,
                                                List<AiRequestItem> requested) {
        return validate(items, requested, LayerKind.MEANING, "en");
    }

    /**
     * @param items     the raw {@code items} array from {@link AiResponseReader}
     * @param requested the items actually sent, which define the exact id set owed back
     * @param target    target language for Meaning, target orthography for Sound
     * @throws AiProtocolException with a token naming the rule and, where one applies, the row
     */
    public static List<AiResponseItem> validate(List<Object> items, List<AiRequestItem> requested,
                                                LayerKind layer, String target) {
        if (items == null) throw new AiProtocolException("items_not_array");
        Map<String, AiRequestItem> requestedById = new LinkedHashMap<>();
        if (requested != null) {
            for (AiRequestItem item : requested) requestedById.put(item.id, item);
        }

        Set<String> seen = new HashSet<>();
        List<AiResponseItem> out = new ArrayList<>(items.size());
        for (Object raw : items) {
            if (!(raw instanceof Map)) throw new AiProtocolException("invalid_item");
            Map<?, ?> item = (Map<?, ?>) raw;
            Object id = item.get("id");
            Object text = item.get("t");
            if (!(id instanceof String) || !(text instanceof String)) {
                throw new AiProtocolException("invalid_item");
            }
            String itemId = (String) id;
            String itemText = (String) text;

            if (!requestedById.containsKey(itemId)) {
                throw new AiProtocolException("id_set_mismatch:unexpected", itemId);
            }
            if (!seen.add(itemId)) {
                throw new AiProtocolException("id_set_mismatch:duplicate", itemId);
            }
            AiRequestItem source = requestedById.get(itemId);

            if (AiText.utf8Bytes(itemText) > AiContract.MAX_TRANSLATED_ITEM_BYTES) {
                throw new AiProtocolException("translated_item_oversized", itemId);
            }
            if (AiText.containsForbiddenText(itemText)) {
                throw new AiProtocolException("forbidden_text", itemId);
            }
            if (source.lineClass == AiLineClass.ORDINARY && AiText.trim(itemText).isEmpty()) {
                throw new AiProtocolException("empty_ordinary", itemId);
            }
            if (layer == LayerKind.SOUND && !soundOrthographyAccepts(itemText, target, source.source)) {
                throw new AiProtocolException("target_orthography_mismatch", itemId);
            }
            if (AiText.segmentCount(source.source) != AiText.segmentCount(itemText)) {
                throw new AiProtocolException("delimiter_mismatch", itemId);
            }
            out.add(new AiResponseItem(itemId, itemText));
        }

        if (seen.size() != requestedById.size()) {
            for (Map.Entry<String, AiRequestItem> entry : requestedById.entrySet()) {
                if (!seen.contains(entry.getKey())) {
                    throw new AiProtocolException("id_set_mismatch:missing", entry.getKey());
                }
            }
            throw new AiProtocolException("id_set_mismatch:missing", "unknown");
        }
        return out;
    }

    /**
     * Sound acceptance: is this text written in the orthography that was asked for?
     *
     * <p>Two rules, and the second is the one that matters. Every letter must be either Latin —
     * which stays readable in any target and is how an already-Latin word survives untouched — or
     * in the target script. And when the source actually needed respelling, the answer has to
     * contain the target script somewhere, otherwise a model that echoed the source back would
     * pass. Neither rule judges whether the reading is correct; that is not something structure
     * can tell.
     */
    static boolean soundOrthographyAccepts(String value, String target, String source) {
        if (!AiContract.isKnownOrthography(target)) return false;
        String text = AiText.nz(value);
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            if (AiText.isLatin(cp)) continue;
            if (!AiText.isTargetScript(cp, target)) return false;
        }

        boolean sourceNeedsRespelling = false;
        if (!AiContract.ORTHOGRAPHY_LATIN.equals(target)) {
            String sourceText = AiText.nz(source);
            int j = 0;
            while (j < sourceText.length()) {
                int cp = sourceText.codePointAt(j);
                j += Character.charCount(cp);
                if (Character.isLetter(cp) && !AiText.isLatin(cp)) {
                    sourceNeedsRespelling = true;
                    break;
                }
            }
        }
        return !sourceNeedsRespelling || AiText.containsTargetScript(text, target);
    }
}
