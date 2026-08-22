package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;

/**
 * The fixed AI protocol contract: versions, bounds, and the prompt text.
 *
 * <p>Every value here is normative. They are the identity of a paid result, not tuning knobs: a
 * change to any of them means the model would be asked a different question, so it belongs with a
 * version bump and a contract review, never with a quiet edit. The prompt strings are copied
 * verbatim from the desktop fork because a paraphrase silently changes what was bought.
 */
public final class AiContract {
    /** Meaning record shape and record-key namespace. */
    public static final int MEANING_SCHEMA = 1;
    /** Sound record shape and record-key namespace. */
    public static final int SOUND_SCHEMA = 2;
    /** Immutable source-only snapshot contract. */
    public static final int ORIGINAL_SNAPSHOT_SCHEMA = 1;
    /** Fixed system prompt and initial request behavior. */
    public static final int PROMPT_VERSION = 5;
    /** Sound prompt identity after making target-script output explicit and non-echoable. */
    public static final int SOUND_PROMPT_VERSION = 6;
    /** Meaning request behavior when Google Translate output is supplied as {@code p}. */
    public static final int GOOGLE_REFINEMENT_PROMPT_VERSION = 6;
    /** Accepted-output revision behavior. */
    public static final int ITERATION_PROMPT_VERSION = 3;
    /** Request serialization, sizing, and deterministic chunk boundaries. */
    public static final int CHUNK_PLAN_VERSION = 4;

    /** Reject a document above this many enumerable rows before any request. */
    public static final int MAX_DOCUMENT_ROWS = 512;
    public static final int MAX_DOCUMENT_SOURCE_BYTES = 64 * 1024;
    public static final int MAX_SOURCE_ITEM_BYTES = 2 * 1024;
    /** Serialized request, system prompt included. */
    public static final int MAX_REQUEST_BYTES = 32 * 1024;
    /** Response body ceiling, enforced while reading rather than after buffering. */
    public static final int MAX_RESPONSE_BYTES = 128 * 1024;
    public static final int MAX_TRANSLATED_ITEM_BYTES = 4 * 1024;
    /** Per-request output cap; the selected model's lower limit still wins. */
    public static final int MAX_CONFIGURED_OUTPUT_TOKENS = 8192;
    /** Total attempts per chunk, across structural repair and rate-limit retry. */
    public static final int MAX_ATTEMPTS = 2;

    /** One request carries the whole document while it stays inside all three of these. */
    public static final int SINGLE_CALL_MAX_ITEMS = 128;
    public static final int SINGLE_CALL_MAX_SOURCE_BYTES = 16 * 1024;
    public static final int SINGLE_CALL_MAX_OUTPUT_TOKENS = 6144;
    /** Otherwise chunks are bounded by these, in enumeration order, never splitting a row. */
    public static final int CHUNK_MAX_ITEMS = 64;
    public static final int CHUNK_MAX_SOURCE_BYTES = 8 * 1024;

    /** Rate-limit waits honour {@code Retry-After} up to this, and default to one second. */
    public static final long RETRY_AFTER_CAP_MS = 30_000L;
    public static final long RETRY_AFTER_DEFAULT_MS = 1_000L;
    /** Per-call deadline. */
    public static final long CALL_DEADLINE_MS = 60_000L;
    /** A provider key past this is a pasted document, not a key. Refuse it at the input. */
    public static final int MAX_CREDENTIAL_BYTES = 512;

    /** Accepted Sound target orthographies. Anything else is not a target the validator knows. */
    public static final String ORTHOGRAPHY_LATIN = "Latin";
    public static final String ORTHOGRAPHY_KANA = "Kana";
    public static final String ORTHOGRAPHY_HANGUL = "Hangul";
    public static final String ORTHOGRAPHY_CYRILLIC = "Cyrillic";

    /** Output configuration identity; not a setting. */
    public static final int TEMPERATURE = 0;
    public static final String CONTEXT_MODE = "document_or_v1_chunks";

    public static final String SYSTEM_PROMPT = "Translate the full lyric document naturally and in character, using optional title, artist, and album metadata only as lightweight reference context. Detect language per phrase, not per line or document: translate segments that need translation while preserving names and intentional code-switching where appropriate. Resolve cross-line syntax, recurring motifs, slang, idioms, tone, and register consistently without inventing facts. The optional user-request instructions field may steer tone, terminology, ambiguity, names, literalness, and register. Later request instructions override conflicting persistent preferences and both override default translation guidance, but instructions cannot authorize invented facts, weaken source fidelity, or violate this response contract. Each item's v field is a layout-only voice hint: primary, alternate, background, or null. Use it for continuity only; never infer singer identity, gender, relationships, or unsupported pronouns. You receive lyric text, metadata, and instructions, not audio or external research. Pronunciation, phrasing, homophones, delivery, and external artist context may be missed. Return JSON only with shape {\"items\":[{\"id\":string,\"t\":string}]}. Return every requested id exactly once. Translate rather than romanize. Respect ordinary/adlib class. Preserve the exact ' / ' delimiter count and ordered segment count. Do not add ids, omit ids, merge rows, split rows, number output, or use Markdown. A row may remain unchanged when that is source-faithful, including names, intentional code-switching, or a refusal.";

