package com.eza.spicyex.lyrics.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One dispatchable unit of work, with its request already serialized.
 *
 * <p>The serialized bytes are held rather than rebuilt on demand: a repair resends them unchanged
 * and a resume replays them from the cache record, and both would be defeated by a request that is
 * regenerated slightly differently the second time.
 */
public final class AiPlannedChunk {
    /** Stable within a plan: {@code C0}, {@code C1}, …. */
    public final String id;
    public final AiLyricContext context;
    /** Normalized steering, empty when none. */
    public final String instructions;
    public final List<AiRequestItem> items;
    /** Rows whose unchanged output was historically permitted; digest compatibility only. */
    public final List<String> allowUnchangedIds;
    public final String requestJson;
    public final int sourceUtf8Bytes;
    public final int estimatedInputTokens;
    public final int estimatedOutputTokens;

    AiPlannedChunk(String id, AiLyricContext context, String instructions,
                   List<AiRequestItem> items, List<String> allowUnchangedIds, String requestJson,
                   int sourceUtf8Bytes, int estimatedInputTokens, int estimatedOutputTokens) {
        this.id = AiText.nz(id);
        this.context = AiLyricContext.normalize(context);
        this.instructions = AiText.nz(instructions);
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.allowUnchangedIds = Collections.unmodifiableList(new ArrayList<>(allowUnchangedIds));
        this.requestJson = AiText.nz(requestJson);
        this.sourceUtf8Bytes = sourceUtf8Bytes;
        this.estimatedInputTokens = estimatedInputTokens;
        this.estimatedOutputTokens = estimatedOutputTokens;
    }

    public List<String> itemIds() {
        List<String> ids = new ArrayList<>(items.size());
        for (AiRequestItem item : items) ids.add(item.id);
        return ids;
    }
}
