package com.eza.spicyex.lyrics.reading;

import java.util.ArrayList;
import java.util.List;

import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalSpanMapping;
import com.eza.spicyex.lyrics.reading.ReadingModels.ScriptRun;
import com.eza.spicyex.lyrics.reading.ReadingModels.ValidationResult;

public final class CanonicalValidator {
    private CanonicalValidator() {}

    public static ValidationResult validate(CanonicalLine line, List<ScriptRun> runs) {
        List<String> errors = new ArrayList<>();
        int mappingEnd = 0;
        for (CanonicalSpanMapping mapping : line.spanMappings) {
            if (!CodePointRanges.isValid(line.text, mapping.canonicalRange)) errors.add("invalid mapping:" + mapping.spanId);
            if (mapping.canonicalRange.startCp < mappingEnd) errors.add("overlapping mapping:" + mapping.spanId);
            mappingEnd = mapping.canonicalRange.endCp;
        }
        int runEnd = 0;
        for (ScriptRun run : runs) {
            if (!CodePointRanges.isValid(line.text, run.canonicalRange)) errors.add("invalid run:" + run.script);
            if (run.canonicalRange.startCp != runEnd) errors.add("run gap:" + runEnd);
            runEnd = run.canonicalRange.endCp;
        }
        if (runEnd != CodePointRanges.length(line.text)) errors.add("run coverage:" + runEnd);
        return new ValidationResult(errors.isEmpty(), errors);
    }
}
