package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Decides what is sent, in how many calls, and in what order.
 *
 * <p>One call for the whole document is the normal path and chunking is the outlier: splitting a
 * song re-sends the system prompt every time, which spends money on exactly the axis chunking was
 * meant to protect, and it costs the model the cross-line context it needs for recurring motifs.
 * So the planner takes the single call whenever the document fits, and only then falls back to
 * deterministic chunks in enumeration order.
 *
 * <p>Boundaries are a pure function of the rows and the injected model limits. Two runs over the
 * same document produce the same chunks with the same ids, which is what makes a partial result
 * resumable and a repeated request free.
 */
public final class AiChunkPlanner {

    /** Everything one plan depends on. Nothing is read from global state. */
    public static final class Input {
        public List<AiLine> rows = Collections.emptyList();
        /** Target language for Meaning, target orthography for Sound. */
        public String target = "";
        public AiModelLimits model;
        /** Raw user steering; normalized by the planner. */
        public String instructions;
        public LayerKind layer = LayerKind.MEANING;
        public AiLyricContext context;
        /**
         * Latest accepted output per row id for an explicit quality revision, or null for an
         * initial request. When present, every sent row must have an entry: a revision that
         * silently dropped a row would ask the model to re-derive it from nothing.
         */
        public Map<String, String> previousById;
        /** Layered Sound mode sends the existing local reading as {@code p}; AI-only does not. */
        public boolean useSoundBaseline = true;
        /** Meaning refinement mode sends the Google draft as {@code p}. */
        public boolean useMeaningBaseline;
        public boolean baselineRefinement;
    }

    private AiChunkPlanner() {
    }

    public static AiChunkPlan plan(Input input) {
        AiLyricContext context = AiLyricContext.normalize(input.context);
        List<AiLine> rows = input.rows == null ? Collections.<AiLine>emptyList() : input.rows;
        boolean iteration = input.previousById != null;

        if (rows.size() > AiContract.MAX_DOCUMENT_ROWS) throw new AiOversizedException("document_rows");
        int canonicalSourceUtf8Bytes = 0;
        for (AiLine row : rows) canonicalSourceUtf8Bytes += AiText.utf8Bytes(row.sourceText);
        if (canonicalSourceUtf8Bytes > AiContract.MAX_DOCUMENT_SOURCE_BYTES) {
            throw new AiOversizedException("document_bytes");
        }

        List<Entry> sent = new ArrayList<>();
        for (AiLine row : rows) {
            if (!row.isSent()) continue;
            if (AiText.utf8Bytes(row.sourceText) > AiContract.MAX_SOURCE_ITEM_BYTES) {
                throw new AiOversizedException("source_item");
            }
            String previous = input.previousById == null ? null : input.previousById.get(row.id);
            boolean useLayerBaseline = input.layer == LayerKind.SOUND
                    ? input.useSoundBaseline : input.useMeaningBaseline;
            if (previous == null && useLayerBaseline) {
                previous = row.baselineText;
            }
            if (input.previousById != null && previous == null) {
                throw new AiProtocolException("previous_output_missing", row.id);
            }
            if (previous != null && AiText.utf8Bytes(previous) > AiContract.MAX_TRANSLATED_ITEM_BYTES) {
                throw new AiOversizedException("previous_item");
            }
            addSent(sent, row, previous);
        }

        if (sent.isEmpty()) {
            return new AiChunkPlan(AiContract.CHUNK_PLAN_VERSION,
                    Collections.<AiPlannedChunk>emptyList(), rows.size(), canonicalSourceUtf8Bytes);
        }

        int sentBytes = 0;
        for (Entry entry : sent) sentBytes += entry.item.sourceUtf8Bytes();
        if (sent.size() <= AiContract.SINGLE_CALL_MAX_ITEMS
                && sentBytes <= AiContract.SINGLE_CALL_MAX_SOURCE_BYTES
                && estimatedOutputTokens(sentBytes, sent.size(), input.model)
                <= AiContract.SINGLE_CALL_MAX_OUTPUT_TOKENS) {
            try {
                AiPlannedChunk single = createChunk("C0", context, sent, input, iteration);
                return new AiChunkPlan(AiContract.CHUNK_PLAN_VERSION,
                        Collections.singletonList(single), rows.size(), canonicalSourceUtf8Bytes);
            } catch (AiOversizedException tooBigForOneCall) {
                // Falls through to deterministic chunking, which is what the bound is for.
            }
        }

        List<AiPlannedChunk> chunks = new ArrayList<>();
        List<Entry> current = new ArrayList<>();
        int currentBytes = 0;
        for (Entry entry : sent) {
            int itemBytes = entry.item.sourceUtf8Bytes();
            boolean fits = current.size() + 1 <= AiContract.CHUNK_MAX_ITEMS
                    && currentBytes + itemBytes <= AiContract.CHUNK_MAX_SOURCE_BYTES;
            if (fits) {
                List<Entry> candidate = new ArrayList<>(current);
                candidate.add(entry);
                fits = probeFits(candidate, context, input, iteration);
            }
            if (!fits && !current.isEmpty()) {
                chunks.add(createChunk("C" + chunks.size(), context, current, input, iteration));
                current = new ArrayList<>();
                currentBytes = 0;
            }
            current.add(entry);
            currentBytes += itemBytes;
            if (!probeFits(current, context, input, iteration)) {
                throw new AiOversizedException("chunk");
            }
        }
        if (!current.isEmpty()) {
            chunks.add(createChunk("C" + chunks.size(), context, current, input, iteration));
        }
        return new AiChunkPlan(AiContract.CHUNK_PLAN_VERSION, chunks, rows.size(),
                canonicalSourceUtf8Bytes);
    }

