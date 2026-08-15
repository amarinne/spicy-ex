package com.eza.spicyex.lyrics;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.eza.spicyex.lyrics.reading.ReadingModels.RenderPlan;
import com.eza.spicyex.lyrics.reading.ReadingModels.CanonicalSpanMapping;
import com.eza.spicyex.lyrics.reading.ReadingModels.TimedReadingUnit;
import com.eza.spicyex.lyrics.reading.CodePointRanges;
import com.eza.spicyex.lyrics.reading.DefaultRenderPlanBuilder;
import com.eza.spicyex.lyrics.session.AIPaidArtifactCache;
import com.eza.spicyex.lyrics.session.CanonicalBase;
import com.eza.spicyex.lyrics.session.CanonicalRow;
import com.eza.spicyex.lyrics.session.DerivedLayerArtifact;
import com.eza.spicyex.lyrics.session.LayerAuthority;
import com.eza.spicyex.lyrics.session.MeaningArtifact;
import com.eza.spicyex.lyrics.session.PaidArtifactIdentity;
import com.eza.spicyex.lyrics.session.MeaningEntry;
import com.eza.spicyex.lyrics.session.SoundArtifact;
import com.eza.spicyex.lyrics.session.SoundEntry;
import com.eza.spicyex.SpotifyPlusConfig;

import de.robv.android.xposed.XposedBridge;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/**
 * Durable per-layer artifact records for reading (Sound) and translation (Meaning).
 *
 * <p>The two layers use separate stores, separate keys, and separate completeness flags: a change
 * to the translation target must not discard a valid reading artifact, and a reading contract bump
 * must not discard translations that cost network requests.
 *
 * <p>Rows are addressed by stable canonical row ID. Positional or count-only restore is never used.
 */
public final class ProcessedLyricsCache {
    private static final String TAG = "[SpotifyPlusProcessedLyricsCache]";
    /** Plan v2 stores one semantic reading authority; legacy strings are cache fallback only. */
    public static final int READING_SCHEMA_VERSION = 3;
    private static final int RECORD_SCHEMA_VERSION = 1;
    private static final Gson GSON = new Gson();

    private ProcessedLyricsCache() {
    }

    /** Outcome of restoring one layer from cache. */
    public static final class Applied {
        public static final Applied NONE = new Applied(false, false, 0);

        /** A compatible record existed and was applied. */
        public final boolean present;
        /** The record claims the layer finished; a partial record still leaves work to do. */
        public final boolean complete;
        public final int rows;

        Applied(boolean present, boolean complete, int rows) {
            this.present = present;
            this.complete = complete;
            this.rows = rows;
        }
    }

    // --- Sound --------------------------------------------------------------

    public static Applied applySound(Context context, LyricsDocument doc, CanonicalBase base,
                                     String soundConfigId) {
        JsonObject record = read(context, true, base, soundConfigId);
        if (record == null) return Applied.NONE;
        JsonObject rows = Json.optObject(record, "rows");
        if (rows == null) return Applied.NONE;
        int applied = 0;
        for (CanonicalRow row : base.rows) {
            LyricsLine line = lineFor(doc, row);
            if (line == null) continue;
            JsonObject item = Json.optObject(rows, row.rowId);
            if (item == null || !safe(line.text).equals(Json.optString(item, "text"))) continue;
            if (applySoundRow(line, item, record)) applied++;
        }
        XposedBridge.log(TAG + " sound applied rows=" + applied + "/" + base.rows.size()
                + " complete=" + Json.optBoolean(record, false, "complete"));
        return new Applied(applied > 0, Json.optBoolean(record, false, "complete"), applied);
    }

