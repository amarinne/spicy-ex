package com.eza.spicyex.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.Test;

public class LayoutOptionTruncationTest {
    @Test
    public void ellipsizedFillChoiceReportsItsStoredOptionIdentity() {
        LayoutProbeReport report = new LayoutProbeReport();
        report.flag("truncated.option.lyric_line_sync_fill.2", true);
        assertEquals(Collections.singletonList("R12 option_text_truncated lyric_line_sync_fill.2"),
                report.violations());
    }

    @Test
    public void fullyReadableChoicesDoNotReportTruncation() {
        LayoutProbeReport report = new LayoutProbeReport();
        report.flag("truncated.option.lyric_line_sync_fill.2", false);
        report.flag("truncated.option.lyric_word_bounce_mode.1", false);
        assertTrue(report.violations().isEmpty());
    }
}
