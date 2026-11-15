package com.eza.spicyex.lyrics.catalog;

import com.eza.spicyex.lyrics.catalog.CatalogSource.ProviderStatus;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import org.junit.Test;
import static org.junit.Assert.*;

public class F4ProviderFailureRetryTest {
    @Test public void arbitraryParserAndNetworkMessagesNeverEstablishAbsence() {
        for (SourceId source : SourceId.values()) {
            for (String message : new String[]{"LRCLIB parse failed: empty JSON input",
                    "Apple Music source unavailable: stream was empty", "network failed: 404 bytes",
                    "parser: no lyrics field", "request failed: disabled socket"}) {
                assertEquals(source + ": " + message, ProviderStatus.TRANSIENT_ERROR,
                        ProviderFailureClassifier.classify(source, message));
            }
        }
    }

    @Test public void parserFailureUsesTransientPlannerHorizon() {
        ProviderStatus status = ProviderFailureClassifier.classify(SourceId.LRCLIB,
                "LRCLIB parse failed: empty JSON input");
        ProviderRecord record = ProviderRecord.notChecked(SourceId.LRCLIB).failed(status, 1000L);
        assertEquals(31000L, AcquisitionPlanner.dueAtMs(record, false));
    }
    @Test public void typedStatusSurvivesWordingAndBackoffStillDoublesToSixHours() {
        for (String message : new String[]{"", "timeout", "disabled", "empty 404 no lyrics"}) {
            assertEquals(ProviderStatus.NOT_FOUND, ProviderFailure.notFound(message).status);
            assertEquals(ProviderStatus.NOT_FOUND, ProviderFailure.httpError(404, message).status);
            assertEquals(ProviderStatus.TRANSIENT_ERROR, ProviderFailure.httpError(503, message).status);
            assertEquals(ProviderStatus.DISABLED, ProviderFailure.disabled(message).status);
        }
        ProviderRecord record = ProviderRecord.notChecked(SourceId.LRCLIB);
        for (int attempt = 1; attempt <= 20; attempt++) {
            CatalogState state = new CatalogState("f4", null,
                    java.util.Collections.singletonMap(SourceId.LRCLIB, record), null, null, true);
            record = CatalogDecisions.providerFailure(state, null, SourceId.LRCLIB,
                    ProviderFailure.transientError("404 empty no lyrics").status, 1000L)
                    .putProviders.get(0);
            assertEquals(Math.min(30000L << (attempt - 1), 21600000L) + 1000L,
                    AcquisitionPlanner.dueAtMs(record, false));
        }
    }

}
