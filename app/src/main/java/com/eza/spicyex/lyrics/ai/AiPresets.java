package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.LayerKind;

/** Small immutable preset catalog shared by settings and the native composer. */
public final class AiPresets {
    private AiPresets() {
    }

    public static String[] names(LayerKind layer) {
        return layer == LayerKind.SOUND
                ? new String[]{"Readable pronunciation", "Source-close pronunciation", "Mixed language"}
                : new String[]{"Natural and contextual", "Faithful", "Mixed language", "Cultural nuance"};
    }

    public static String instructions(LayerKind layer, String name) {
        if (layer == LayerKind.SOUND) {
            if ("Source-close pronunciation".equals(name)) {
                return "Stay close to the source pronunciation. Avoid substitutions made only to resemble an English spelling convention, while remaining readable in the requested target orthography.";
            }
            if ("Mixed language".equals(name)) {
                return "Handle each language segment independently. Preserve text already readable in the target orthography and keep code-switching, names, and repeated phrases consistent.";
            }
            return "Use clear, readable pronunciation in the requested target orthography. Keep spelling and word boundaries consistent across repeated phrases and names.";
        }
        if ("Faithful".equals(name)) {
            return "Stay close to the source meaning without becoming mechanical. Preserve ambiguity, repetition, imagery, and emotional intensity when the source supports them.";
        }
        if ("Mixed language".equals(name)) {
            return "Review every phrase independently. Translate phrases that need translation, preserve intentional code-switching, and keep mixed-language lines natural as a whole.";
        }
        if ("Cultural nuance".equals(name)) {
            return "Preserve cultural references, honorifics, slang, dialect, and implied meaning. Prefer an equivalent natural effect over a mechanical word-for-word rendering.";
        }
        return "Make the complete output natural and contextual. Improve materially inaccurate, awkward, or inconsistent wording while retaining passages that already work when an alternative would not be better. Keep the song's tone, register, cultural nuance, and mixed-language phrasing.";
    }

    /** Exact match only. A user-edited preset is custom and must not be silently relabelled. */
    public static String matchingName(LayerKind layer, String instructions) {
        String value = instructions == null ? "" : instructions;
        for (String name : names(layer)) {
            if (instructions(layer, name).equals(value)) return name;
        }
        return null;
    }
}
