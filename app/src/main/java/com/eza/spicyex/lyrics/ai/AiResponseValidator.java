package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides whether a response may be believed.
 *
 * <p>All-or-nothing, by design. A chunk is accepted only when every requested id came back exactly
 * once and every returned row satisfies the shape rules; there is no partial acceptance and no
 * count-only fallback. That is what makes reordering a non-issue rather than a failure mode to
 * detect: the mapping travels in the data, so a shuffled response is simply a correct one.
 *
 * <p>Multi-segment rows arrive as several request items with derived ids
 * ({@link AiContract#SEGMENT_MARKER}) and are rejoined here in index order, which is how the old
 * "preserve the delimiter count" instruction became a property of the transport instead of an
 * obedience test. Whole-row items keep their delimiter check as a safety net. The id-set integrity
 * argument survives the split: segment ids are derived deterministically from the row id, so an
 * unexpected, duplicate, or missing piece is still a whole-chunk failure — it just names the row.
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
     * One row the response failed, named for the re-ask.
     *
     * <p>{@code token} is the same machine token the strict path would have thrown, so a report
     * says one thing whether the failure was tolerated or fatal.
     */
    public static final class RowFailure {
        public final String rowId;
        public final String token;

        RowFailure(String rowId, String token) {
            this.rowId = rowId;
            this.token = token;
        }
    }

    /**
     * A validation split into what may be kept and what may be re-asked.
     *
     * <p>{@code structural} non-empty means the response cannot be trusted at all — an unexpected
     * or duplicate id breaks the id-set integrity the mapping relies on, and so does an
     * unparseable item. Row-level entries mean exactly those lyric rows failed shape rules while
     * the rest came back sound; the runtime keeps the sound rows and re-asks only these.
     */
    public static final class Partition {
        public final List<AiResponseItem> accepted;
        public final List<RowFailure> failed;
        public final String structural;

        Partition(List<AiResponseItem> accepted, List<RowFailure> failed, String structural) {
            this.accepted = Collections.unmodifiableList(accepted);
            this.failed = Collections.unmodifiableList(failed);
            this.structural = AiText.nz(structural);
        }

        public boolean clean() {
            return structural.isEmpty() && failed.isEmpty();
        }
    }

    /**
     * Strict acceptance: everything valid or nothing.
     *
     * <p>The probe and any other whole-answer consumer go through here. The first failure wins —
     * a structural token if the response itself is untrustworthy, otherwise the earliest row
     * failure — with the exact token text this method has always produced.
     */
    public static List<AiResponseItem> validate(List<Object> items, List<AiRequestItem> requested,
                                                LayerKind layer, String target) {
        Partition partition = partition(items, requested, layer, target);
        if (!partition.structural.isEmpty()) throw new AiProtocolException(partition.structural);
        if (!partition.failed.isEmpty()) {
            RowFailure first = partition.failed.get(0);
            throw new AiProtocolException(first.token, first.rowId);
        }
        return partition.accepted;
    }

    /**
     * @param items     the raw {@code items} array from {@link AiResponseReader}
     * @param requested the items actually sent, which define the exact id set owed back
     * @param target    target language for Meaning, target orthography for Sound
     */
    public static Partition partition(List<Object> items, List<AiRequestItem> requested,
                                      LayerKind layer, String target) {
        if (items == null) return new Partition(new ArrayList<>(), new ArrayList<>(),
                "items_not_array");
        Map<String, AiRequestItem> requestedById = new LinkedHashMap<>();
        if (requested != null) {
            for (AiRequestItem item : requested) requestedById.put(item.id, item);
        }

        // Pass one: identity and shape, per returned item. A multi-segment row arrives as several
        // items, so everything checkable without context happens here. Identity violations are
        // structural; text and script violations belong to their row.
        Map<String, String> textById = new LinkedHashMap<>();
        for (Object raw : items) {
            if (!(raw instanceof Map)) {
                return new Partition(new ArrayList<>(), new ArrayList<>(), "invalid_item");
            }
            Map<?, ?> item = (Map<?, ?>) raw;
            Object id = item.get("id");
            Object text = item.get("t");
            if (!(id instanceof String) || !(text instanceof String)) {
                return new Partition(new ArrayList<>(), new ArrayList<>(), "invalid_item");
            }
            String itemId = (String) id;
            String itemText = (String) text;

            if (!requestedById.containsKey(itemId)) {
                return new Partition(new ArrayList<>(), new ArrayList<>(),
                        "id_set_mismatch:unexpected:" + itemId);
            }
            if (textById.putIfAbsent(itemId, itemText) != null) {
                return new Partition(new ArrayList<>(), new ArrayList<>(),
                        "id_set_mismatch:duplicate:" + itemId);
            }
            textById.put(itemId, itemText);
        }

        // Pass two: reassemble rows in requested order. Segment items are joined with the exact
        // ' / ' delimiter locally, so the count is correct by construction and no model ever has
        // to preserve it. Whole-row items keep the delimiter check as a safety net for any path
        // that still sends joined rows.
        Map<String, List<AiRequestItem>> rows = new LinkedHashMap<>();
        for (AiRequestItem item : requestedById.values()) {
            rows.computeIfAbsent(AiContract.rowIdOf(item.id), key -> new ArrayList<>()).add(item);
        }

        List<AiResponseItem> accepted = new ArrayList<>(rows.size());
        List<RowFailure> failed = new ArrayList<>();
        for (Map.Entry<String, List<AiRequestItem>> row : rows.entrySet()) {
            String rowId = row.getKey();
            List<AiRequestItem> parts = row.getValue();
            boolean plainRow = parts.size() == 1
                    && AiContract.segmentIndexOf(parts.get(0).id) < 0;
            List<String> texts;
            try {
                texts = plainRow
                        ? Collections.singletonList(singleSegmentText(parts.get(0), textById))
                        : joinedSegmentTexts(parts, textById);
            } catch (MissingRow missing) {
                failed.add(new RowFailure(rowId, missing.token));
                continue;
            }
            AiRequestItem representative = parts.get(0);

            RowFailure shapeFailure = rowShapeFailure(representative, texts, layer, target);
            if (shapeFailure != null) {
                failed.add(shapeFailure);
                continue;
            }
            accepted.add(new AiResponseItem(rowId, String.join(" / ", texts)));
        }
        return new Partition(accepted, failed, "");
    }

    /**
     * The per-row shape rules: byte cap, forbidden characters, ordinary-blank, and the Sound
     * orthography check. Applied over the row's segment texts so a pre-split row is judged on its
     * joined meaning while a bad segment still names its row.
     */
    private static RowFailure rowShapeFailure(AiRequestItem representative, List<String> texts,
                                              LayerKind layer, String target) {
        for (String text : texts) {
            if (AiText.utf8Bytes(text) > AiContract.MAX_TRANSLATED_ITEM_BYTES) {
                return new RowFailure(representative.id, "translated_item_oversized");
            }
            if (AiText.containsForbiddenText(text)) {
                return new RowFailure(representative.id, "forbidden_text");
            }
            if (layer == LayerKind.MEANING && echoesVoiceHint(representative, text)) {
                return new RowFailure(representative.id, "voice_hint_echo");
            }
            if (layer == LayerKind.SOUND && !soundOrthographyAccepts(text, target,
                    representative.source)) {
                return new RowFailure(representative.id, "target_orthography_mismatch");
            }
        }
        if (representative.lineClass == AiLineClass.ORDINARY && allBlank(texts)) {
            return new RowFailure(representative.id, "empty_ordinary");
        }
        return null;
    }

    /** Rejects request metadata copied into Meaning text while preserving a genuine same word. */
    private static boolean echoesVoiceHint(AiRequestItem item, String text) {
        String token = AiVoiceHint.tokenOf(item == null ? null : item.voice);
        if (token == null || !token.equalsIgnoreCase(AiText.trim(text))) return false;
        if (token.equalsIgnoreCase(AiText.trim(item.source))) return false;
        return item.previous == null || !token.equalsIgnoreCase(AiText.trim(item.previous));
    }

    private static boolean allBlank(List<String> texts) {
        for (String text : texts) {
            if (!AiText.trim(text).isEmpty()) return false;
        }
        return true;
    }

    /** A row whose pieces did not all come back. Not structural: only this row is untrusted. */
    private static final class MissingRow extends RuntimeException {
        private final String token;

        private MissingRow(String token) {
            super(token);
            this.token = token;
        }
    }

    /** The text of a whole-row item, which must be present and must keep its delimiter count. */
    private static String singleSegmentText(AiRequestItem part, Map<String, String> textById) {
        String text = textById.get(part.id);
        if (text == null) throw new MissingRow("id_set_mismatch:missing");
        if (AiText.segmentCount(part.source) != AiText.segmentCount(text)) {
            throw new MissingRow("delimiter_mismatch");
        }
        return text;
    }

    /**
     * This row's segment texts in index order. A row is owed every segment it was split into; one
     * missing piece means the whole row is missing, because a partially-joined lyric row would be
     * silently wrong rather than visibly failed.
     */
    private static List<String> joinedSegmentTexts(List<AiRequestItem> parts,
                                                   Map<String, String> textById) {
        int[] indexes = new int[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            int segmentIndex = AiContract.segmentIndexOf(parts.get(i).id);
            if (segmentIndex < 0 || segmentIndex >= indexes.length) {
                // A malformed derived id breaks id-set integrity, not just one row.
                throw new AiProtocolException("id_set_mismatch:unexpected", parts.get(i).id);
            }
            indexes[i] = segmentIndex;
        }
        List<String> ordered = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) ordered.add(null);
        for (int i = 0; i < parts.size(); i++) {
            String text = textById.get(parts.get(i).id);
            if (text == null) {
                throw new MissingRow("id_set_mismatch:missing");
            }
            ordered.set(indexes[i], text);
        }
        return ordered;
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
