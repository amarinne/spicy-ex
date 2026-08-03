package com.eza.spicyex.diagnostics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class DiagnosticUploaderTest {
    @Test
    public void mapsContractFailuresWithoutCollapsingThem() {
        assertEquals(DiagnosticUploader.Kind.INVALID_REPORT, DiagnosticUploader.mapStatus(400));
        assertEquals(DiagnosticUploader.Kind.REPORT_ID_COLLISION, DiagnosticUploader.mapStatus(409));
        assertEquals(DiagnosticUploader.Kind.REQUEST_TOO_LARGE, DiagnosticUploader.mapStatus(413));
        assertEquals(DiagnosticUploader.Kind.RATE_LIMITED, DiagnosticUploader.mapStatus(429));
        assertEquals(DiagnosticUploader.Kind.STORAGE_UNAVAILABLE, DiagnosticUploader.mapStatus(503));
        assertEquals(DiagnosticUploader.Kind.REDIRECT_REJECTED, DiagnosticUploader.mapStatus(307));
        assertEquals(DiagnosticUploader.Kind.SERVER_ERROR, DiagnosticUploader.mapStatus(500));
        assertEquals(DiagnosticUploader.Kind.INVALID_RESPONSE, DiagnosticUploader.mapStatus(418));
    }

    @Test
    public void receiptMustMatchExpectedReportId() {
        String id = "R1-00000000000000000000000002";
        String body = "{\"reportId\":\"" + id + "\","
                + "\"receivedAtUtc\":\"2026-08-02T00:00:00Z\","
                + "\"rawExpiresAtUtc\":null,"
                + "\"retentionPolicy\":\"indefinite\"}";
        assertNotNull(DiagnosticUploader.parseReceipt(body, id));
        assertNull(DiagnosticUploader.parseReceipt(body,
                "R1-00000000000000000000000003"));
        assertNull(DiagnosticUploader.parseReceipt("{}", id));
        assertNull(DiagnosticUploader.parseReceipt(body.replace(
                "\"rawExpiresAtUtc\":null",
                "\"rawExpiresAtUtc\":\"2026-09-01T00:00:00Z\""), id));
        assertNull(DiagnosticUploader.parseReceipt(body.replace(
                "\"retentionPolicy\":\"indefinite\"",
                "\"retentionPolicy\":\"unknown\""), id));
    }

}
