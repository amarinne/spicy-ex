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
    /**
     * Fixed system prompt and initial request behavior.
     *
     * <p>v7 rejects a Meaning response that copies the request's layout-only {@code v} token into
     * {@code t}. A weak model returned {@code primary} for several code-switched rows; the old
     * shape validator accepted that nonblank metadata echo as lyric text.
     *
     * <p>v6 stops asking the model to preserve the {@code ' / '} delimiter count: multi-segment
     * rows are pre-split into one request item per segment and rejoined locally, so there is
     * nothing left for a model to disobey. A deliberate divergence from the desktop fork's verbatim
     * prompt — recorded in the handover findings, to be flagged for the desktop contract.
     */
    public static final int PROMPT_VERSION = 7;
    /**
     * Sound prompt and row-selection identity.
     *
     * <p>v8 treats a whole-line {@code RenderPlan} produced by the deterministic Sound artifact as
     * coverage. Russian/Greek local readings use that shape; v7 misclassified it as a replaceable
     * remote fallback and bought AI readings for rows the packaged engine had already covered.
     * Existing v7 Sound records were bought under the broader row selection and are not reused.
     *
     * <p>v7 dropped the delimiter-preservation sentence together with the Meaning prompt: segments
     * travel as separate request items now, and the count is enforced by construction on this side.
     */
    public static final int SOUND_PROMPT_VERSION = 8;
    /**
     * Meaning request behavior when Google Translate output is supplied as {@code p}.
     *
     * <p>Kept strictly ahead of {@link #PROMPT_VERSION}: these two version numbers share one
     * configId field, so equal values would let a refinement record answer a plain request.
     */
    public static final int GOOGLE_REFINEMENT_PROMPT_VERSION = 8;
    /** Accepted-output revision behavior. */
    public static final int ITERATION_PROMPT_VERSION = 3;
    /**
     * Request serialization, sizing, and deterministic chunk boundaries.
     *
     * <p>v7 adds deterministic hierarchical re-planning after a chunk reaches the model's true
     * output ceiling. A v6 record has only the original chunk boundary and cannot prove which
     * smaller child requests were already bought, so Meaning and Sound records from v6 are not
     * resumable under this plan.
     *
     * <p>v6 added the structured-output directive to what was bought: where an endpoint accepts
     * {@code json_schema}, the item shape is enforced on the wire and the model's answer differs
     * enough from a plain {@code json_object} answer that records are not interchangeable.
     */
    public static final int CHUNK_PLAN_VERSION = 7;

    /** JSON shape of one accepted item, shared by the prompt text and the strict wire schema. */
    public static final String ITEM_SHAPE_JSON = "{\"id\":string,\"t\":string}";

    /** Reject a document above this many enumerable rows before any request. */
    public static final int MAX_DOCUMENT_ROWS = 512;
    public static final int MAX_DOCUMENT_SOURCE_BYTES = 64 * 1024;
    public static final int MAX_SOURCE_ITEM_BYTES = 2 * 1024;
    /** Serialized request, system prompt included. */
    public static final int MAX_REQUEST_BYTES = 32 * 1024;
    /**
     * Generation-response ceiling, enforced while reading rather than after buffering.
     *
     * <p>It exists because this code runs inside Spotify's process, not ours. An endpoint is
     * owner-supplied and may be a misconfigured proxy; reading whatever it sends into the host
     * app's heap can kill Spotify, and losing a translation is a far better outcome than that.
     * Bounding while reading rather than after is the same argument: a body that would not fit is
     * never fully materialized.
     *
     * <p>Derived from the contract rather than chosen, because a ceiling below what the validator
     * would accept rejects correct output — which is what a flat 128 KiB did: a full single call is
     * {@link #SINGLE_CALL_MAX_ITEMS} rows of up to {@link #MAX_TRANSLATED_ITEM_BYTES} each, four
     * times that figure before any JSON envelope. The factor of two is the envelope plus headroom.
     */
    public static final int MAX_RESPONSE_BYTES =
            2 * AiContract.SINGLE_CALL_MAX_ITEMS * AiContract.MAX_TRANSLATED_ITEM_BYTES;
    /**
     * Ceiling for a model-list response, which is an index rather than generated output.
     *
     * <p>Separate from {@link #MAX_RESPONSE_BYTES} because the two bound different risks. A
     * generation response is bounded because a model can produce unbounded text and we pay for it.
     * A model list is a fixed document the endpoint publishes: a routing gateway that fronts
     * several hundred models legitimately returns hundreds of kilobytes of it, and refusing that
     * makes every model on the endpoint unreachable — indistinguishable, from the owner's side,
     * from the endpoint being down.
     */
    public static final int MAX_MODEL_LIST_BYTES = 4 * 1024 * 1024;
    public static final int MAX_TRANSLATED_ITEM_BYTES = 4 * 1024;
    /** Per-request output cap; the selected model's lower limit still wins. */
    public static final int MAX_CONFIGURED_OUTPUT_TOKENS = 8192;
    /**
     * Output cap for the one-row model probe.
     *
     * <p>A ceiling, not a charge: a model that answers the probe in the eighteen tokens the reply
     * actually needs is billed for eighteen either way. The number only has to be large enough that
     * no usable model is cut off before it finishes, and a reasoning model spends its thinking
     * inside this same budget before the first character of the answer appears. A cap sized to the
     * visible reply alone therefore fails every such model with a length finish, which reads as
     * "cannot produce structured output" when the model produces it perfectly well.
     *
     * <p>Sized from measurement, not from the reply. A survey of the OpenRouter models reachable on
     * a free key (2026-08-23, {@code OpenRouterModelSurveyTest}) found that <em>every</em> model
     * which answered spent between 330 and 989 completion tokens on this fixture — against roughly
     * sixty tokens of visible answer — and one, {@code nvidia/nemotron-nano-9b-v2:free}, ran past
     * 1,024 and truncated. At that cap the highest passing model cleared it by thirty-five tokens,
     * which is not a margin. Raising it costs nothing: a model that answers in 330 is billed for
     * 330 either way.
     */
    public static final int PROBE_OUTPUT_TOKENS = 4096;
    /**
     * Total attempts per dispatchable chunk, across structural repair and rate-limit retry.
     *
     * <p>One shared budget on purpose: it is the whole cost control for asking the same question.
     * A truncation at the configured ceiling is not asked again. The layer runner accounts that
     * billed attempt, asks the planner for smaller deterministic child chunks, and each child gets
     * its own budget because it is a new bounded question rather than a retry of the oversized one.
     * Whatever the repair/rate-limit sequence, identical request bytes are never served more than
     * twice without the caller deciding to reopen them.
     */
    public static final int MAX_ATTEMPTS = 2;
    /**
     * Reasoning output allowance: the headroom term added to the planner's visible-output
     * estimate.
     *
     * <p>Output tokens are not a function of input size alone. A reasoning model spends an
     * unbounded extra term — the thinking — inside the same output budget before the first
     * character of the visible answer appears, and no OpenAI-wire {@code /models} response
     * announces it. The visible estimate ({@code ceilHalf(sourceBytes)}) stays exactly what it
     * was; this term is added on top so the two remain independently reviewable, and a measured
     * per-model value (see {@code AiModelDescriptor}) replaces it when the probe has seen one.
     *
     * <p>The error direction matters. Under-estimating truncates a billed response and costs a
     * retry; over-estimating only makes the planner split documents it did not have to. As a
     * dispatch cap the term is free unless the model actually burns it — the same argument as
     * {@link #PROBE_OUTPUT_TOKENS} — so this figure is sized to that constant's rationale: large
     * enough that a reasoning model is not cut off mid-think on a normal chunk, small enough not
     * to push ordinary songs into an extra call at the single-call boundary.
     */
    public static final int REASONING_OUTPUT_ALLOWANCE_TOKENS = 1024;
    /**
     * Response tokens one item costs before any translated text: its id, the two JSON keys, the
     * quoting and the separators.
     *
     * <p>Counted per item because the estimate's other term is source <em>text</em> bytes only,
     * which for a lyric document is the smaller half. A row id carries an eight-hex digest suffix,
     * so {@code {"id":"r27#7d9b227f","t":"…"}} costs roughly this much whether the line is one word
     * or twenty — and lyric lines are short and numerous, so omitting it under-counted the response
     * by more than the text itself.
     */
    public static final int RESPONSE_ITEM_OVERHEAD_TOKENS = 16;
    /** Smallest per-call output budget worth dispatching; below this a cap cannot carry a row. */
    public static final int MIN_CALL_OUTPUT_TOKENS = 512;

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
    /**
     * Per-call deadline, derived from the output budget rather than flat.
     *
     * <p>A flat minute was shorter than the work it was timing. A reasoning model asked for a full
     * lyric document spends its thinking and then emits a couple of thousand tokens; at the rates
     * these endpoints actually stream, that passes sixty seconds routinely — and the failure it
     * produces is the worst class we have. A deadline that fires after dispatch is
     * {@code DELIVERY_UNKNOWN}: the request may have been served and billed, so nothing may retry
     * it automatically and the owner has to be asked. Waiting longer for an answer that is coming
     * is strictly better than charging for one we threw away.
     *
     * <p>These are ceilings, not waits. They elapse only while the model is still producing.
     */
    public static final long CALL_DEADLINE_BASE_MS = 30_000L;
    /**
     * Roughly twelve output tokens per second.
     *
     * <p>Deliberately slower than any endpoint should be. Generation speed is not ours to predict:
     * a queued free tier, a cold start, a reasoning model at depth, or a gateway routing to
     * whichever upstream has capacity can all take minutes for work another provider returns in
     * seconds. A rate tuned to the fast case turns the slow case into
     * {@code DELIVERY_UNKNOWN} — billed, unrepeatable without asking the owner — so the only safe
     * direction to be wrong in is generous.
     */
    public static final long CALL_DEADLINE_MS_PER_OUTPUT_TOKEN = 80L;
    /**
     * The outer bound, and it is deliberately large.
     *
     * <p>What actually ends a call that is going nowhere is not this: an unreachable host fails at
     * the connect timeout in seconds, and a track change aborts the signal, which cancels the
     * socket. This exists only so a live connection that will never answer cannot hold a lane
     * forever, and being impatient here costs more than being slow.
     */
    public static final long MAX_CALL_DEADLINE_MS = 600_000L;
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

    /**
     * The marker separating a row's id from its segment index on the wire.
     *
     * <p>Multi-segment rows are sent as one request item per segment — {@code r0#ab~0},
     * {@code r0#ab~1} — and rejoined locally, so no model is ever asked to preserve a delimiter
     * count it can get wrong. The marker must not occur in canonical row ids, which are
     * {@code r<index>#<hex>}; {@code '~'} never does.
     */
    public static final char SEGMENT_MARKER = '~';

    /** The wire id for segment {@code segmentIndex} of the row with id {@code rowId}. */
    public static String segmentId(String rowId, int segmentIndex) {
        return rowId + SEGMENT_MARKER + segmentIndex;
    }

    /** The segment index encoded in {@code itemId}, or {@code -1} when it addresses a whole row. */
    public static int segmentIndexOf(String itemId) {
        String id = AiText.nz(itemId);
        int at = id.lastIndexOf(SEGMENT_MARKER);
        if (at < 0) return -1;
        try {
            return Integer.parseInt(id.substring(at + 1));
        } catch (NumberFormatException notASegment) {
            return -1;
        }
    }

    /** The row id a request item addresses, with any segment suffix removed. */
    public static String rowIdOf(String itemId) {
        String id = AiText.nz(itemId);
        int at = id.lastIndexOf(SEGMENT_MARKER);
        if (at < 0) return id;
        return segmentIndexOf(id) >= 0 ? id.substring(0, at) : id;
    }

    /**
     * Meaning system prompt.
     *
     * <p>Shape: hard rules first, short and numbered, semantics after. The rules are the sentences
     * a weak instruction-follower stops reading by the end of a long paragraph — the id set,
     * translate-rather-than-romanize, ad-lib class, and the forbidden edits — so they lead. The
     * wording of every rule and every semantic sentence carries over from the previous single-
     * paragraph prompt; only order and layout changed. This is a deliberate divergence from the
     * desktop fork's verbatim copy, recorded for the desktop contract.
     */
    public static final String SYSTEM_PROMPT = "Hard rules:\n"
            + "1. Return JSON only, shaped {\"items\":[{\"id\":string,\"t\":string}]}. Do not add "
            + "ids, omit ids, merge rows, split rows, number output, or use Markdown.\n"
            + "2. Return every requested id exactly once.\n"
            + "3. Translate rather than romanize.\n"
            + "4. Respect the ordinary/adlib class of each item.\n"
            + "5. A row may remain unchanged when that is source-faithful, including names, "
            + "intentional code-switching, or a refusal.\n"
            + "\n"
            + "Translate the full lyric document naturally and in character, using optional title, "
            + "artist, and album metadata only as lightweight reference context. Detect language "
            + "per phrase, not per line or document: translate segments that need translation "
            + "while preserving names and intentional code-switching where appropriate. Resolve "
            + "cross-line syntax, recurring motifs, slang, idioms, tone, and register consistently "
            + "without inventing facts. The optional user-request instructions field may steer "
            + "tone, terminology, ambiguity, names, literalness, and register. Later request "
            + "instructions override conflicting persistent preferences and both override default "
            + "translation guidance, but instructions cannot authorize invented facts, weaken "
            + "source fidelity, or violate these hard rules. Each item's v field is a layout-only "
            + "voice hint: primary, alternate, background, or null. Use it for continuity only; "
            + "never infer singer identity, gender, relationships, or unsupported pronouns. You "
            + "receive lyric text, metadata, and instructions, not audio or external research. "
            + "Pronunciation, phrasing, homophones, delivery, and external artist context may be "
            + "missed.";

    /**
     * Sound system prompt, structured like {@link #SYSTEM_PROMPT}: the acceptance-critical rules
     * lead, the pronunciation guidance follows.
     */
    public static final String SOUND_SYSTEM_PROMPT = "Hard rules:\n"
            + "1. Return JSON only, shaped {\"items\":[{\"id\":string,\"t\":string}]}. Do not add "
            + "ids, omit ids, merge rows, split rows, number output, or use Markdown.\n"
            + "2. Return every requested id exactly once.\n"
            + "3. Provide pronunciation only. Never echo source-script text as pronunciation, and "
            + "never translate meaning; for a Latin target, the output must use Latin letters "
            + "wherever the source used another script.\n"
            + "4. Write every part that needs respelling in the requested target orthography.\n"
            + "\n"
            + "An item's optional p field is the deterministic or Google baseline: preserve its "
            + "correct portions and fill or correct only uncovered portions instead of recreating "
            + "the reading from scratch. Preserve words already readable in the target "
            + "orthography. Provide pronunciation only for rows where local processing could not "
            + "produce complete target-orthography coverage. Use optional title, artist, and "
            + "album metadata only as lightweight reference context. Detect language per phrase: "
            + "handle every segment of a mixed-language line independently, and keep names, "
            + "code-switching, dialect, and repeated phrases consistent across the song. The "
            + "optional user-request instructions field may steer terminology, ambiguity, names, "
            + "literalness, and register. Later request instructions override conflicting "
            + "preferences but cannot authorize invented facts, weaken source fidelity, or violate "
            + "these hard rules. Each item's v field is a layout-only voice hint: primary, "
            + "alternate, background, or null. Use it for continuity only; never invent singer "
            + "identity or gender. You receive lyric text, metadata, instructions, and possibly a "
            + "baseline—not audio or external research. Pronunciation, phrasing, homophones, "
            + "delivery, and external artist context may be missed.";

    public static final String ITERATION_SYSTEM_PROMPT = "Re-evaluate the complete accepted document in p against canonical source s, using the user-request instructions field as the active quality target. Improve materially inaccurate, awkward, inconsistent, or off-target wording. Retain wording that already meets the target when an alternative is not a real improvement. Return a complete replacement document, not a patch, critique, explanation, continuation, or selected-row response. Do not ask questions.";

    public static final String GOOGLE_REFINEMENT_SYSTEM_PROMPT = "Each item's optional p field is a Google Translate draft. Treat canonical source s as authoritative, then refine p into a natural, faithful translation. Keep correct draft wording when it already fits; replace awkward, inaccurate, inconsistent, or overly literal wording. Return a complete translation document, not a critique or patch.";

    public static final String REPAIR_PROMPT = "The prior response violated the JSON or item contract. Return the complete chunk again, satisfying it exactly.";

    private AiContract() {
    }

    /** The deadline one call gets for the output budget it was dispatched with. */
    public static long callDeadlineMs(int maxOutputTokens) {
        long derived = CALL_DEADLINE_BASE_MS
                + (long) Math.max(0, maxOutputTokens) * CALL_DEADLINE_MS_PER_OUTPUT_TOKEN;
        return Math.min(MAX_CALL_DEADLINE_MS, derived);
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
