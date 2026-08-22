package com.eza.spicyex.lyrics.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Model discovery's outcome: a filtered list, or a typed failure. */
public final class AiModelListResult {
    public final boolean ok;
    public final List<AiModelDescriptor> models;
    public final AiProviderFailure failure;

    private AiModelListResult(boolean ok, List<AiModelDescriptor> models,
                              AiProviderFailure failure) {
        this.ok = ok;
        this.models = Collections.unmodifiableList(new ArrayList<>(
                models == null ? Collections.<AiModelDescriptor>emptyList() : models));
        this.failure = failure;
    }

    public static AiModelListResult ok(List<AiModelDescriptor> models) {
        return new AiModelListResult(true, models, null);
    }

    public static AiModelListResult failed(AiProviderFailure failure) {
        return new AiModelListResult(false, null, failure);
    }
}
