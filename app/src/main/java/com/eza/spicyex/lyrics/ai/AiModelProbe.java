package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A representative structured-output probe for the selected model.
 *
 * <p>Deliberately not a one-word ping. A model can return {@code {"items":[{"id":"P0","t":"hello"}]}}
 * for a single Latin word and still fail the first real song, because the rules that actually break
 * are the ones a single word never exercises: preserving a {@code ' / '} segment boundary, leaving
 * an ad-lib alone, translating a non-Latin row rather than romanizing it, and returning the
 * requested id set exactly. The fixture below exercises all four, so a pass means the contract
 * holds and not merely that the endpoint answered.
 *
 * <p>The fixture is ours, not the listener's. Nothing in a probe request, a probe response, or a
 * probe failure is derived from the song on screen, which is why {@link Trace} may carry the whole
 * exchange verbatim while the live lyric path may not.
 */
public final class AiModelProbe {
    private AiModelProbe() {
    }

    /**
     * The full exchange for one probe, for the owner and for a diagnostic report.
     *
     * <p>Verbatim on purpose. A machine token says <em>that</em> a model failed the contract; only
     * the bytes say why, and "why" is the question a bug report has to answer. Safe to show and to
     * attach because the fixture contains no listener data — see the class note.
     */
    public static final class Trace {
        /** Credential-free request body exactly as dispatched, or empty when none was sent. */
        public final String request;
        /** Model output exactly as received, or empty when the call did not return one. */
        public final String response;
        public final AiFinishReason finish;
        public final AiUsage usage;
        /** Transport status when the provider reported one, otherwise 0. */
        public final int httpStatus;

        /** Public because a report factory composes this value outside this package. */
        public Trace(String request, String response, AiFinishReason finish, AiUsage usage,
                     int httpStatus) {
            this.request = AiText.nz(request);
            this.response = AiText.nz(response);
            this.finish = finish;
            this.usage = usage == null ? AiUsage.UNREPORTED : usage;
            this.httpStatus = httpStatus;
        }

        public static final Trace EMPTY = new Trace("", "", null, AiUsage.UNREPORTED, 0);

        public boolean hasRequest() {
            return !request.isEmpty();
        }

        public boolean hasResponse() {
            return !response.isEmpty();
        }
    }

    public static final class Result {
        public final boolean ok;
        /** Privacy-safe machine token, stable enough to compare across reports. */
        public final String failure;
        /** The exchange behind {@link #failure}. Never empty-by-policy, only empty-by-absence. */
        public final Trace trace;

        private Result(boolean ok, String failure, Trace trace) {
            this.ok = ok;
            this.failure = AiText.nz(failure);
            this.trace = trace == null ? Trace.EMPTY : trace;
        }

        static Result success(Trace trace) {
            return new Result(true, "", trace);
        }

        static Result failed(String reason) {
            return failed(reason, Trace.EMPTY);
        }

        static Result failed(String reason, Trace trace) {
            return new Result(false, AiText.nz(reason).isEmpty() ? "unknown" : reason, trace);
        }

        /** One line for a toast or a report row: the verdict and its token. */
        public String summary() {
            return ok ? "ok" : failure;
        }
    }

    /**
     * The rows every probe sends. Fixed, so two probe results are comparable.
     *
     * <p>The multi-segment row is pre-split exactly as the planner splits real documents: one item
     * per segment with derived ids, rejoined by the validator. The probe therefore never measures
     * delimiter obedience, because live traffic no longer asks for it either.
     */
    static List<AiRequestItem> fixture() {
        return Collections.unmodifiableList(Arrays.asList(
                // A non-Latin row sent as its two segments: catches a romanizing model and one
                // that cannot answer pre-split items id-exactly.
                new AiRequestItem(AiContract.segmentId("P0", 0), AiLineClass.ORDINARY,
                        AiVoiceHint.PRIMARY, "夜が明ける", null),
                new AiRequestItem(AiContract.segmentId("P0", 1), AiLineClass.ORDINARY,
                        AiVoiceHint.PRIMARY, "静かに", null),
                // An already-English row: correct output may be the input unchanged, which a model
                // that always rewrites will fail.
                new AiRequestItem("P1", AiLineClass.ORDINARY, AiVoiceHint.ALTERNATE,
                        "and we kept walking", null),
                // An ad-lib: sent, and legitimately returned untouched.
                new AiRequestItem("P2", AiLineClass.ADLIB, AiVoiceHint.BACKGROUND,
                        "oh oh oh", null)));
    }

    /**
     * Sends the fixture document and requires the exact shared response contract.
     *
     * <p>The output cap is {@link AiContract#PROBE_OUTPUT_TOKENS} rather than a figure derived from
     * the size of the expected reply, because the reply is not the only thing that spends the
     * budget — see that constant.
     */
    public static Result probe(AiSettings settings, AiSignal signal) {
        if (settings == null || !settings.canRequest()) return Result.failed("not_ready");
        AiProviderConfig config = settings.providerConfig(LayerKind.MEANING, "en");
        if (config == null) return Result.failed("no_model");
        return probe(settings.provider(), config, signal);
    }

    /** The probe against an explicit provider and configuration. */
    public static Result probe(AiProvider provider, AiProviderConfig config, AiSignal signal) {
        if (provider == null || config == null) return Result.failed("not_ready");
        List<AiRequestItem> items = new ArrayList<>(fixture());
        AiProviderRequest request = new AiProviderRequest(AiLyricContext.EMPTY, "en", "", items);
        AiProviderConfig callConfig = config.forCall(false, AiContract.PROBE_OUTPUT_TOKENS);

        String sent = "";
        try {
            sent = AiText.nz(provider.monitorPayload(request, callConfig));
        } catch (Throwable notAvailable) {
            // A monitor payload is a diagnostic convenience. Failing to render one must never be
            // the reason a working model is reported as broken.
            sent = "";
        }

        AiProviderResult result;
        try {
            result = provider.generateChunk(request, callConfig, signal);
        } catch (AiCancelledException cancelled) {
            return Result.failed("cancelled", new Trace(sent, "", null, AiUsage.UNREPORTED, 0));
        } catch (Throwable failure) {
            return Result.failed("exception:" + failure.getClass().getSimpleName(),
                    new Trace(sent, "", null, AiUsage.UNREPORTED, 0));
        }
        if (result == null) {
            return Result.failed("no_result", new Trace(sent, "", null, AiUsage.UNREPORTED, 0));
        }
        if (!result.ok) {
            int status = result.failure == null ? 0 : result.failure.status;
            return Result.failed(failureToken(result.failure),
                    new Trace(sent, "", null, AiUsage.UNREPORTED, status));
        }

        Trace trace = new Trace(sent, result.rawText, result.finish, result.usage, 0);
        if (result.finish == AiFinishReason.SAFETY) return Result.failed("finish:safety", trace);
        if (result.finish == AiFinishReason.LENGTH) return Result.failed("finish:length", trace);
        try {
            AiResponseValidator.validate(AiResponseReader.readItems(result.rawText), items,
                    LayerKind.MEANING, "en");
            return Result.success(trace);
        } catch (AiProtocolException invalid) {
            return Result.failed("protocol:" + invalid.getMessage(), trace);
        } catch (Throwable failure) {
            return Result.failed("exception:" + failure.getClass().getSimpleName(), trace);
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