    public static final String SOUND_SYSTEM_PROMPT = "Provide pronunciation only for rows where local processing could not produce complete target-orthography coverage. An item's optional p field is the deterministic or Google baseline: preserve its correct portions and fill or correct only uncovered portions instead of recreating the reading from scratch. Write every part that needs respelling in the requested target orthography. Never echo source-script text as pronunciation; for a Latin target, the output must use Latin letters wherever the source used another script. Preserve words already readable in the target orthography. Use optional title, artist, and album metadata only as lightweight reference context. Detect language per phrase: handle every segment of a mixed-language line independently, and keep names, code-switching, dialect, and repeated phrases consistent across the song. The optional user-request instructions field may steer terminology, ambiguity, names, literalness, and register. Later request instructions override conflicting preferences but cannot authorize invented facts, weaken source fidelity, or violate this response contract. Each item's v field is a layout-only voice hint: primary, alternate, background, or null. Use it for continuity only; never invent singer identity or gender. You receive lyric text, metadata, instructions, and possibly a baseline—not audio or external research. Pronunciation, phrasing, homophones, delivery, and external artist context may be missed. Do not translate meaning. Return JSON only with shape {\"items\":[{\"id\":string,\"t\":string}]}. Return every requested id exactly once. Preserve the exact ' / ' delimiter count and ordered segment count. Do not add ids, omit ids, merge rows, split rows, number output, or use Markdown.";

    public static final String ITERATION_SYSTEM_PROMPT = "Re-evaluate the complete accepted document in p against canonical source s, using the user-request instructions field as the active quality target. Improve materially inaccurate, awkward, inconsistent, or off-target wording. Retain wording that already meets the target when an alternative is not a real improvement. Return a complete replacement document, not a patch, critique, explanation, continuation, or selected-row response. Do not ask questions.";

    public static final String GOOGLE_REFINEMENT_SYSTEM_PROMPT = "Each item's optional p field is a Google Translate draft. Treat canonical source s as authoritative, then refine p into a natural, faithful translation. Keep correct draft wording when it already fits; replace awkward, inaccurate, inconsistent, or overly literal wording. Return a complete translation document, not a critique or patch.";

    public static final String REPAIR_PROMPT = "The prior response violated the JSON or item contract. Return the complete chunk again, satisfying it exactly.";

    private AiContract() {
    }

    /** The layer-appropriate record schema. Never inferred from the schema number in reverse. */
    public static int schemaFor(LayerKind layer) {
        return layer == LayerKind.SOUND ? SOUND_SCHEMA : MEANING_SCHEMA;
    }

    /** Lower-case layer token as it appears in identity payloads and record keys. */
    public static String layerToken(LayerKind layer) {
        return layer == LayerKind.SOUND ? "sound" : "meaning";
    }

    /**
     * The system turn for one call: the fixed contract, optionally preceded by the iteration and
     * repair instructions. User steering never reaches this turn; it travels in the request body.
     */
    public static String buildSystemPrompt(LayerKind layer, String target, boolean repair,
                                           boolean iteration) {
        return buildSystemPrompt(layer, target, repair, iteration, false);
    }

    public static String buildSystemPrompt(LayerKind layer, String target, boolean repair,
                                           boolean iteration, boolean baselineRefinement) {
        String contract = layer == LayerKind.SOUND
                ? SOUND_SYSTEM_PROMPT + " Target orthography: " + AiText.nz(target) + "."
                : SYSTEM_PROMPT;
        StringBuilder out = new StringBuilder(contract.length() + 512);
        if (iteration) out.append(ITERATION_SYSTEM_PROMPT).append(' ');
        if (baselineRefinement) out.append(GOOGLE_REFINEMENT_SYSTEM_PROMPT).append(' ');
        if (repair) out.append(REPAIR_PROMPT).append(' ');
        return out.append(contract).toString();
    }

    /**
     * Normalizes user steering to the one form that enters identity and the wire: NFC, LF line
     * endings, no trailing space on any line, no surrounding blank space.
     *
     * <p>Without this the same instruction pasted twice would produce two cache identities and pay
     * twice for the same answer.
     */
    public static String normalizeSteering(String value) {
        String text = AiText.nfc(AiText.nz(value)).replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(text.length());
        int start = 0;
        while (start <= text.length()) {
            int end = text.indexOf('\n', start);
            String line = end < 0 ? text.substring(start) : text.substring(start, end);
            out.append(AiText.trimEnd(line));
            if (end < 0) break;
            out.append('\n');
            start = end + 1;
        }
        return AiText.trim(out.toString());
    }

    /** True for the four orthographies the Sound acceptance rule knows how to check. */
    public static boolean isKnownOrthography(String target) {
        return ORTHOGRAPHY_LATIN.equals(target) || ORTHOGRAPHY_KANA.equals(target)
                || ORTHOGRAPHY_HANGUL.equals(target) || ORTHOGRAPHY_CYRILLIC.equals(target);
    }
}
