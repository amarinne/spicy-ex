package com.eza.spicyex.lyrics.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collections;

/** The bounded model inputs that fixed one paid run's root and hierarchical boundaries. */
final class AiPlanningSnapshot {
    // A malformed snapshot keeps the purchase readable but cannot admit replay or another call.
    static final AiPlanningSnapshot INVALID = new AiPlanningSnapshot(0, 0, -1);
    final int inputTokenLimit;
    final int outputTokenLimit;
    final int reasoningAllowanceTokens;

    private AiPlanningSnapshot(int input, int output, int reasoning) {
        inputTokenLimit = input;
        outputTokenLimit = output;
        reasoningAllowanceTokens = reasoning;
    }

    static AiPlanningSnapshot capture(AiModelLimits model) {
        return new AiPlanningSnapshot(model == null ? Integer.MAX_VALUE : model.inputTokenLimit,
                model == null ? Integer.MAX_VALUE : model.outputTokenLimit,
                AiChunkPlanner.reasoningAllowance(model));
    }

    boolean isValid() {
        return inputTokenLimit > 0 && outputTokenLimit > 0 && reasoningAllowanceTokens >= 0
                && reasoningAllowanceTokens <= AiContract.MAX_CONFIGURED_OUTPUT_TOKENS;
    }

    AiModelDescriptor model() {
        return new AiModelDescriptor("", "", inputTokenLimit, outputTokenLimit,
                Collections.emptyList(), reasoningAllowanceTokens);
    }

    JsonObject encode() {
        JsonObject value = new JsonObject();
        value.addProperty("inputTokenLimit", inputTokenLimit);
        value.addProperty("outputTokenLimit", outputTokenLimit);
        value.addProperty("reasoningAllowanceTokens", reasoningAllowanceTokens);
        return value;
    }

    static AiPlanningSnapshot decode(JsonElement value) {
        try {
            if (value == null || !value.isJsonObject()) return INVALID;
            JsonObject fields = value.getAsJsonObject();
            if (fields.size() != 3) return INVALID;
            AiPlanningSnapshot snapshot = new AiPlanningSnapshot(integer(fields, "inputTokenLimit"),
                    integer(fields, "outputTokenLimit"), integer(fields, "reasoningAllowanceTokens"));
            return snapshot.isValid() ? snapshot : INVALID;
        } catch (RuntimeException malformed) {
            return INVALID;
        }
    }

    private static int integer(JsonObject fields, String name) {
        JsonElement value = fields.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("planning_number");
        }
        return value.getAsBigDecimal().intValueExact();
    }
}
