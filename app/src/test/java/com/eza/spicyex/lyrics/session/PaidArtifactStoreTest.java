package com.eza.spicyex.lyrics.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The paid store's contract: an accepted AI artifact is addressable by everything it depends on,
 * is never served for a request it does not answer, and is never dropped to make room.
 */
public class PaidArtifactStoreTest {

    private static PaidArtifactIdentity identity() {
        return new PaidArtifactIdentity(LayerKind.MEANING, "digest-a", "provider-a", "model-a", "prompt-a");
    }

    // --- identity -----------------------------------------------------------

    @Test
    public void everyIdentityComponentChangesTheKey() {
        PaidArtifactIdentity base = identity();
        List<PaidArtifactIdentity> variants = new ArrayList<>();
        variants.add(new PaidArtifactIdentity(LayerKind.SOUND, "digest-a", "provider-a", "model-a", "prompt-a"));
        variants.add(new PaidArtifactIdentity(LayerKind.MEANING, "digest-b", "provider-a", "model-a", "prompt-a"));
        variants.add(new PaidArtifactIdentity(LayerKind.MEANING, "digest-a", "provider-b", "model-a", "prompt-a"));
        variants.add(new PaidArtifactIdentity(LayerKind.MEANING, "digest-a", "provider-a", "model-b", "prompt-a"));
        variants.add(new PaidArtifactIdentity(LayerKind.MEANING, "digest-a", "provider-a", "model-a", "prompt-b"));
        for (PaidArtifactIdentity variant : variants) {
            assertNotEquals("collides with the base identity: " + variant.storageKey(),
                    base.storageKey(), variant.storageKey());
        }
    }

    @Test
    public void anIdentityMissingAComponentIsNotStorable() {
        assertFalse(new PaidArtifactIdentity(null, "d", "p", "m", "c").isComplete());
        assertFalse(new PaidArtifactIdentity(LayerKind.MEANING, "", "p", "m", "c").isComplete());
        assertFalse(new PaidArtifactIdentity(LayerKind.MEANING, "d", "", "m", "c").isComplete());
        assertFalse(new PaidArtifactIdentity(LayerKind.MEANING, "d", "p", "", "c").isComplete());
        assertFalse(new PaidArtifactIdentity(LayerKind.MEANING, "d", "p", "m", "").isComplete());
        assertTrue(new PaidArtifactIdentity(LayerKind.MEANING, "d", "p", "m", "c").isComplete());
    }

    @Test
    public void onlyAnAiArtifactYieldsAPaidIdentity() {
        assertNull(PaidArtifactIdentity.forArtifact(artifact(LayerAuthority.DETERMINISTIC, "local", "m", "c")));
        assertNull(PaidArtifactIdentity.forArtifact(artifact(LayerAuthority.MACHINE, "google", "m", "c")));
        PaidArtifactIdentity paid = PaidArtifactIdentity.forArtifact(artifact(LayerAuthority.AI, "prov", "m", "c"));
        assertTrue(paid != null && paid.isComplete());
        assertEquals("prov", paid.providerId);
        assertEquals("m", paid.modelId);
        assertEquals("c", paid.promptContractId);
    }

    @Test
    public void anAiArtifactWithoutAModelIsNotAddressable() {
        assertNull(PaidArtifactIdentity.forArtifact(artifact(LayerAuthority.AI, "prov", "", "c")));
    }

    // --- record validation --------------------------------------------------

    @Test
    public void aRecordIsServedOnlyForItsOwnIdentity() {
        JsonObject record = AIPaidArtifactCache.header(identity());
        assertTrue(AIPaidArtifactCache.matches(record, identity()));
        assertFalse(AIPaidArtifactCache.matches(record,
                new PaidArtifactIdentity(LayerKind.MEANING, "digest-a", "provider-a", "model-b", "prompt-a")));
        assertFalse(AIPaidArtifactCache.matches(record,
                new PaidArtifactIdentity(LayerKind.SOUND, "digest-a", "provider-a", "model-a", "prompt-a")));
    }

