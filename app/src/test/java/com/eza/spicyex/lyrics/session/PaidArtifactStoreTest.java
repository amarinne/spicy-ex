package com.eza.spicyex.lyrics.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
    public void aFullStoreRejectsTheWriteInsteadOfEvicting() {
        AIPaidArtifactCache.Admission first = AIPaidArtifactCache.admit("", "a", 10L, 2, 1000L);
        AIPaidArtifactCache.Admission second = AIPaidArtifactCache.admit(first.nextIndex, "b", 10L, 2, 1000L);
        AIPaidArtifactCache.Admission third = AIPaidArtifactCache.admit(second.nextIndex, "c", 10L, 2, 1000L);

        assertTrue(first.admitted);
        assertTrue(second.admitted);
        assertFalse(third.admitted);
        assertEquals("store-full-entries", third.reason);
        // The two paid artifacts already accepted are still indexed.
        assertTrue(second.nextIndex.contains("a|10"));
        assertTrue(second.nextIndex.contains("b|10"));
    }

    @Test
    public void theByteBoundAlsoRejectsRatherThanMakingRoom() {
        AIPaidArtifactCache.Admission first = AIPaidArtifactCache.admit("", "a", 60L, 10, 100L);
        AIPaidArtifactCache.Admission second = AIPaidArtifactCache.admit(first.nextIndex, "b", 60L, 10, 100L);
        assertTrue(first.admitted);
        assertFalse(second.admitted);
        assertEquals("store-full-bytes", second.reason);
    }

    @Test
    public void anArtifactBiggerThanTheStoreIsRejected() {
        AIPaidArtifactCache.Admission admission = AIPaidArtifactCache.admit("", "a", 500L, 10, 100L);
        assertFalse(admission.admitted);
        assertEquals("artifact-larger-than-store", admission.reason);
    }

    @Test
    public void rewritingOneIdentityIsMeasuredAgainstItsOwnStoredSize() {
        AIPaidArtifactCache.Admission first = AIPaidArtifactCache.admit("", "a", 90L, 10, 100L);
        assertTrue(first.admitted);
        // Room for both would need 185 bytes; replacing "a" needs only 95.
        AIPaidArtifactCache.Admission rewrite = AIPaidArtifactCache.admit(first.nextIndex, "a", 95L, 10, 100L);
        assertTrue(rewrite.admitted);
        assertEquals("a|95", rewrite.nextIndex);
    }

    @Test
    public void anUnreadableIndexRowDoesNotBlockAdmission() {
        AIPaidArtifactCache.Admission admission =
                AIPaidArtifactCache.admit("a|notanumber\nb|10", "c", 10L, 10, 1000L);
        assertTrue(admission.admitted);
        assertTrue(admission.nextIndex.contains("b|10"));
        assertTrue(admission.nextIndex.contains("c|10"));
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
