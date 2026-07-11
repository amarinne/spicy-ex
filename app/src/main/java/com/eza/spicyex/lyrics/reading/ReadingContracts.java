package com.eza.spicyex.lyrics.reading;

import java.util.List;
import java.util.Map;

import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.LanguageContext;
import com.eza.spicyex.lyrics.reading.ReadingModels.ParsedDocument;
import com.eza.spicyex.lyrics.reading.ReadingModels.ParsedLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingAnnotation;
import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;
import com.eza.spicyex.lyrics.reading.ReadingModels.ScriptRun;
import com.eza.spicyex.lyrics.reading.ReadingModels.ValidationResult;

public final class ReadingContracts {
    private ReadingContracts() {}

    public interface ProviderAdapter<I> { ParsedDocument parse(I input); }
    public interface CanonicalLineBuilder { CanonicalLine build(ParsedLine line); }
    public interface ScriptPartitioner { List<ScriptRun> partition(CanonicalLine line, LanguageContext context); }
    public interface ReadingProcessor {
        boolean supports(ScriptRun run, LanguageContext context);
        ReadingAnnotation annotate(CanonicalLine line, ScriptRun run, Map<String, Object> options);
    }
    public interface ReadingPlanValidator { ValidationResult validate(CanonicalLine line, ReadingAnnotation annotation); }
    public interface RenderPlanBuilder {
        RenderPlan build(ParsedLine line, CanonicalLine canonical, List<ReadingAnnotation> annotations);
    }
}
