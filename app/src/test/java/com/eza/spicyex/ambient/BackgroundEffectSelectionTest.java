package com.eza.spicyex.ambient;

import com.eza.spicyex.settings.values.BackgroundEffect;
import com.eza.spicyex.settings.values.BackgroundEffectPool;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class BackgroundEffectSelectionTest {
    private static final class Picks extends Random {
        private final int[] picks;
        int calls;

        Picks(int... picks) {
            this.picks = picks;
        }

        @Override public int nextInt(int bound) {
            int value = picks[Math.min(calls, picks.length - 1)];
            calls++;
            return Math.floorMod(value, bound);
        }
    }

    @Test public void onlyNewSongsRerollRandomAndEmptyMetadataDoesNotResetIdentity() {
        Picks picks = new Picks(0, 1, 2);
        BackgroundEffectSelection selection = new BackgroundEffectSelection(picks);
        BackgroundEffectPool pool = BackgroundEffectPool.of(
                BackgroundEffect.KAWARP, BackgroundEffect.GOD_RAYS,
                BackgroundEffect.MESH_GRADIENT);
        assertEquals(BackgroundEffect.KAWARP,
                selection.resolve("song-a", BackgroundEffect.RANDOM, pool));
        for (int i = 0; i < 10; i++) {
            assertEquals(BackgroundEffect.KAWARP,
                    selection.resolve("song-a", BackgroundEffect.RANDOM, pool));
        }
        selection.resolve("", BackgroundEffect.RANDOM, pool);
        selection.resolve("song-a", BackgroundEffect.RANDOM, pool);
        assertEquals(1, picks.calls);
        assertEquals(BackgroundEffect.MESH_GRADIENT,
                selection.resolve("song-b", BackgroundEffect.RANDOM, pool));
        assertEquals(2, picks.calls);
        assertEquals(BackgroundEffect.GOD_RAYS,
                selection.resolve("song-a", BackgroundEffect.RANDOM, pool));
        assertEquals(3, picks.calls);
    }

    @Test public void randomRepeatsTheSameEffectAcrossSongs() {
        Picks picks = new Picks(1, 1);
        BackgroundEffectSelection selection = new BackgroundEffectSelection(picks);
        BackgroundEffectPool pool = BackgroundEffectPool.of(
                BackgroundEffect.KAWARP, BackgroundEffect.MESH_GRADIENT,
                BackgroundEffect.GOD_RAYS);
        assertEquals(BackgroundEffect.MESH_GRADIENT,
                selection.resolve("song-a", BackgroundEffect.RANDOM, pool));
        assertEquals(BackgroundEffect.MESH_GRADIENT,
                selection.resolve("song-b", BackgroundEffect.RANDOM, pool));
        assertEquals(2, picks.calls);
    }

    @Test public void poolEditsKeepIncludedChoiceAndReplaceExcludedChoiceImmediately() {
        Picks picks = new Picks(0, 0);
        BackgroundEffectSelection selection = new BackgroundEffectSelection(picks);
        BackgroundEffectPool first = BackgroundEffectPool.of(
                BackgroundEffect.KAWARP, BackgroundEffect.MESH_GRADIENT);
        assertEquals(BackgroundEffect.KAWARP,
                selection.resolve("a", BackgroundEffect.RANDOM, first));
        BackgroundEffectPool stillIncludes = BackgroundEffectPool.of(
                BackgroundEffect.KAWARP, BackgroundEffect.GOD_RAYS);
        assertEquals(BackgroundEffect.KAWARP,
                selection.resolve("a", BackgroundEffect.RANDOM, stillIncludes));
        assertEquals(1, picks.calls);
        BackgroundEffectPool excludes = BackgroundEffectPool.of(
                BackgroundEffect.MESH_GRADIENT, BackgroundEffect.GOD_RAYS);
        assertEquals(BackgroundEffect.MESH_GRADIENT,
                selection.resolve("a", BackgroundEffect.RANDOM, excludes));
        assertEquals(2, picks.calls);
    }

    @Test public void fixedChoicesIgnoreRandomPoolAndSongChanges() {
        Picks picks = new Picks(0);
        BackgroundEffectSelection selection = new BackgroundEffectSelection(picks);
        assertEquals(BackgroundEffect.MESH_GRADIENT, selection.resolve(
                "a", BackgroundEffect.MESH_GRADIENT, BackgroundEffectPool.of(BackgroundEffect.KAWARP)));
        assertEquals(BackgroundEffect.GOD_RAYS, selection.resolve(
                "b", BackgroundEffect.GOD_RAYS, BackgroundEffectPool.DEFAULT));
        assertEquals(BackgroundEffect.KAWARP, selection.resolve(
                "b", BackgroundEffect.KAWARP, BackgroundEffectPool.of(BackgroundEffect.MESH_GRADIENT)));
        assertEquals(0, picks.calls);
    }

    @Test public void aSingleEffectPoolCannotLoseItsLastEffect() {
        BackgroundEffectPool pool = BackgroundEffectPool.of(BackgroundEffect.GOD_RAYS);
        assertSame(pool, pool.toggle(BackgroundEffect.GOD_RAYS));
        assertTrue(pool.includes(BackgroundEffect.GOD_RAYS));
        BackgroundEffectPool next = pool.toggle(BackgroundEffect.MESH_GRADIENT);
        assertTrue(next.includes(BackgroundEffect.GOD_RAYS));
        assertTrue(next.includes(BackgroundEffect.MESH_GRADIENT));
    }
}
