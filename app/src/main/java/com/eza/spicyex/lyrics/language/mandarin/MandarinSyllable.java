package com.eza.spicyex.lyrics.language.mandarin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * One syllable of the portable Mandarin reading contract
 * ({@code cjk-labs.zh.mandarin-reading.v1}).
 *
 * <p>Layer 1 of eza式 revision 3: letters, the real tone and the N flag. {@link #tone()} is the
 * real/citation tone (5 means the character has no tone of its own); {@link #neutral()} says
 * whether the word reduces this position; {@link #alwaysNeutral()} says every whole-token phrase
 * reading is neutral here. Layer 2 consumes only this contract.
 */
public final class MandarinSyllable {
    public static final String STATUS_UNAVAILABLE = "unavailable";

    private final String status;
    private final String reason;
    private final String letters;
    private final int tone;
    private final boolean neutral;
    private final boolean alwaysNeutral;
    private final String basis;
    private final int contextStart;
    private final int contextEnd;
    private final int[] sourceLines;
    private final List<String> alternatives;

    private MandarinSyllable(String status, String reason, String letters, int tone,
                             boolean neutral, boolean alwaysNeutral, String basis,
                             int contextStart, int contextEnd,
                             int[] sourceLines, List<String> alternatives) {
        this.status = status;
        this.reason = reason;
        this.letters = letters;
        this.tone = tone;
        this.neutral = neutral;
        this.alwaysNeutral = alwaysNeutral;
        this.basis = basis;
        this.contextStart = contextStart;
        this.contextEnd = contextEnd;
        this.sourceLines = sourceLines;
        this.alternatives = alternatives;
    }

    public static MandarinSyllable unavailable(String reason) {
        return new MandarinSyllable(STATUS_UNAVAILABLE,
                reason == null || reason.isEmpty()
                        ? "No ranked Mandarin reading in the installed data." : reason,
                null, 0, false, false, null, -1, -1, null, null);
    }

    public boolean isUnavailable() {
        return STATUS_UNAVAILABLE.equals(status);
    }

    public String status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    /** Lowercase pinyin letters without a tone digit; {@code ü} is written {@code ü}. */
    public String letters() {
        return letters;
    }

    /** Citation tone 1-4, or 5 when the character has no tone of its own. */
    public int tone() {
        return tone;
    }

    public boolean neutral() {
        return neutral;
    }

    /**
     * True only when every whole-token phrase reading is neutral at this position (姐姐, 椅子,
     * 耳朵). False when the word can also give the syllable a full tone (想想).
     */
    public boolean alwaysNeutral() {
        return alwaysNeutral;
    }

    public String basis() {
        return basis;
    }

    public boolean hasContext() {
        return contextStart >= 0 && contextEnd > contextStart;
    }

    public int contextStart() {
        return contextStart;
    }

    public int contextEnd() {
        return contextEnd;
    }

    public int[] sourceLines() {
        return sourceLines == null ? null : sourceLines.clone();
    }

    public List<String> alternatives() {
        return alternatives;
    }

    /** The one-number contract digit: the neutral realization wins over the citation tone. */
    public int digit() {
        return neutral ? 5 : tone;
    }

    /** Letters plus the contract digit, e.g. {@code peng2} or {@code you5}. */
    public String numbered() {
        return letters + digit();
    }

    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        if (isUnavailable()) {
            out.addProperty("status", status);
            out.addProperty("reason", reason);
            return out;
        }
        out.addProperty("letters", letters);
        out.addProperty("tone", tone);
        out.addProperty("neutral", neutral);
        if (alwaysNeutral) out.addProperty("alwaysNeutral", true);
        out.addProperty("basis", basis);
        if (alternatives != null) {
            JsonArray values = new JsonArray();
            for (String alternative : alternatives) values.add(alternative);
            out.add("alternatives", values);
        }
        if (hasContext()) {
            JsonObject range = new JsonObject();
            range.addProperty("start", contextStart);
            range.addProperty("end", contextEnd);
            out.add("contextRange", range);
        }
        if (sourceLines != null) {
            JsonArray lines = new JsonArray();
            for (int line : sourceLines) lines.add(line);
            out.add("sourceLines", lines);
        }
        return out;
    }

    static MandarinSyllable fromData(String letters, int tone, boolean neutral, String basis,
                                     int contextStart, int contextEnd, int[] lines,
                                     List<String> alternatives) {
        return fromData(letters, tone, neutral, false, basis, contextStart, contextEnd, lines,
                alternatives);
    }

    static MandarinSyllable fromData(String letters, int tone, boolean neutral,
                                     boolean alwaysNeutral, String basis,
                                     int contextStart, int contextEnd, int[] lines,
                                     List<String> alternatives) {
        return new MandarinSyllable(null, null, letters, tone, neutral, alwaysNeutral, basis,
                contextStart, contextEnd, lines, alternatives);
    }
}
