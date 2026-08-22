package com.eza.spicyex.lyrics.ai;

import java.util.Map;

/** The live transport, shared by every adapter so none of them can quietly use a different client. */
final class AiTransports {

    private AiTransports() {
    }

    static AiGeminiProvider.Transport live() {
        return new AiGeminiProvider.Transport() {
            @Override public AiHttp.Result get(String url, Map<String, String> headers,
                                               AiSignal signal, int maxBytes) {
                return AiHttp.get(url, headers, signal, maxBytes);
            }

            @Override public AiHttp.Result postJson(String url, Map<String, String> headers,
                                                    String json, AiSignal signal, int maxBytes) {
                return AiHttp.postJson(url, headers, json, signal, maxBytes);
            }
        };
    }
}