    public static boolean saveSound(Context context, LyricsDocument doc, CanonicalBase base,
                                    String soundConfigId, boolean complete) {
        if (context == null || doc == null || base == null || base.isEmpty()) return false;
        try {
            JsonObject rows = new JsonObject();
            for (CanonicalRow row : base.rows) {
                LyricsLine line = lineFor(doc, row);
                if (line == null) continue;
                boolean hasPlan = line.readingRenderPlan != null;
                boolean hasLegacy = !isBlank(line.romanizedText);
                boolean hasReading = line.japaneseReading != null;
                if (!hasPlan && !hasLegacy && !hasReading && isBlank(line.chineseMode)) continue;
                JsonObject item = new JsonObject();
                item.addProperty("text", safe(line.text));
                // A valid plan owns displayed reading text. Write legacy romaji only when no plan
                // exists, so restore cannot combine two independently-derived projections.
                if (!hasPlan && hasLegacy) item.addProperty("romanizedText", line.romanizedText);
                if (!isBlank(line.chineseMode)) {
                    item.addProperty("chineseMode", normalizeChineseMode(line.chineseMode));
                }
                if (hasReading) item.add("JapaneseReading", japaneseReadingToJson(line.japaneseReading));
                if (hasPlan) item.add("readingRenderPlan", renderPlanToJson(line.readingRenderPlan));
                rows.add(row.rowId, item);
            }
            if (rows.size() == 0) return false;
            JsonObject record = newRecord("SOUND", base, soundConfigId, complete);
            record.add("rows", rows);
            LyricCaches.putSoundArtifact(context,
                    LyricCaches.soundArtifactKey(base.digest, soundConfigId), record.toString());
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " sound save failed: " + t);
            return false;
        }
    }

    /**
     * Persists a Sound artifact directly, without going through a document.
     *
     * <p>The artifact carries its own canonical digest and configuration, so the record is written
     * under the identity the lane actually produced it with rather than whatever the caller happens
     * to compute. {@code base} supplies each row's source text, which restore validates against.
     */
    public static boolean saveSound(Context context, CanonicalBase base, SoundArtifact artifact) {
        if (context == null || base == null || artifact == null || artifact.isEmpty()) return false;
        if (!artifact.canonicalDigest.equals(base.digest)) return false;
        try {
            JsonObject rows = new JsonObject();
            for (CanonicalRow row : base.rows) {
                SoundEntry entry = artifact.sound(row.rowId);
                if (entry == null) continue;
                boolean hasPlan = entry.renderPlan != null;
                boolean hasLegacy = !isBlank(entry.displayText);
                if (!hasPlan && !hasLegacy && entry.japaneseReading == null && isBlank(entry.mode)) {
                    continue;
                }
                JsonObject item = new JsonObject();
                item.addProperty("text", row.text);
                // A valid plan owns displayed reading text; a legacy string beside it would give
                // restore two independently-derived projections to choose between.
                if (!hasPlan && hasLegacy) item.addProperty("romanizedText", entry.displayText);
                if (!isBlank(entry.mode)) item.addProperty("chineseMode", normalizeChineseMode(entry.mode));
                if (entry.japaneseReading != null) {
                    item.add("JapaneseReading", japaneseReadingToJson(entry.japaneseReading));
                }
                if (hasPlan) item.add("readingRenderPlan", renderPlanToJson(entry.renderPlan));
                rows.add(row.rowId, item);
            }
            if (rows.size() == 0) return false;
            JsonObject record = newRecordHeader("SOUND", base.digest, artifact.configId, !artifact.partial);
            record.add("rows", rows);
            if (isPaid(artifact)) return persistPaid(context, artifact, record);
            LyricCaches.putSoundArtifact(context,
                    LyricCaches.soundArtifactKey(base.digest, artifact.configId), record.toString());
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " sound save failed: " + t);
            return false;
        }
    }

    /** Persists a Meaning artifact directly, without going through a document. */
    public static boolean saveMeaning(Context context, CanonicalBase base, MeaningArtifact artifact) {
        if (context == null || base == null || artifact == null || artifact.isEmpty()) return false;
        if (!artifact.canonicalDigest.equals(base.digest)) return false;
        try {
            JsonObject rows = new JsonObject();
            for (CanonicalRow row : base.rows) {
                MeaningEntry entry = artifact.meaning(row.rowId);
                if (entry == null || isBlank(entry.text)) continue;
                JsonObject item = new JsonObject();
                item.addProperty("text", row.text);
                item.addProperty("translatedText", entry.text);
                rows.add(row.rowId, item);
            }
            if (rows.size() == 0) return false;
            JsonObject record = newRecordHeader("MEANING", base.digest, artifact.configId, !artifact.partial);
            record.add("rows", rows);
            if (isPaid(artifact)) return persistPaid(context, artifact, record);
            LyricCaches.putMeaningArtifact(context,
                    LyricCaches.meaningArtifactKey(base.digest, artifact.configId), record.toString());
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " meaning save failed: " + t);
            return false;
        }
    }

    // --- Meaning ------------------------------------------------------------

    public static Applied applyMeaning(Context context, LyricsDocument doc, CanonicalBase base,
                                       String meaningConfigId) {
        JsonObject record = read(context, false, base, meaningConfigId);
        if (record == null) return Applied.NONE;
        JsonObject rows = Json.optObject(record, "rows");
        if (rows == null) return Applied.NONE;
        int applied = 0;
        for (CanonicalRow row : base.rows) {
            LyricsLine line = lineFor(doc, row);
            if (line == null) continue;
            JsonObject item = Json.optObject(rows, row.rowId);
            if (item == null || !safe(line.text).equals(Json.optString(item, "text"))) continue;
            String translated = Json.optString(item, "translatedText");
            if (isBlank(translated)) continue;
            line.translatedText = translated;
            applied++;
        }
        XposedBridge.log(TAG + " meaning applied rows=" + applied + "/" + base.rows.size()
                + " complete=" + Json.optBoolean(record, false, "complete"));
        return new Applied(applied > 0, Json.optBoolean(record, false, "complete"), applied);
    }

    public static boolean saveMeaning(Context context, LyricsDocument doc, CanonicalBase base,
                                      String meaningConfigId, boolean complete) {
        if (context == null || doc == null || base == null || base.isEmpty()) return false;
        try {
            JsonObject rows = new JsonObject();
            for (CanonicalRow row : base.rows) {
                LyricsLine line = lineFor(doc, row);
                if (line == null || isBlank(line.translatedText)) continue;
                JsonObject item = new JsonObject();
                item.addProperty("text", safe(line.text));
                item.addProperty("translatedText", line.translatedText);
                rows.add(row.rowId, item);
            }
            if (rows.size() == 0) return false;
            JsonObject record = newRecord("MEANING", base, meaningConfigId, complete);
            record.add("rows", rows);
            LyricCaches.putMeaningArtifact(context,
                    LyricCaches.meaningArtifactKey(base.digest, meaningConfigId), record.toString());
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " meaning save failed: " + t);
            return false;
        }
    }

    // --- shared -------------------------------------------------------------

    /**
     * True when this artifact is AI-authored, and so must not be written to a store that evicts.
     *
     * <p>The Sound and Meaning stores are LRU- and age-bounded and are emptied by a deploy epoch.
     * That is correct for work that can be redone for free; it would silently throw away work the
     * owner paid for.
     */
    public static boolean isPaid(DerivedLayerArtifact artifact) {
        return artifact != null && artifact.provenance != null
                && artifact.provenance.authority == LayerAuthority.AI;
    }

    private static boolean persistPaid(Context context, DerivedLayerArtifact artifact,
                                       JsonObject record) {
        PaidArtifactIdentity identity = PaidArtifactIdentity.forArtifact(artifact);
        if (identity == null) {
            // AI-authored but unaddressable: no provider, model, or prompt contract to key it by.
            // Refusing beats writing it somewhere it can be evicted without anyone noticing.
            XposedBridge.log(TAG + " paid save refused: incomplete provenance kind=" + artifact.kind);
            return false;
        }
        AIPaidArtifactCache.Write write = AIPaidArtifactCache.put(context, identity, record.toString());
        if (!write.durable) {
            XposedBridge.log(TAG + " paid save not durable reason=" + write.reason
                    + " layer=" + identity.layerKind);
        }
        return write.durable;
    }

    private static JsonObject newRecord(String kind, CanonicalBase base, String configId, boolean complete) {
        return newRecordHeader(kind, base.digest, configId, complete);
    }

    private static JsonObject read(Context context, boolean sound, CanonicalBase base, String configId) {
        if (context == null || base == null || base.isEmpty()) return null;
        try {
            String raw = sound
                    ? LyricCaches.getSoundArtifact(context, LyricCaches.soundArtifactKey(base.digest, configId))
                    : LyricCaches.getMeaningArtifact(context, LyricCaches.meaningArtifactKey(base.digest, configId));
            if (isBlank(raw)) return null;
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) return null;
            JsonObject record = parsed.getAsJsonObject();
            // An artifact applies only to its own base and configuration. For Sound this is the
            // whole isolation guarantee, because the key carries the digest alone.
            if (!recordMatches(record, base.digest, configId)) return null;
            return record;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " read failed: " + t);
            return null;
        }
    }

    /** True when a stored record was produced for exactly this base and layer configuration. */
    static boolean recordMatches(JsonObject record, String canonicalDigest, String configId) {
        if (record == null) return false;
        if ((int) Json.optDouble(record, -1, "schema") != RECORD_SCHEMA_VERSION) return false;
        if (!safe(canonicalDigest).equals(Json.optString(record, "canonicalDigest"))) return false;
        return safe(configId).equals(Json.optString(record, "configId"));
    }

    /** Exposed for tests: builds the header a stored record is validated against. */
    static JsonObject newRecordHeader(String kind, String canonicalDigest, String configId, boolean complete) {
        JsonObject record = new JsonObject();
        record.addProperty("schema", RECORD_SCHEMA_VERSION);
        record.addProperty("kind", kind);
        record.addProperty("canonicalDigest", safe(canonicalDigest));
        record.addProperty("configId", safe(configId));
        record.addProperty("readingSchemaVersion", READING_SCHEMA_VERSION);
        record.addProperty("complete", complete);
        return record;
    }

    private static LyricsLine lineFor(LyricsDocument doc, CanonicalRow row) {
        if (doc == null || row == null || row.index < 0 || row.index >= doc.lines.size()) return null;
        return doc.lines.get(row.index);
    }

    private static boolean applySoundRow(LyricsLine line, JsonObject item, JsonObject record) {
        boolean compatibleReadingSchema =
                (int) Json.optDouble(record, -1, "readingSchemaVersion") == READING_SCHEMA_VERSION;
        String cnMode = Json.optString(item, "chineseMode");
        if (!isBlank(cnMode)) line.chineseMode = normalizeChineseMode(cnMode);
        SpicyJapaneseChineseProcessor.JapaneseReading reading = LyricsParser.parseJapaneseReading(item);
        RenderPlan plan = compatibleReadingSchema && item.has("readingRenderPlan")
                ? parseRenderPlan(item.get("readingRenderPlan")) : null;
        if (plan != null) {
            if (!validPlanForLine(line, plan, reading)) return false;
            line.readingRenderPlan = plan;
            // A plan's joined display text is authoritative. Never restore a competing cached
            // legacy string beside it.
            line.romanizedText = "";
            if (reading != null) line.japaneseReading = reading;
            return true;
        }
        String romanized = Json.optString(item, "romanizedText");
        if (!isBlank(romanized)) line.romanizedText = romanized;
        if (reading != null) line.japaneseReading = reading;
        return !isBlank(romanized) || reading != null || !isBlank(cnMode);
    }

    private static JsonObject japaneseReadingToJson(SpicyJapaneseChineseProcessor.JapaneseReading reading) {
        JsonObject object = new JsonObject();
        object.addProperty("sourceText", safe(reading.sourceText));
        object.addProperty("romaji", safe(reading.romaji));
        JsonArray furigana = new JsonArray();
        if (reading.furigana != null) {
            for (SpicyJapaneseChineseProcessor.FuriganaSegment segment : reading.furigana) {
                if (segment == null || isBlank(segment.reading)) continue;
                JsonObject item = new JsonObject();
                item.addProperty("start", segment.start);
                item.addProperty("end", segment.end);
                item.addProperty("reading", segment.reading);
                furigana.add(item);
            }
        }
        object.add("furigana", furigana);
        return object;
    }

    static JsonElement renderPlanToJson(RenderPlan plan) {
        return GSON.toJsonTree(plan);
    }

    static RenderPlan parseRenderPlan(JsonElement element) {
        try {
            return element == null || element.isJsonNull() ? null : GSON.fromJson(element, RenderPlan.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean validPlanForLine(LyricsLine line, RenderPlan plan,
                                    SpicyJapaneseChineseProcessor.JapaneseReading reading) {
        if (line == null || plan == null || !DefaultRenderPlanBuilder.validate(plan).valid) return false;
        String text = safe(line.text);
        for (CanonicalSpanMapping source : plan.sourceUnits) {
            if (source == null || source.spanId == null || !CodePointRanges.isValid(text, source.canonicalRange)) return false;
        }
        for (TimedReadingUnit timed : plan.timedReadingUnits) {
            if (timed == null || isBlank(timed.spanId) || !CodePointRanges.isValid(text, timed.canonicalRange)) return false;
        }
        return reading == null || isBlank(reading.romaji) || safe(reading.romaji).equals(safe(plan.joinedDisplayText));
    }

    private static String normalizeChineseMode(String mode) {
        if ("jyutping".equalsIgnoreCase(mode) || "cantonese".equalsIgnoreCase(mode)) return SpotifyPlusConfig.CHINESE_MODE_JYUTPING;
        return SpotifyPlusConfig.CHINESE_MODE_PINYIN;
    }

}
