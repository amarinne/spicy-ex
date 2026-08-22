package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;

import java.util.Collections;

/** One explicit, tiny structured-output probe for the selected model. */
public final class AiModelProbe {
    private AiModelProbe() {
    }

    public static final class Result {
        public final boolean ok;
        /** Privacy-safe machine token; never a provider response body. */
        public final String failure;

        private Result(boolean ok, String failure) {
            this.ok = ok;
            this.failure = AiText.nz(failure);
        }

        static Result success() {
            return new Result(true, "");
        }

        static Result failed(String reason) {
            return new Result(false, AiText.nz(reason).isEmpty() ? "unknown" : reason);
        }
    }

    /**
     * Sends one billable ordinary row and requires the exact shared response contract.
     *
     * <p>This mirrors the desktop probe: {@code P0}, source {@code hola}, target {@code en}, no
     * steering, a 32-token output cap, and one strict id/text response. It is deliberately not a
     * connectivity-only ping; a model that answers but cannot return the lyric protocol is not
     * usable by the feature.
     */
    public static Result probe(AiSettings settings, AiSignal signal) {
        if (settings == null || !settings.canRequest()) return Result.failed("not_ready");
        AiProviderConfig config = settings.providerConfig(LayerKind.MEANING, "en");
        if (config == null) return Result.failed("no_model");
        AiRequestItem item = new AiRequestItem("P0", AiLineClass.ORDINARY, null,
                "hola", null);
        AiProviderRequest request = new AiProviderRequest(AiLyricContext.EMPTY, "en", "",
                Collections.singletonList(item));
        AiProviderResult result;
        try {
            result = settings.provider().generateChunk(request, config.forCall(false, 32), signal);
        } catch (AiCancelledException cancelled) {
            return Result.failed("cancelled");
        } catch (Throwable failure) {
            return Result.failed("exception:" + failure.getClass().getSimpleName());
        }
        if (result == null) return Result.failed("no_result");
        if (!result.ok) return Result.failed(failureToken(result.failure));
        if (result.finish == AiFinishReason.SAFETY) return Result.failed("finish:safety");
        if (result.finish == AiFinishReason.LENGTH) return Result.failed("finish:length");
        try {
            AiResponseValidator.validate(AiResponseReader.readItems(result.rawText),
                    Collections.singletonList(item), LayerKind.MEANING, "en");
            return Result.success();
        } catch (AiProtocolException invalid) {
            return Result.failed("protocol:" + invalid.getMessage());
        } catch (Throwable failure) {
            return Result.failed("exception:" + failure.getClass().getSimpleName());
        }
    }

    /** Compatibility boolean for callers that only need the gate. */
    public static boolean run(AiSettings settings, AiSignal signal) {
        return probe(settings, signal).ok;
    }

    private static String failureToken(AiProviderFailure failure) {
        if (failure == null) return "provider:no_failure_detail";
        String kind = failure.kind == null ? "unknown" : failure.kind.name().toLowerCase(java.util.Locale.ROOT);
        return failure.detail.isEmpty() ? "provider:" + kind : "provider:" + kind + ":" + failure.detail;
    }
}
