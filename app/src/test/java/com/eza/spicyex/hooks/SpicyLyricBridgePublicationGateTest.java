package com.eza.spicyex.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpicyLyricBridgePublicationGateTest {
    @Test
    public void currentSerializationPublishes() {
        assertTrue(SpicyLyricBridgeCoordinator.shouldPublishSerializedDocument(
                true, 7, 7, 3L, 3L));
    }

    @Test
    public void newerPublicationSupersedesAnOvertakenSerialization() {
        assertFalse(SpicyLyricBridgeCoordinator.shouldPublishSerializedDocument(
                true, 7, 7, 3L, 4L));
    }

    @Test
    public void songChangeAndDisabledBridgeDropTheSerialization() {
        assertFalse(SpicyLyricBridgeCoordinator.shouldPublishSerializedDocument(
                true, 8, 7, 3L, 3L));
        assertFalse(SpicyLyricBridgeCoordinator.shouldPublishSerializedDocument(
                true, null, 7, 3L, 3L));
        assertFalse(SpicyLyricBridgeCoordinator.shouldPublishSerializedDocument(
                false, 7, 7, 3L, 3L));
    }

    /**
     * A lane that finished with no changes replaces the session document with an equal copy and
     * returns at the fingerprint check, leaving the revision alone. The publication it overtook is
     * the only one carrying the document, so it has to survive.
     */
    @Test
    public void deduplicatedLaneCompletionDoesNotDropThePendingDocument() {
        assertTrue(SpicyLyricBridgeCoordinator.shouldPublishSerializedDocument(
                true, 7, 7, 1L, 1L));
    }
}
