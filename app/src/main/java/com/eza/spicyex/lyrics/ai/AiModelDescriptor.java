package com.eza.spicyex.lyrics.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A model as the provider describes it: name, version, limits, and what it can be asked to do. */
public final class AiModelDescriptor extends AiModelLimits {
    public final String name;
    public final String version;
    public final List<String> supportedGenerationMethods;
    /**
     * Probe-measured reasoning allowance in output tokens, or {@code -1} when never measured.
     *
     * <p>A compatible {@code /models} response carries no limits and no capability flags, so this
     * is the one per-model output-budget fact the app can actually learn: how many completion
     * tokens the structured-output probe spent on a fixed tiny fixture. A model that spends
     * hundreds there reasons, and the planner sizes its output budget from this instead of the
     * contract default. Measured per provider/model identity, persisted across process restarts.
     */
    public final int reasoningAllowanceTokens;

    public AiModelDescriptor(String name, String version, int inputTokenLimit, int outputTokenLimit,
                             List<String> supportedGenerationMethods) {
        this(name, version, inputTokenLimit, outputTokenLimit, supportedGenerationMethods, -1);
    }

    public AiModelDescriptor(String name, String version, int inputTokenLimit, int outputTokenLimit,
                             List<String> supportedGenerationMethods, int reasoningAllowanceTokens) {
        super(inputTokenLimit, outputTokenLimit);
        this.name = AiText.nz(name);
        this.version = AiText.nz(version);
        this.supportedGenerationMethods = Collections.unmodifiableList(new ArrayList<>(
                supportedGenerationMethods == null
                        ? Collections.<String>emptyList() : supportedGenerationMethods));
        this.reasoningAllowanceTokens = reasoningAllowanceTokens;
    }
}
