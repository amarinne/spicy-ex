package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The plain-text wire for Meaning: numbered lines out, numbered lines back.
 *
 * <p>The model only writes translations. Row identity, segments, and validation stay in the
 * harness: line {@code N} answers the request's {@code N}th item, and the result is handed to
 * {@link AiResponseValidator} in the same {@code {id, t}} shape the JSON contract produced, so
 * repair, targeted re-ask, re-planning, and fallback are unchanged. A strict JSON contract made
 * reasoning models deliberate over ids, hints, and escaping (a 40-row DeepSeek run on 2026-11-19
 * spent 22,578 output tokens for about 816 visible ones) and made schema-enforced decoding slow.
 *
 * <p>Parsing is tolerant where tolerance cannot misplace text: preamble, blank lines, a code
 * fence, Markdown bold around numbers, and wrapped continuation lines. It is strict where a guess
 * could attach text to the wrong row: a number outside the request is ignored, and a number that
 * appears twice drops that row so the runtime re-asks it. A reply in the older JSON
 * {@code {"items":[...]}} shape is still read, so a model that answers in JSON anyway works.
 *
 * <p>Sound keeps the JSON contract; its baseline and orthography rules are a separate migration.
 */
public final class AiTextWire {
    /** Prefix of the line that carries an item's draft or current text under its source line. */
    static final String DRAFT_PREFIX = "draft: ";

    private static final Pattern NUMBERED = Pattern.compile(
            "^\\s*(?:[*_]{1,2})?(\\d{1,4})(?:[*_]{1,2})?\\s*[.):：、](?:[*_]{1,2})?\\s?(.*)$");

    private AiTextWire() {
    }

    /** True for the layers that speak this wire. */
    public static boolean usesText(LayerKind layer) {
        return layer == LayerKind.MEANING;
    }

    /** The user turn for {@code layer}: numbered text for Meaning, the JSON contract otherwise. */
    public static String userTurn(AiProviderRequest request, LayerKind layer) {
        if (request == null) return "";
        return usesText(layer) ? render(request) : request.toJson();
    }

    /** Parses a response for {@code layer} into the validator's raw item list. */
    public static List<Object> readItems(String raw, List<AiRequestItem> requested,
                                         LayerKind layer) {
        return usesText(layer) ? read(raw, requested) : AiResponseReader.readItems(raw);
    }

    /**
     * Renders the request as plain text. Byte-stable for the same request: fixed section order,
     * LF line endings, and one source line per item with any internal line break collapsed.
     */
    static String render(AiProviderRequest request) {
        StringBuilder out = new StringBuilder(256 + request.items.size() * 48);
        AiLyricContext context = request.context;
        if (context.title != null || !context.artists.isEmpty() || context.album != null) {
            out.append("Song: ");
            if (context.title != null) out.append(context.title);
            if (!context.artists.isEmpty()) {
                if (context.title != null) out.append(" — ");
                out.append(String.join(", ", context.artists));
            }
            if (context.album != null) out.append(" (").append(context.album).append(')');
            out.append('\n');
        }
        out.append("Target language: ").append(request.target).append('\n');
        if (!request.instructions.isEmpty()) {
            out.append("\nInstructions:\n").append(request.instructions).append('\n');
        }
        out.append("\nLines:\n");
        for (int i = 0; i < request.items.size(); i++) {
            AiRequestItem item = request.items.get(i);
            out.append(i + 1).append(". ").append(oneLine(item.source)).append('\n');
            if (item.previous != null) {
                out.append("   ").append(DRAFT_PREFIX).append(oneLine(item.previous)).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Maps numbered reply lines onto the requested items.
     *
     * @throws AiProtocolException {@code no_numbered_lines} when nothing in the reply is numbered
     */
    static List<Object> read(String raw, List<AiRequestItem> requested) {
        String body = AiResponseReader.stripSingleFence(raw);
        if (body.startsWith("{")) return AiResponseReader.readItems(body);
        int size = requested == null ? 0 : requested.size();
        Map<Integer, StringBuilder> texts = new LinkedHashMap<>();
        Set<Integer> duplicated = new HashSet<>();
        Integer current = null;
        boolean numbered = false;
        for (String line : body.split("\n", -1)) {
            Matcher match = NUMBERED.matcher(line);
            if (match.matches()) {
                numbered = true;
                int number = Integer.parseInt(match.group(1));
                if (number < 1 || number > size) {
                    current = null;
                    continue;
                }
                if (texts.containsKey(number)) duplicated.add(number);
                texts.put(number, new StringBuilder(AiText.trim(match.group(2))));
                current = number;
                continue;
            }
            String trimmed = AiText.trim(line);
            if (current == null || trimmed.isEmpty() || trimmed.startsWith("```")
                    || trimmed.regionMatches(true, 0, DRAFT_PREFIX.trim(), 0,
                            DRAFT_PREFIX.trim().length())) {
                continue;
            }
            StringBuilder text = texts.get(current);
            if (text.length() > 0) text.append(' ');
            text.append(trimmed);
        }
        if (!numbered) throw new AiProtocolException("no_numbered_lines");

        List<Object> items = new ArrayList<>(texts.size());
        for (Map.Entry<Integer, StringBuilder> entry : texts.entrySet()) {
            if (duplicated.contains(entry.getKey())) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", requested.get(entry.getKey() - 1).id);
            item.put("t", entry.getValue().toString());
            items.add(item);
        }
        return items;
    }

    private static String oneLine(String value) {
        return AiText.collapseWhitespace(AiText.trim(AiText.nz(value)));
    }
}
