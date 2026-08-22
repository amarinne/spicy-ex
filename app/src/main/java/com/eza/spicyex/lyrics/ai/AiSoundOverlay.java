package com.eza.spicyex.lyrics.ai;

import com.eza.spicyex.lyrics.session.SoundEntry;
import com.eza.spicyex.lyrics.reading.ReadingModels.ReadingUnit;
import com.eza.spicyex.lyrics.reading.ReadingPlanFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How AI pronunciation enters the Sound layer, and what it is not allowed to touch.
 *
 * <p>AI output is <b>line-level by construction</b>. It is never parsed into spans, never split on
 * whitespace to guess syllable boundaries, and never merged into an existing multi-span plan. That
 * is not a limitation to be lifted later: a model returns a reading for a line, and any attempt to
 * distribute that across word-timed spans would be inventing alignment evidence that does not
 * exist. The entries this class produces carry display text and nothing else, so there is no code
 * path by which AI output could acquire spans.
 *
 * <p>Authority inside the line-level slot is <b>deterministic &gt; AI &gt; Google</b>. A row the
 * deterministic pipeline covered keeps exactly what that pipeline produced — years of curated
 * reading rules, furigana, and timed alignment are not replaced by a model's opinion. A row it
 * could not cover is a gap, and there Google fills first because it is fast and already wired,
 * with AI taking the slot when its answer lands.
 */
public final class AiSoundOverlay {

    private AiSoundOverlay() {
    }

    /**
     * True when the deterministic reading pipeline produced real structure for this row.
     *
     * <p>Decided from what only that pipeline can produce — a Japanese reading, per-span readings,
     * or a structured plan with timed units. A synthesized whole-line fallback may also be stored
     * as a {@code RenderPlan}; its single {@code line-fallback} unit is still replaceable by AI.
     */
    public static boolean hasDeterministicCoverage(SoundEntry entry) {
        if (entry == null) return false;
        if (entry.japaneseReading != null) return true;
        if (!entry.spanReadings.isEmpty()) return true;
        if (isWholeLineFallback(entry)) return false;
        if (entry.renderPlan == null) return false;
        if (!entry.renderPlan.readingUnits.isEmpty()) {
            return ReadingPlanFactory.hasTransformedReading(entry.renderPlan);
        }
        // Compatibility for old structured caches that predate serialized ReadingUnits.
        return !entry.renderPlan.timedReadingUnits.isEmpty();
    }

    private static boolean isWholeLineFallback(SoundEntry entry) {
        if (entry == null || entry.renderPlan == null
                || entry.renderPlan.readingUnits.size() != 1) {
            return false;
        }
        ReadingUnit unit = entry.renderPlan.readingUnits.get(0);
        return unit != null && "line-fallback".equals(unit.logicalGroupId);
    }

    /**
     * Composes an AI overlay over whatever the Sound lane already produced.
     *
     * <p>Row order follows the base, with overlay-only rows appended in their own order, so the
     * result is deterministic regardless of the order the two artifacts completed in.
     *
     * @param base    deterministic and Google output for this canonical base, or null
     * @param overlay accepted AI readings, addressed by canonical row ID, or null
     * @return the composed rows; never null
     */
    public static List<SoundEntry> compose(List<SoundEntry> base, List<SoundEntry> overlay) {
        Map<String, SoundEntry> overlayByRow = new LinkedHashMap<>();
        if (overlay != null) {
            for (SoundEntry entry : overlay) {
                if (entry != null && !entry.rowId.isEmpty()) overlayByRow.put(entry.rowId, entry);
            }
        }

        List<SoundEntry> composed = new ArrayList<>();
        if (base != null) {
            for (SoundEntry entry : base) {
                if (entry == null) continue;
                SoundEntry replacement = overlayByRow.remove(entry.rowId);
                composed.add(replacement != null && !hasDeterministicCoverage(entry)
                        ? replacement : entry);
            }
        }
        // Rows the base never covered at all: a gap AI filled outright.
        composed.addAll(overlayByRow.values());
        return composed;
    }

    /**
     * Turns accepted AI items into Sound entries.
     *
     * <p>Built through {@link SoundEntry#line}, which carries display text and no plan, no span
     * readings, and no Japanese reading. That is the enforcement of the line-level rule: the
     * overlay physically cannot describe span alignment.
     *
     * @param mode output orthography the readings were produced in
     */
    public static List<SoundEntry> entriesOf(List<AiResponseItem> items, String mode) {
        if (items == null) return Collections.emptyList();
        List<SoundEntry> entries = new ArrayList<>(items.size());
        for (AiResponseItem item : items) {
            if (item == null || item.id.isEmpty()) continue;
            if (AiText.trim(item.text).isEmpty()) continue;
            entries.add(SoundEntry.line(item.id, item.text, AiText.nz(mode)));
        }
        return entries;
    }
}