    /**
     * Re-plans one ceiling-truncated chunk into deterministic hierarchical children.
     *
     * <p>The tighter output estimate is derived rather than guessed: keep the reasoning allowance
     * each new call must pay, then halve the failed chunk's remaining visible/envelope payload.
     * Greedy enumeration under that bound preserves order and stable resume IDs. A single item is
     * indivisible and returns no children, leaving truncation terminal.
     */
    static List<AiPlannedChunk> replan(Input input, AiPlannedChunk failed) {
        if (failed == null || failed.items.size() < 2) return Collections.emptyList();

        int reasoning = reasoningAllowance(input.model);
        int payload = Math.max(1, failed.estimatedOutputTokens - reasoning);
        int tighterOutputTokens = reasoning + (payload + 1) / 2;
        List<Entry> entries = entriesOf(failed);
        List<AiPlannedChunk> children = new ArrayList<>();
        List<Entry> current = new ArrayList<>();

        for (Entry entry : entries) {
            List<Entry> candidate = new ArrayList<>(current);
            candidate.add(entry);
            AiPlannedChunk candidateChunk = createChunk("probe", failed.context, candidate, input,
                    false);
            if (!current.isEmpty()
                    && candidateChunk.estimatedOutputTokens > tighterOutputTokens) {
                children.add(createChunk(failed.id + "." + children.size(), failed.context,
                        current, input, false));
                current = new ArrayList<>();
            }
            current.add(entry);
        }
        if (!current.isEmpty()) {
            children.add(createChunk(failed.id + "." + children.size(), failed.context,
                    current, input, false));
        }
        return children.size() < 2 ? Collections.<AiPlannedChunk>emptyList() : children;
    }

    private static List<Entry> entriesOf(AiPlannedChunk chunk) {
        List<Entry> entries = new ArrayList<>(chunk.items.size());
        for (AiRequestItem item : chunk.items) {
            entries.add(new Entry(item, chunk.allowUnchangedIds.contains(item.id)));
        }
        return entries;
    }

    /**
     * The probe the boundary rule is defined by: a candidate chunk is only allowed if the request
     * it would produce actually fits the transport bound and the model's limits. Counting items and
     * source bytes alone would let a long steering note or a large baseline push the real request
     * over, which the provider would reject after the call was already paid for.
     */
    private static boolean probeFits(List<Entry> entries, AiLyricContext context, Input input,
                                     boolean iteration) {
        try {
            createChunk("probe", context, entries, input, iteration);
            return true;
        } catch (AiOversizedException tooBig) {
            return false;
        }
    }

    /**
     * Adds one row to the send list, pre-split into one item per {@code " / "} segment.
     *
     * <p>This is what retired the delimiter rule: a model cannot disobey a count it never sees.
     * Segment items carry derived ids ({@link AiContract#segmentId}), the validator rejoins them
     * in order, and everything downstream stays row-addressed. The split is deterministic, so
     * boundaries and cache identity do not drift between runs.
     *
     * <p>The source decides the segmentation, and a baseline that disagrees is dropped for that
     * row rather than allowed to suppress the split. Google output routinely merges or drops
     * {@code " / "} boundaries, so making the split conditional on the baseline agreeing left the
     * Google-draft and layered pipelines still sending joined rows — still required by the
     * validator to preserve a delimiter count, with the sentence that used to ask for it now
     * removed from the prompt. A rule that is enforced but no longer stated is worse than one that
     * is stated: the model has no way to comply. Losing a baseline on those rows costs a refinement
     * and nothing else, and a baseline whose segmentation contradicts the source was poor material
     * for refining anyway.
     */
    private static void addSent(List<Entry> sent, AiLine row, String previous) {
        List<String> sourceSegments = AiText.splitSegments(row.sourceText);
        List<String> previousSegments = previous == null ? null
                : AiText.splitSegments(previous);
        if (previousSegments != null && previousSegments.size() != sourceSegments.size()) {
            previousSegments = null;
            previous = null;
        }
        boolean splits = sourceSegments.size() > 1;
        if (!splits) {
            sent.add(new Entry(new AiRequestItem(row.id, row.lineClass, row.voice,
                    row.sourceText, previous), row.allowUnchanged));
            return;
        }
        for (int index = 0; index < sourceSegments.size(); index++) {
            sent.add(new Entry(new AiRequestItem(AiContract.segmentId(row.id, index),
                    row.lineClass, row.voice, sourceSegments.get(index),
                    previousSegments == null ? null : previousSegments.get(index)),
                    row.allowUnchanged));
        }
    }

