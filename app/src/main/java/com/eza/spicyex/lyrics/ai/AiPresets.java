package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;

/** Small immutable preset catalog shared by settings and the native composer. */
public final class AiPresets {
    private AiPresets() {
    }

    public static String[] names(LayerKind layer) {
        return layer == LayerKind.SOUND
                ? new String[]{"Readable", "Source-close"}
                : new String[]{"Natural", "Faithful", "Creative"};
    }

    public static String instructions(LayerKind layer, String name) {
        if (layer == LayerKind.SOUND) {
            if ("Source-close".equals(name)) {
                return "Stay close to the source pronunciation. Avoid substitutions made only to resemble an English spelling convention, while remaining readable in the requested target orthography.";
            }
            return "Use clear, readable pronunciation in the requested target orthography. Keep spelling and word boundaries consistent across repeated phrases and names.";
        }
        if ("Creative".equals(name)) {
            return CREATIVE;
        }
        if ("Faithful".equals(name)) {
            return "Stay close to the source meaning without becoming mechanical. Preserve ambiguity, repetition, imagery, and emotional intensity when the source supports them.";
        }
        return "Make the complete output natural and contextual. Improve materially inaccurate, awkward, or inconsistent wording while retaining passages that already work when an alternative would not be better. Keep the song's tone, register, cultural nuance, and mixed-language phrasing.";
    }

    private static final String NEUTRAL_PERSON_BLOCK =
            "Track who speaks, who is addressed, and who is mentioned across the whole song, not "
                    + "line by line. Where the source leaves a person, number, gender, or relation "
                    + "unstated or ambiguous, decide it from evidence in the lyrics: verb forms, "
                    + "giving and receiving direction, honorifics, kin terms, names, and gendered "
                    + "agreement. Use the same decision for every occurrence, including repeated "
                    + "lines. When the text gives no evidence, do not assert a gender or "
                    + "relationship; keep wording true to both readings. Do not add or drop "
                    + "pronouns, articles, or honorifics by rule; follow what reads naturally in "
                    + "the target style.";

    private static final String CREATIVE =
            "Write a polished lyric adaptation in the target language, not a gloss. Before "
                    + "writing, plan the whole song once: speaker, addressee, relationship, "
                    + "register, recurring images, and repeated lines. Do not draft rows in the "
                    + "plan; keep those decisions fixed for every row."
                    + "\n\nPreserve names, actions, relationships, gender shown by source grammar, "
                    + "emotional force, explicitness, humor, and ambiguity. Reshape syntax, idiom, "
                    + "metaphor, and diction so each row sounds written in the target language "
                    + "while the row keeps its dramatic function. Add no event, object, motive, "
                    + "subject, or certainty the song does not support, and do not soften explicit "
                    + "lines or change the relationship's gender."
                    + "\n\nChoose pronouns and address forms from the target language's song "
                    + "conventions, not everyday conversation. Use one address policy for the "
                    + "whole song. Gender shown by the source outranks convention."
                    + "\n\nKeep the order of ideas close to the source, keep each row close to its "
                    + "source length, and do not move meaning between rows."
                    + "\n\nDecide the function of each repeated line. Keep chants, hooks, and title "
                    + "drops identical. Vary a repeated line only when the song's feeling clearly "
                    + "develops, changing one dimension at a time and keeping its length and shape."
                    + "\n\nKeep vocables, ad-libs, names, and intentional code-switching unchanged. "
                    + "Use at most one or two creative turns per row. Do not add target-language "
                    + "phrases the source does not contain."
                    + "\n\n" + NEUTRAL_PERSON_BLOCK;

    /** Exact match only. A user-edited preset is custom and must not be silently relabelled. */
    public static String matchingName(LayerKind layer, String instructions) {
        String value = instructions == null ? "" : instructions;
        for (String name : names(layer)) {
            if (instructions(layer, name).equals(value)) return name;
        }
        return null;
    }
}
