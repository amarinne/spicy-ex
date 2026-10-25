package com.eza.spicyex.ambient;

import com.eza.spicyex.settings.values.BackgroundEffect;
import com.eza.spicyex.settings.values.BackgroundEffectPool;
import java.util.Random;

/** Playback-owned resolution. View recreation, refreshes and documents never reroll the current song. */
public final class BackgroundEffectSelection {
    private final Random random;
    private String trackUri = "";
    private BackgroundEffect mode;
    private BackgroundEffect selected;

    public BackgroundEffectSelection() { this(new Random()); }
    public BackgroundEffectSelection(Random random) { this.random = random; }

    public BackgroundEffect resolve(String uri, BackgroundEffect nextMode,
            BackgroundEffectPool pool) {
        BackgroundEffect targetMode = nextMode == null ? BackgroundEffect.DEFAULT : nextMode;
        BackgroundEffectPool targetPool = pool == null ? BackgroundEffectPool.DEFAULT : pool;
        boolean changedSong = uri != null && !uri.isEmpty() && !uri.equals(trackUri);
        if (uri != null && !uri.isEmpty()) trackUri = uri;
        if (targetMode.isRandom()) {
            if (mode != targetMode || selected == null || changedSong
                    || !targetPool.includes(selected)) {
                selected = targetPool.pick(random);
            }
        } else {
            selected = targetMode;
        }
        mode = targetMode;
        return selected;
    }
}
