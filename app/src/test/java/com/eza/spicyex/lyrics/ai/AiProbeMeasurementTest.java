package com.eza.spicyex.lyrics.ai;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The probe measurement is the only per-model output-budget fact the app can learn on the
 * OpenAI wire, so its storage form and the allowance it produces are pinned here: identity-bound,
 * restart-surviving, and clamped in both directions.
 */
public class AiProbeMeasurementTest {

    @Test
    public void theRecordRoundTripsAndSurvivesAProcessRestart() {
        String stored = AiProbeMeasurement.encode("api.deepseek.com", "deepseek-v4-flash",
                900, "finish:length");

        AiProbeMeasurement.Parsed parsed =
                AiProbeMeasurement.decode(stored, "api.deepseek.com", "deepseek-v4-flash");

        assertEquals("api.deepseek.com", parsed.endpointHost);
        assertEquals("deepseek-v4-flash", parsed.model);
        assertEquals(Integer.valueOf(900), parsed.completionTokens);
        assertEquals("finish:length", parsed.failureToken);
    }

    @Test
    public void aRecordFromAnotherIdentityReadsAsUnmeasured() {
        String stored = AiProbeMeasurement.encode("api.deepseek.com", "deepseek-v4-flash",
                900, "");

        assertNull(AiProbeMeasurement.decode(stored, "api.openai.com", "deepseek-v4-flash"));
        assertNull(AiProbeMeasurement.decode(stored, "api.deepseek.com", "o3-mini"));
        assertNull(AiProbeMeasurement.decode("", "api.deepseek.com", "deepseek-v4-flash"));
        assertNull(AiProbeMeasurement.decode(null, "api.deepseek.com", "deepseek-v4-flash"));
        assertNull(AiProbeMeasurement.decode("garbage", "api.deepseek.com", "deepseek-v4-flash"));
    }

    @Test
    public void anUnreportedSpendIsCarriedWithoutInventingAFigure() {
        AiProbeMeasurement.Parsed parsed = AiProbeMeasurement.decode(
                AiProbeMeasurement.encode("h", "m", null, "provider:request_rejected"),
                "h", "m");
        assertNull(parsed.completionTokens);
        assertEquals("provider:request_rejected", parsed.failureToken);
    }

    @Test
    public void theAllowanceIsTheSpendPastTheReplyEstimateClampedBothWays() {
        assertEquals("a shallow spender is measured at zero, not defaulted upward",
                0, AiProbeMeasurement.allowanceFrom(30));
        assertEquals("the reply estimate itself buys nothing extra",
                0, AiProbeMeasurement.allowanceFrom(AiProbeMeasurement.REPLY_TOKEN_ESTIMATE));
        assertTrue("a reasoner's spend becomes headroom",
                AiProbeMeasurement.allowanceFrom(900) == 900 - AiProbeMeasurement.REPLY_TOKEN_ESTIMATE);
        assertEquals("the configured output ceiling still wins",
                AiContract.MAX_CONFIGURED_OUTPUT_TOKENS,
                AiProbeMeasurement.allowanceFrom(Integer.MAX_VALUE));
    }
}
