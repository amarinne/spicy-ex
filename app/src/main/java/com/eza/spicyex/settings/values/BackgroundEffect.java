package com.eza.spicyex.settings.values;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The user-selected background effect. Fixed effects persist by stable shader id; Random resolves
 * once per song from the nonempty pool.
 */
public enum BackgroundEffect implements StoredValue {
    KAWARP("kawarp", "Kawarp", "Kawarp"),
    MESH_GRADIENT("mesh-gradient", "Mesh Gradient", "Mesh Gradient"),
    SIMPLEX_NOISE("simplex-noise", "Simplex Noise"),
    DOT_ORBIT("dot-orbit", "Dot Orbit"),
    GOD_RAYS("god-rays", "God Rays"),
    VORONOI("voronoi", "Voronoi"),
    SWIRL("swirl", "Swirl"),
    NEURO_NOISE("neuro-noise", "Neuro Noise"),
    PERLIN_NOISE("perlin-noise", "Perlin Noise"),
    COLOR_PANELS("color-panels", "Color Panels"),
    WATER("water", "Water"),
    GEM_SMOKE("gem-smoke", "Gem Smoke"),
    SMOKE_RING("smoke-ring", "Smoke Ring"),
    WARP("warp", "Warp"),
    METABALLS("metaballs", "Metaballs"),
    PULSING_BORDER("pulsing-border", "Pulsing Border"),
    GRAIN_GRADIENT("grain-gradient", "Grain Gradient"),
    RANDOM("random", "Random", "Random");

    /** The value used when the stored label is unknown or absent. */
    public static final BackgroundEffect DEFAULT = KAWARP;

    private final String stableId;
    private final String displayLabel;
    private final List<String> legacyLabels;

    BackgroundEffect(String stableId, String displayLabel, String... legacyLabels) {
        this.stableId = stableId;
        this.displayLabel = displayLabel;
        this.legacyLabels = Collections.unmodifiableList(Arrays.asList(legacyLabels));
    }

    @Override
    public String storedLabel() {
        return stableId;
    }

    @Override
    public boolean isDefault() {
        return this == DEFAULT;
    }

    @Override
    public List<String> legacyLabels() {
        return legacyLabels;
    }

    /** The stable shader id; Random has no shader of its own. */
    public String stableId() {
        return stableId;
    }

    /** The English fallback label; resources localize the display. */
    public String displayLabel() {
        return displayLabel;
    }

    public boolean isRandom() {
        return this == RANDOM;
    }

    public boolean isFixed() {
        return this != RANDOM;
    }

    /** The user-facing fixed choices. Static catalog entries are pruned except album-art Dithering. */
    public static List<BackgroundEffect> fixedValues() {
        List<BackgroundEffect> values = new ArrayList<>();
        for (BackgroundEffect effect : values()) {
            if (effect.isFixed()) values.add(effect);
        }
        return Collections.unmodifiableList(values);
    }

    /** The selectable Paper shaders, excluding Kawarp and Random. */
    public static List<BackgroundEffect> paperValues() {
        List<BackgroundEffect> values = new ArrayList<>();
        for (BackgroundEffect effect : values()) {
            if (effect != KAWARP && effect.isFixed()) values.add(effect);
        }
        return Collections.unmodifiableList(values);
    }

    /** The picker order: Random first, then the fixed effects in catalog order. */
    public static List<BackgroundEffect> pickerValues() {
        List<BackgroundEffect> values = new ArrayList<>();
        values.add(RANDOM);
        values.addAll(fixedValues());
        return Collections.unmodifiableList(values);
    }

    /** Every canonical stored id, in declaration order. */
    public static List<String> stableIds() {
        List<String> ids = new ArrayList<>();
        for (BackgroundEffect effect : values()) {
            ids.add(effect.stableId);
        }
        return Collections.unmodifiableList(ids);
    }

    /** Canonical ids plus the labels older releases stored. */
    public static List<String> allowedLabels() {
        Set<String> labels = new LinkedHashSet<>(stableIds());
        for (BackgroundEffect effect : values()) labels.addAll(effect.legacyLabels);
        return Collections.unmodifiableList(new ArrayList<>(labels));
    }

    public static String defaultStableId() {
        return DEFAULT.stableId;
    }

    /** A fixed effect by stable id or a display/legacy label; null for Random or unknown input. */
    public static BackgroundEffect fixedFromText(String raw) {
        BackgroundEffect effect = fromText(raw);
        return effect == null || effect.isRandom() ? null : effect;
    }

    /** An effect by stable id or a display/legacy label; null when unknown. */
    public static BackgroundEffect fromText(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        for (BackgroundEffect effect : values()) {
            if (effect.stableId.equals(value)) return effect;
        }
        for (BackgroundEffect effect : values()) {
            if (effect.displayLabel.equalsIgnoreCase(value)) return effect;
            for (String legacy : effect.legacyLabels) {
                if (legacy.equalsIgnoreCase(value)) return effect;
            }
        }
        String normalized = value.toLowerCase(Locale.ROOT).replace('_', '-');
        for (BackgroundEffect effect : values()) {
            if (effect.stableId.equals(normalized)) return effect;
        }
        return null;
    }
}
