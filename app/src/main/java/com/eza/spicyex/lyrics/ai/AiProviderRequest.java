package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.Digests;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The user turn of one call: context, target, optional steering, and the items.
 *
 * <p>Serialization is byte-stable by construction — fixed key order, no insignificant whitespace,
 * optional keys omitted rather than nulled. A structural repair resends these exact bytes and a
 * resume replays them, so two serializations of the same request that differ by a space are two
 * different requests as far as the cache is concerned.
 */
public final class AiProviderRequest {
    public final AiLyricContext context;
    public final String target;
    /** Normalized steering, or empty when the request carries none. */
    public final String instructions;
    public final List<AiRequestItem> items;

    public AiProviderRequest(AiLyricContext context, String target, String instructions,
                             List<AiRequestItem> items) {
        this.context = AiLyricContext.normalize(context);
        this.target = AiText.nz(target);
        this.instructions = AiText.nz(instructions);
        this.items = Collections.unmodifiableList(
                new ArrayList<>(items == null ? Collections.<AiRequestItem>emptyList() : items));
    }

    public String toJson() {
        StringBuilder out = new StringBuilder(256);
        out.append("{\"context\":");
        context.appendJson(out);
        out.append(",\"target\":");
        Digests.appendJsonString(out, target);
        if (!instructions.isEmpty()) {
            out.append(",\"instructions\":");
            Digests.appendJsonString(out, instructions);
        }
        out.append(",\"items\":[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) out.append(',');
            items.get(i).appendJson(out);
        }
        return out.append("]}").toString();
    }
}
