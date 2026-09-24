package com.eza.spicyex.lyrics;

import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpicyQueryEnvelopeTest {
    private JsonObject envelope(String jobs) { return JsonParser.parseString("{\"queries\":[" + jobs + "]}").getAsJsonObject(); }
    @Test public void selectionMatchesStringMapLastWriteSemantics() {
        JsonObject root = envelope("{\"operationId\":\"0\",\"result\":{\"status\":401}},"
                + "{\"operationId\":0,\"result\":{\"status\":403}},"
                + "{\"operationId\":\"0\",\"result\":{\"status\":200}}");
        assertEquals(Integer.valueOf(200), SpicyQueryEnvelope.status(SpicyQueryEnvelope.result(root)));
        assertNull(SpicyQueryEnvelope.result(envelope("{\"operationId\":0,\"result\":{\"status\":200}}")));
        assertNull(SpicyQueryEnvelope.result(envelope("{\"result\":{\"status\":200}}")));
    }
    @Test public void noticeFirstPacked429KeepsUnknownSourceAndCannotBecomeDurableMiss() {
        JsonObject root = envelope("{\"_notice\":\"Access policy\"},{\"operationId\":\"0\",\"result\":{\"httpStatus\":429,"
                + "\"data\":[[\"error\",\"Spotify API error\",\"source\",\"unknown\"],[-1,2,0,2,1,3]]}}");
        String failure = SpicyNetworkDiagnostics.recordEnvelope(root, SpicyQueryEnvelope.result(root));
        assertTrue(SpicyNetworkDiagnostics.spicyEnvelopeNoticePresent);
        assertEquals(Integer.valueOf(429), SpicyNetworkDiagnostics.spicyQueryStatus);
        assertEquals("unknown", SpicyNetworkDiagnostics.source);
        assertTrue(failure.contains("Apple Music upstream degraded / Spotify API error"));
        assertFalse(LyricsFetchErrors.isDurableNoLyrics(failure + "; LRCLIB empty"));
    }
    @Test public void statusMappingKeeps204AsErrorAnd503AsQueue() {
        for (int status : new int[]{204,401,403,404,429,503}) {
            JsonObject result = new JsonObject(); result.addProperty("httpStatus", status);
            String failure = SpicyQueryEnvelope.failureReason(result);
            assertNotNull(failure);
            if (status == 404) assertTrue(failure.contains("no-match"));
            else assertFalse(failure.contains("no-match"));
            if (status == 503) assertEquals("Apple Music queued", failure);
            if (status != 404) assertFalse(LyricsFetchErrors.isDurableNoLyrics(failure + "; LRCLIB HTTP 404"));
        }
    }
    @Test public void versionProbeIgnoresNoticeAndOtherOperationVersions() {
        assertEquals("6.3.15", SpicyVersionProbeState.parseLatestVersion(envelope(
                "{\"_notice\":\"99.0.0\"},{\"operationId\":\"1\",\"result\":{\"data\":\"88.0.0\"}},"
                        + "{\"operationId\":\"0\",\"result\":{\"httpStatus\":200,\"data\":\"6.3.15\"}}").toString()));
    }
}
