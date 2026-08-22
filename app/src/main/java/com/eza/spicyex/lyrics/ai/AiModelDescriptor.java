package com.eza.spicyex.lyrics.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A model as the provider describes it: name, version, limits, and what it can be asked to do. */
public final class AiModelDescriptor extends AiModelLimits {
    public final String name;
    public final String version;
    public final List<String> supportedGenerationMethods;

    public AiModelDescriptor(String name, String version, int inputTokenLimit, int outputTokenLimit,
                             List<String> supportedGenerationMethods) {
        super(inputTokenLimit, outputTokenLimit);
        this.name = AiText.nz(name);
        this.version = AiText.nz(version);
        this.supportedGenerationMethods = Collections.unmodifiableList(new ArrayList<>(
                supportedGenerationMethods == null
                        ? Collections.<String>emptyList() : supportedGenerationMethods));
    }
}
