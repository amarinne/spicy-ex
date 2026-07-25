package com.eza.spicyex.lyrics.reading;

import com.eza.spicyex.lyrics.reading.ReadingContracts.CanonicalLineBuilder;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalLine;
import com.eza.spicyex.lyrics.reading.ReadingModels.ParsedLine;

public final class DefaultCanonicalLineBuilder implements CanonicalLineBuilder {
    private final ProviderBoundaryResolver resolver = new ProviderBoundaryResolver();

    @Override
    public CanonicalLine build(ParsedLine line) {
        return resolver.resolve(line).canonical;
    }
}
