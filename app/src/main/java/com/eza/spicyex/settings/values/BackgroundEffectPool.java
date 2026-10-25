package com.eza.spicyex.settings.values;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * The nonempty Random pool. It persists canonical stable ids and accepts the labels older releases
 * stored. Removing its last effect leaves the pool unchanged.
 */
public final class BackgroundEffectPool {
    /** The old "Both" default: Kawarp and Mesh Gradient. */
    public static final BackgroundEffectPool DEFAULT =
            new BackgroundEffectPool(Arrays.asList(BackgroundEffect.KAWARP,
                    BackgroundEffect.MESH_GRADIENT));

    private final List<BackgroundEffect> effects;

    private BackgroundEffectPool(List<BackgroundEffect> effects) {
        this.effects = Collections.unmodifiableList(effects);
    }

    public static BackgroundEffectPool of(BackgroundEffect... values) {
        return values == null ? DEFAULT : of(Arrays.asList(values));
    }

    public static BackgroundEffectPool of(Iterable<BackgroundEffect> values) {
        Set<BackgroundEffect> selected = new LinkedHashSet<>();
        if (values != null) {
            for (BackgroundEffect effect : values) {
                if (effect != null && effect.isFixed()) selected.add(effect);
            }
        }
        if (selected.isEmpty()) return DEFAULT;
        List<BackgroundEffect> ordered = new ArrayList<>();
        for (BackgroundEffect effect : BackgroundEffect.fixedValues()) {
            if (selected.contains(effect)) ordered.add(effect);
        }
        return new BackgroundEffectPool(ordered);
    }

    /** A stored pool, including the old Both/Kawarp/Mesh Gradient labels. */
    public static BackgroundEffectPool parse(String stored) {
        if (stored == null) return DEFAULT;
        String value = stored.trim();
        if (value.isEmpty()) return DEFAULT;
        BackgroundEffect single = BackgroundEffect.fromText(value);
        if (single != null && single.isFixed()) return of(single);
        if ("both".equalsIgnoreCase(value)) return DEFAULT;
        Set<BackgroundEffect> selected = new LinkedHashSet<>();
        for (String token : value.split("[,;\\s]+")) {
            BackgroundEffect effect = BackgroundEffect.fromText(token);
            if (effect != null && effect.isFixed()) selected.add(effect);
        }
        return selected.isEmpty() ? DEFAULT : of(selected);
    }

    /** A caller-supplied pool; null when the value is empty, unknown, or names Random. */
    public static BackgroundEffectPool parseExplicit(String csv) {
        if (csv == null || csv.trim().isEmpty()) return null;
        Set<BackgroundEffect> selected = new LinkedHashSet<>();
        for (String token : csv.split(",", -1)) {
            BackgroundEffect effect = BackgroundEffect.fixedFromText(token.trim());
            if (effect == null) return null;
            selected.add(effect);
        }
        return selected.isEmpty() ? null : of(selected);
    }

    public List<BackgroundEffect> effects() {
        return effects;
    }

    public boolean includes(BackgroundEffect effect) {
        return effect != null && effects.contains(effect);
    }

    public int size() {
        return effects.size();
    }

    public boolean isDefault() {
        return DEFAULT.effects.equals(effects);
    }

    /** A uniform pick with replacement, so the same effect may repeat across songs. */
    public BackgroundEffect pick(Random random) {
        return effects.get(random.nextInt(effects.size()));
    }

    public BackgroundEffectPool toggle(BackgroundEffect effect) {
        if (effect == null || !effect.isFixed()) return this;
        List<BackgroundEffect> next = new ArrayList<>(effects);
        if (next.contains(effect)) {
            if (next.size() == 1) return this;
            next.remove(effect);
        } else {
            next.add(effect);
        }
        return of(next);
    }

    /** The canonical comma-separated stable ids. */
    public String encode() {
        StringBuilder out = new StringBuilder();
        for (BackgroundEffect effect : effects) {
            if (out.length() > 0) out.append(',');
            out.append(effect.stableId());
        }
        return out.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BackgroundEffectPool
                && effects.equals(((BackgroundEffectPool) other).effects);
    }

    @Override
    public int hashCode() {
        return Objects.hash(effects);
    }

    @Override
    public String toString() {
        return encode();
    }
}
