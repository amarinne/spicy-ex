package com.eza.spicyex.lyrics.language.mandarin;

import java.text.Normalizer;
import java.util.List;

/**
 * Renders one reading line as the Mandarin eza式 display string, layers 2-4 of revision
 * {@code eza-2026-11-15.3}: sandhi, the line-wide tone-4 pass, then spelling and marks.
 */
public final class MandarinEza {
    /** Shared revision and adapter identity of the Mandarin eza式 layers. */
    public static final String REVISION = MandarinSandhi.REVISION;
    public static final String ADAPTER_REVISION = REVISION + "/adapter-2026-11-15.1";
    public static final String PROVIDER_VERSION = "0.7.0";

    private MandarinEza() {
    }

    /** Layers 2-4 over the line. Call this from a whole-line renderer: layer 3 needs adjacency. */
    public static String render(MandarinReadingLine line, boolean tones) {
        return render(line, MandarinSandhi.surfaceAndRealize(line), tones);
    }

    /** Layer 4 only, over an already realized surface. */
    public static String render(MandarinReadingLine line, List<List<String>> surface, boolean tones) {
        StringBuilder out = new StringBuilder();
        boolean previous = false;
        for (int index = 0; index < line.tokens().size(); index++) {
            MandarinToken token = line.tokens().get(index);
            if (!"han".equals(token.kind())) {
                out.append(token.text());
                previous = false;
                continue;
            }
            if (previous) out.append(' ');
            previous = true;
            List<String> spoken = surface == null ? null : surface.get(index);
            for (int at = 0; at < token.syllables().size(); at++) {
                if (at > 0) out.append(' ');
                MandarinSyllable syllable = token.syllables().get(at);
                if (syllable.isUnavailable()) {
                    out.append('?');
                    continue;
                }
                String canonical = spoken != null && at < spoken.size()
                        ? spoken.get(at) : syllable.letters() + syllable.digit();
                out.append(MandarinSpelling.spell(canonical, tones));
            }
        }
        return Normalizer.normalize(out.toString(), Normalizer.Form.NFC);
    }
}
