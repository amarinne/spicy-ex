package com.eza.spicyex.auto;

import com.eza.spicyex.lyrics.AppliedLine;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Duet display policy for Android Auto, ported from HyperGlow's AOD canvas
 * ({@code AodLyricCanvasView.kt} slot conveyor and {@code SpicyBridgeDocumentStore.concurrentRowsAt}).
 *
 * <p>Spicy EX has no speaker lane. Duet sections derive from the existing row plan: a background
 * vocal ({@link AppliedLine#bgLine}) overlapping the primary joins with any positive shared window,
 * while two lead vocals join only when their shared window reaches {@link #MIN_CONCURRENT_OVERLAP_MS}.
 * Sequential lines only touch (the previous row's active end is the next row's start), so their
 * shared window is zero and they never join. Dot rows never join a lyric scene.
 *
 * <p>All members are pure and Android-free so the JVM suite pins the behavior.
 */
public final class AutoDuetLayout {
    /** Sung lines kept on screen at once: the primary plus one overlapping line. */
    public static final int MAX_CONCURRENT_LYRIC_LINES = 2;
    /**
     * Minimum shared window for incidental lead-line overlaps. Explicit backing vocals retain
     * their authored shared window, including short responses below one second.
     */
    public static final long MIN_CONCURRENT_OVERLAP_MS = 1000L;

    private AutoDuetLayout() { }

    /**
     * Identity of one sung line for duet slot memory. Source row ordinals distinguish co-timed
     * lines and survive text corrections and primary/companion role changes.
     */
    public static final class DuetSectionId {
        public final long trackGeneration;
        public final long lineStartMs;
        public final long lineEndMs;
        public final int sourceRowOrdinal;

        public DuetSectionId(long trackGeneration, long lineStartMs, long lineEndMs, int sourceRowOrdinal) {
            this.trackGeneration = trackGeneration;
            this.lineStartMs = lineStartMs;
            this.lineEndMs = lineEndMs;
            this.sourceRowOrdinal = sourceRowOrdinal;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof DuetSectionId)) return false;
            DuetSectionId id = (DuetSectionId) other;
            return trackGeneration == id.trackGeneration && lineStartMs == id.lineStartMs
                    && lineEndMs == id.lineEndMs && sourceRowOrdinal == id.sourceRowOrdinal;
        }

        @Override public int hashCode() {
            int result = Long.hashCode(trackGeneration);
            result = 31 * result + Long.hashCode(lineStartMs);
            result = 31 * result + Long.hashCode(lineEndMs);
            result = 31 * result + sourceRowOrdinal;
            return result;
        }

        @Override public String toString() {
            return trackGeneration + ":" + lineStartMs + "-" + lineEndMs + "#" + sourceRowOrdinal;
        }
    }

    /**
     * Transition pass split for a duet-safe crossfade: sections present in both layouts continue
     * (drawn once from the incoming layout at full opacity, so the survivor never crossfades
     * against itself); outgoing-only sections fade out with the exit pass; incoming-only sections
     * fade in with the enter pass. Per-pass block indices in visual order.
     */
    public static final class TransitionPasses {
        public final Set<Integer> continuingEnterBlocks;
        public final Set<Integer> departingExitBlocks;
        public final Set<Integer> arrivingEnterBlocks;

        TransitionPasses(Set<Integer> continuingEnterBlocks, Set<Integer> departingExitBlocks,
                         Set<Integer> arrivingEnterBlocks) {
            this.continuingEnterBlocks = Collections.unmodifiableSet(continuingEnterBlocks);
            this.departingExitBlocks = Collections.unmodifiableSet(departingExitBlocks);
            this.arrivingEnterBlocks = Collections.unmodifiableSet(arrivingEnterBlocks);
        }
    }

    /**
     * Companion row shown while {@code primaryIndex} shows: the latest-started covering overlap,
     * else the most recently ended overlap (held so both fade out together), else the earliest
     * overlap starting inside the primary window (a pre-join placeholder, invisible until its
     * first sung word). Returns -1 when no duet is active.
     */
    public static int concurrentRowAt(List<AppliedLine> rows, int primaryIndex, long positionMs) {
        if (rows == null || primaryIndex < 0 || primaryIndex >= rows.size()) return -1;
        AppliedLine primary = rows.get(primaryIndex);
        if (primary == null || primary.dotLine) return -1;
        int covering = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (i == primaryIndex) continue;
            AppliedLine row = rows.get(i);
            if (!candidate(row)) continue;
            if (positionMs < row.startMs || positionMs >= row.endMs) continue;
            if (!joinsScene(primary, row)) continue;
            // Latest started wins; the reference stable-sorts, so ties keep row order.
            if (covering < 0 || row.startMs > rows.get(covering).startMs) covering = i;
        }
        if (covering >= 0) return covering;
        int held = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (i == primaryIndex) continue;
            AppliedLine row = rows.get(i);
            if (!candidate(row)) continue;
            if (row.endMs > positionMs) continue;
            if (!joinsScene(primary, row)) continue;
            if (held < 0 || row.endMs > rows.get(held).endMs) held = i;
        }
        if (held >= 0) return held;
        int prejoin = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (i == primaryIndex) continue;
            AppliedLine row = rows.get(i);
            if (!candidate(row)) continue;
            if (row.startMs <= positionMs || row.startMs >= primary.endMs) continue;
            if (!joinsScene(primary, row)) continue;
            if (prejoin < 0 || row.startMs < rows.get(prejoin).startMs) prejoin = i;
        }
        return prejoin;
    }

    /** A companion must be a lyric row with text to show; dot rows never join a lyric scene. */
    private static boolean candidate(AppliedLine row) {
        return row != null && !row.dotLine && row.text != null && !row.text.trim().isEmpty();
    }

    /**
     * Whether two rows share a duet scene: a positive shared active window, reaching
     * {@link #MIN_CONCURRENT_OVERLAP_MS} unless either row is a background vocal.
     */
    static boolean joinsScene(AppliedLine first, AppliedLine second) {
        long overlap = Math.min(first.endMs, second.endMs) - Math.max(first.startMs, second.startMs);
        return overlap > 0 && (first.bgLine || second.bgLine || overlap >= MIN_CONCURRENT_OVERLAP_MS);
    }

    /**
     * Visual section order by slot: lines present in the previous build keep their slot, so a
     * continuing line never moves between sections; newcomers inherit vacated slots in
     * current-list order, so a replacement takes the exiting line's position instead of appending
     * below. A full swap (nothing continues) keeps current order. At most two sections exist.
     */
    public static List<DuetSectionId> assignDuetSlots(List<DuetSectionId> current, List<DuetSectionId> previous) {
        if (current == null) return new ArrayList<>();
        if (current.size() <= 1) return new ArrayList<>(current);
        Map<DuetSectionId, Integer> previousSlot = new HashMap<>(previous == null ? 0 : previous.size());
        if (previous != null) for (int i = 0; i < previous.size(); i++)
            if (!previousSlot.containsKey(previous.get(i))) previousSlot.put(previous.get(i), i);
        boolean[] taken = new boolean[current.size()];
        List<DuetSectionId> slotted = new ArrayList<>(Collections.nCopies(current.size(), null));
        for (int i = 0; i < current.size(); i++) {
            DuetSectionId id = current.get(i);
            Integer slot = previousSlot.get(id);
            if (slot != null && slot < slotted.size() && slotted.get(slot) == null) {
                slotted.set(slot, id);
                taken[i] = true;
            }
        }
        int free = 0;
        for (int i = 0; i < current.size(); i++) {
            if (taken[i]) continue;
            while (free < slotted.size() && slotted.get(free) != null) free++;
            if (free < slotted.size()) {
                slotted.set(free, current.get(i));
                free++;
            }
        }
        List<DuetSectionId> result = new ArrayList<>(current.size());
        for (DuetSectionId id : slotted) if (id != null) result.add(id);
        return result;
    }

    /**
     * Transition pass split for a join/leave/replacement. A continuing section whose wrapped-line
     * count changed re-presented (e.g. an unwrap upgrade at a duet join): survivor-once would swap
     * it instantly, so instead it crossfades — fading out in the exit pass and fading in with the
     * enter pass. Without line-count inputs the classification falls back to pure section identity.
     */
    public static TransitionPasses resolveDuetTransitionPasses(List<DuetSectionId> exitIds,
                                                               List<DuetSectionId> enterIds,
                                                               List<Integer> exitLineCounts,
                                                               List<Integer> enterLineCounts) {
        if (exitIds == null) exitIds = new ArrayList<>();
        if (enterIds == null) enterIds = new ArrayList<>();
        Set<DuetSectionId> exitSet = new HashSet<>(exitIds);
        Set<DuetSectionId> continuing = new HashSet<>();
        for (int enterIndex = 0; enterIndex < enterIds.size(); enterIndex++) {
            DuetSectionId id = enterIds.get(enterIndex);
            if (!exitSet.contains(id)) continue;
            int exitIndex = exitIds.indexOf(id);
            Integer exitCount = exitLineCounts == null || exitIndex < 0 || exitIndex >= exitLineCounts.size()
                    ? null : exitLineCounts.get(exitIndex);
            Integer enterCount = enterLineCounts == null || enterIndex >= enterLineCounts.size()
                    ? null : enterLineCounts.get(enterIndex);
            boolean presentationChanged = exitCount != null && enterCount != null && !exitCount.equals(enterCount);
            if (!presentationChanged) continuing.add(id);
        }
        Set<Integer> continuingEnterBlocks = new HashSet<>();
        Set<Integer> departingExitBlocks = new HashSet<>();
        Set<Integer> arrivingEnterBlocks = new HashSet<>();
        for (int i = 0; i < enterIds.size(); i++)
            (continuing.contains(enterIds.get(i)) ? continuingEnterBlocks : arrivingEnterBlocks).add(i);
        for (int i = 0; i < exitIds.size(); i++)
            if (!continuing.contains(exitIds.get(i))) departingExitBlocks.add(i);
        return new TransitionPasses(continuingEnterBlocks, departingExitBlocks, arrivingEnterBlocks);
    }

    /**
     * Visual block indices whose first sung word still lies ahead of the playhead: pre-join
     * placeholders that reserve layout and slot space but stay invisible until their window starts.
     * Solo scenes never defer.
     */
    public static Set<Integer> deferredDuetBlockIndices(List<Long> blockStartMs, long positionMs) {
        if (blockStartMs == null || blockStartMs.size() <= 1) return Collections.emptySet();
        Set<Integer> deferred = new HashSet<>();
        for (int i = 0; i < blockStartMs.size(); i++)
            if (blockStartMs.get(i) != null && blockStartMs.get(i) > positionMs) deferred.add(i);
        return deferred;
    }

    /** After a duet ends, the lone survivor recenters instead of retaining its duet slot. */
    public static boolean shouldRecenterAfterDuet(boolean wasDuet, int sectionCount) {
        return wasDuet && sectionCount == 1;
    }

    /** A join, leave, or replacement disables directional slide; the scene crossfades instead. */
    public static boolean isDuetTransition(int exitBlockCount, int enterBlockCount) {
        return exitBlockCount > 1 || enterBlockCount > 1;
    }
}