    @Test
    public void aRecordFromAnotherSchemaIsNotServed() {
        JsonObject record = AIPaidArtifactCache.header(identity());
        record.addProperty("schema", 99);
        assertFalse(AIPaidArtifactCache.matches(record, identity()));
    }

    // --- admission ----------------------------------------------------------

    @Test
    public void entryCountDoesNotRejectWhenBytesFit() {
        assertEquals("", AIPaidArtifactCache.admissionReason(999L, 1L, 1_000L));
    }

    @Test
    public void unlimitedCapacityNeverRejectsForBytePressure() {
        assertEquals("", AIPaidArtifactCache.admissionReason(
                Long.MAX_VALUE - 100L, 100L, Long.MAX_VALUE));
    }

    @Test
    public void theByteBoundAlsoRejectsRatherThanMakingRoom() {
        assertEquals("store-full-bytes",
                AIPaidArtifactCache.admissionReason(60L, 60L, 100L));
    }

    @Test
    public void anArtifactBiggerThanTheStoreIsRejected() {
        assertEquals("artifact-larger-than-store",
                AIPaidArtifactCache.admissionReason(0L, 500L, 100L));
    }

    @Test
    public void rewritingOneIdentityIsMeasuredAgainstItsOwnStoredSize() {
        // The caller excludes the rewritten identity from otherBytes.
        assertEquals("", AIPaidArtifactCache.admissionReason(0L, 95L, 100L));
    }

    @Test
    public void admissionArithmeticSaturatesInsteadOfOverflowing() {
        assertEquals("store-full-bytes", AIPaidArtifactCache.admissionReason(
                Long.MAX_VALUE - 5L, 10L, Long.MAX_VALUE - 1L));
    }

    // --- v515 transition ----------------------------------------------------

    @Test
    public void migrationPreservesEveryPaidStringByteForByteInStableOrder() {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("paid-v1|z", "not-json");
        prefs.put("paid-v1|a", "{\"layer\":\"MEANING\",\"payload\":\"é\"}");
        prefs.put("__paid_index", "paid-v1|a|99");
        prefs.put("paid-v1|number", 3L);
        prefs.put("unrelated", "ignored");

        List<AIPaidArtifactCache.MigrationRow> rows =
                AIPaidArtifactCache.migrationRows(prefs);

        assertEquals(2, rows.size());
        assertEquals("paid-v1|a", rows.get(0).key);
        assertEquals("MEANING", rows.get(0).layer);
        assertEquals("{\"layer\":\"MEANING\",\"payload\":\"é\"}",
                new String(rows.get(0).valueBytes, StandardCharsets.UTF_8));
        assertEquals("paid-v1|z", rows.get(1).key);
        assertEquals("", rows.get(1).layer);
        assertEquals("not-json",
                new String(rows.get(1).valueBytes, StandardCharsets.UTF_8));
    }

    // --- routing ------------------------------------------------------------

    @Test
    public void onlyAiAuthoredArtifactsAreTreatedAsPaid() {
        assertFalse(com.eza.spicyex.lyrics.ProcessedLyricsCache.isPaid(
                artifact(LayerAuthority.DETERMINISTIC, "local", "", "reading-v3")));
        assertFalse(com.eza.spicyex.lyrics.ProcessedLyricsCache.isPaid(
                artifact(LayerAuthority.MACHINE, "google", "", "translate-v1")));
        assertTrue(com.eza.spicyex.lyrics.ProcessedLyricsCache.isPaid(
                artifact(LayerAuthority.AI, "prov", "model", "prompt")));
    }

    private static DerivedLayerArtifact artifact(LayerAuthority authority, String producerId,
                                                 String modelId, String contractId) {
        LayerProvenance provenance = new LayerProvenance(authority, producerId, contractId, modelId, 0L);
        return new DerivedLayerArtifact(LayerKind.MEANING, "digest-a", "config-a", provenance,
                Collections.singletonList(new MeaningEntry("r0#aaaa", "text", "en")), false);
    }
}
