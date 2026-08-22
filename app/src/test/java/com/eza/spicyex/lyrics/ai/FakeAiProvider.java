package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.Digests;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * An offline provider, and the only one that exists at this stage of the port.
 *
 * <p>It answers from a scripted queue so a test can say exactly what the model did — returned a
 * malformed body, refused, hit its length cap, rate-limited with a Retry-After, or simply took the
 * request and never came back. When the queue runs out it echoes a plausible answer, which keeps
 * tests that care about orchestration from having to script content they do not check.
 *
 * <p>It records every call, so the tests that matter most here — that a repair resends byte-
 * identical bytes, that a cancelled run never dispatched — can assert on what was actually sent.
 */
public final class FakeAiProvider implements AiProvider {
    /** A scripted answer, or a function of the call for the cases that need to observe it. */
    public interface Step {
        AiProviderResult answer(AiProviderRequest request, AiProviderConfig config, AiSignal signal);
    }

    /** What one call carried. The request is kept as its serialized bytes, which is the contract. */
    public static final class Call {
        public final String requestJson;
        public final AiProviderRequest request;
        public final AiProviderConfig config;

        Call(AiProviderRequest request, AiProviderConfig config) {
            this.requestJson = request.toJson();
            this.request = request;
            this.config = config;
        }
    }

    public static final AiModelDescriptor DEFAULT_MODEL = new AiModelDescriptor(
            "fake-model", "1", 32_768, 8_192, Collections.singletonList("generateContent"));

    public final List<Call> calls = new ArrayList<>();
    private final List<Step> steps = new ArrayList<>();
    private final List<AiModelDescriptor> models;

    public FakeAiProvider(Step... steps) {
        this(Collections.singletonList(DEFAULT_MODEL), steps);
    }

    public FakeAiProvider(List<AiModelDescriptor> models, Step... steps) {
        this.models = new ArrayList<>(models);
        this.steps.addAll(Arrays.asList(steps));
    }

    @Override public String id() {
        return "fake";
    }

    @Override public AiModelListResult listModels(AiSignal signal) {
        if (signal != null) signal.throwIfAborted();
        return AiModelListResult.ok(models);
    }

    @Override public AiProviderResult generateChunk(AiProviderRequest request,
                                                    AiProviderConfig config, AiSignal signal) {
        if (signal != null) signal.throwIfAborted();
        calls.add(new Call(request, config));
        if (!steps.isEmpty()) return steps.remove(0).answer(request, config, signal);
        return AiProviderResult.ok(echo(request), AiUsage.of(10, 10), AiFinishReason.STOP, 64L);
    }

    /** A well-formed answer for every requested id: ad-libs unchanged, everything else marked. */
    public static String echo(AiProviderRequest request) {
        List<String> texts = new ArrayList<>();
        for (AiRequestItem item : request.items) {
            texts.add(item.lineClass == AiLineClass.ADLIB ? item.source : "AI " + item.source);
        }
        return itemsJson(request, texts);
    }

    /** A response body carrying {@code texts} against the request's ids, in request order. */
    public static String itemsJson(AiProviderRequest request, List<String> texts) {
        StringBuilder out = new StringBuilder("{\"items\":[");
        for (int i = 0; i < request.items.size(); i++) {
            if (i > 0) out.append(',');
            out.append("{\"id\":");
            Digests.appendJsonString(out, request.items.get(i).id);
            out.append(",\"t\":");
            Digests.appendJsonString(out, texts.get(i));
            out.append('}');
        }
        return out.append("]}").toString();
    }

    // --- step helpers -------------------------------------------------------

    public static Step body(final String rawText) {
        return body(rawText, AiUsage.of(4, 2), AiFinishReason.STOP);
    }

    public static Step body(final String rawText, final AiUsage usage,
                            final AiFinishReason finish) {
        return new Step() {
            @Override public AiProviderResult answer(AiProviderRequest request,
                                                     AiProviderConfig config, AiSignal signal) {
                return AiProviderResult.ok(rawText, usage, finish, 20L);
            }
        };
    }

    public static Step failure(final AiProviderFailure failure) {
        return new Step() {
            @Override public AiProviderResult answer(AiProviderRequest request,
                                                     AiProviderConfig config, AiSignal signal) {
                return AiProviderResult.failed(failure);
            }
        };
    }

    /** Never answers: blocks until the call's own signal aborts, then reports it as we would. */
    public static Step neverReturns() {
        return new Step() {
            @Override public AiProviderResult answer(AiProviderRequest request,
                                                     AiProviderConfig config, AiSignal signal) {
                try {
                    signal.awaitAbort(5_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                throw new AiCancelledException(signal.reason());
            }
        };
    }
}
