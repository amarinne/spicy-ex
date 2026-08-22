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
            sent.add(new Entry(new AiRequestItem(row.id, row.lineClass, row.voice, row.sourceText,
                    previous), row.allowUnchanged));
        }

        if (sent.isEmpty()) {
            return new AiChunkPlan(AiContract.CHUNK_PLAN_VERSION,
                    Collections.<AiPlannedChunk>emptyList(), rows.size(), canonicalSourceUtf8Bytes);
        }

        int sentBytes = 0;
        for (Entry entry : sent) sentBytes += entry.item.sourceUtf8Bytes();
        if (sent.size() <= AiContract.SINGLE_CALL_MAX_ITEMS
                && sentBytes <= AiContract.SINGLE_CALL_MAX_SOURCE_BYTES
                && ceilHalf(sentBytes) <= AiContract.SINGLE_CALL_MAX_OUTPUT_TOKENS) {
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
        int estimatedOutputTokens = ceilHalf(sourceUtf8Bytes);
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
     * The output-token estimator: half the source bytes, rounded up. Deliberately crude and
     * tokenizer-independent, so the same document plans identically on every model and the estimate
     * changes only when the chunk plan version does.
     */
    static int ceilHalf(int bytes) {
        return (bytes + 1) / 2;
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