    private static AiPlannedChunk createChunk(String id, AiLyricContext context,
                                              List<Entry> entries, Input input, boolean iteration) {
        List<AiRequestItem> items = new ArrayList<>(entries.size());
        List<String> allowUnchangedIds = new ArrayList<>();
        int sourceUtf8Bytes = 0;
        for (Entry entry : entries) {
            items.add(entry.item);
            if (entry.allowUnchanged) allowUnchangedIds.add(entry.item.id);
            sourceUtf8Bytes += entry.item.sourceUtf8Bytes();
        }
        String instructions = AiContract.normalizeSteering(input.instructions);
        String json = new AiProviderRequest(context, input.target, instructions, items).toJson();
        String systemPrompt =
                AiContract.buildSystemPrompt(input.layer, input.target, false, iteration,
                        input.baselineRefinement);

        int requestBytes = AiText.utf8Bytes(systemPrompt) + AiText.utf8Bytes(json);
        int estimatedOutputTokens = estimatedOutputTokens(sourceUtf8Bytes, items.size(),
                input.model);
        int estimatedInputTokens = ceilHalf(requestBytes);
        AiModelLimits model = input.model;
        if (requestBytes > AiContract.MAX_REQUEST_BYTES
                || (model != null && estimatedInputTokens > model.inputTokenLimit)
                || (model != null && estimatedOutputTokens > model.outputTokenLimit)) {
            throw new AiOversizedException("request");
        }
        return new AiPlannedChunk(id, context, instructions, items, allowUnchangedIds, json,
                sourceUtf8Bytes, estimatedInputTokens, estimatedOutputTokens);
    }

    /**
     * The output-token estimator: half the source bytes, rounded up, plus a reasoning headroom
     * term. Deliberately crude and tokenizer-independent, so the same document plans identically
     * on every model and the estimate changes only when the chunk plan version does.
     *
     * <p>The visible-output half ({@link #ceilHalf}) and the reasoning allowance
     * ({@link AiContract#REASONING_OUTPUT_ALLOWANCE_TOKENS}, replaced by a probe-measured value
     * when one exists) are separate terms on purpose: they fail differently, they cost
     * differently, and keeping them apart keeps each one reviewable on its own.
     */
    static int ceilHalf(int bytes) {
        return (bytes + 1) / 2;
    }

    /** The full output estimate for {@code sourceUtf8Bytes} against the planned model. */
    static int estimatedOutputTokens(int sourceUtf8Bytes, AiModelLimits model) {
        return estimatedOutputTokens(sourceUtf8Bytes, 0, model);
    }

    /**
     * @param itemCount rows in the response, each of which costs its own JSON envelope
     */
    static int estimatedOutputTokens(int sourceUtf8Bytes, int itemCount, AiModelLimits model) {
        return ceilHalf(sourceUtf8Bytes)
                + Math.max(0, itemCount) * AiContract.RESPONSE_ITEM_OVERHEAD_TOKENS
                + reasoningAllowance(model);
    }

    /**
     * The reasoning allowance in effect for {@code model}: the measured value when the descriptor
     * carries one from the probe, otherwise the contract default.
     */
    static int reasoningAllowance(AiModelLimits model) {
        if (model instanceof AiModelDescriptor) {
            int measured = ((AiModelDescriptor) model).reasoningAllowanceTokens;
            if (measured >= 0) return Math.min(measured, AiContract.MAX_CONFIGURED_OUTPUT_TOKENS);
        }
        return AiContract.REASONING_OUTPUT_ALLOWANCE_TOKENS;
    }

    private static final class Entry {
        final AiRequestItem item;
        final boolean allowUnchanged;

        Entry(AiRequestItem item, boolean allowUnchanged) {
            this.item = item;
            this.allowUnchanged = allowUnchanged;
        }
    }
}
