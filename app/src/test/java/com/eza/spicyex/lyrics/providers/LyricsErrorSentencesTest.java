package com.eza.spicyex.lyrics.providers;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LyricsErrorSentencesTest {
    @Test
    public void durableMissShowsOneLineAndNoNextStep() {
        LyricsErrorSentences.Report report =
                LyricsErrorSentences.describe("Apple Music lyrics empty; LRCLIB empty");

        assertVerdict(report, LyricsErrorSentences.Verdict.NO_MATCH);
        assertEquals("No lyrics for this track.", report.title.fallback);
        assertNull("a plain miss has no detail line", report.detail);
        assertNull("a plain miss has nothing to do", report.action);
        assertEquals(1, report.sentences().size());
    }

    @Test
    public void surfaceEmptyTokensAreAMissNotAGenericFailure() {
        assertVerdict(LyricsErrorSentences.describe("Empty lyrics response"),
                LyricsErrorSentences.Verdict.NO_MATCH);
        assertVerdict(LyricsErrorSentences.describe("Empty applied lyrics rows"),
                LyricsErrorSentences.Verdict.NO_MATCH);
        assertVerdict(LyricsErrorSentences.describe("Lyrics unavailable"),
                LyricsErrorSentences.Verdict.NO_MATCH);
    }

    @Test
    public void transportFailureAsksTheReaderToCheckTheConnection() {
        LyricsErrorSentences.Report report = LyricsErrorSentences.describe(
                "Apple Music upstream-error status 0: timeout; LRCLIB failed: timeout");

        assertVerdict(report, LyricsErrorSentences.Verdict.NO_CONNECTION);
        assertEquals("The lyrics could not be downloaded.", report.title.fallback);
        assertNull(report.detail);
        assertEquals("Try again when you are online.", report.action.fallback);
    }

    @Test
    public void rateLimitOutranksTheFallbackStagesThatFollowIt() {
        LyricsErrorSentences.Report report = LyricsErrorSentences.describe(
                "Apple Music rate-limited HTTP 429; LRCLIB HTTP 404");

        assertVerdict(report, LyricsErrorSentences.Verdict.SOURCE_BUSY);
        assertEquals("The lyrics source is busy.", report.title.fallback);
        assertNull(report.detail);
        assertEquals("Try again in a minute.", report.action.fallback);
    }

    @Test
    public void openBreakerReadsAsASourceThatIsNotAnswering() {
        assertVerdict(LyricsErrorSentences.describe(
                        "Apple Music rate-limited: request suppressed; LRCLIB empty"),
                LyricsErrorSentences.Verdict.SOURCE_BUSY);
        assertVerdict(LyricsErrorSentences.describe("LRCLIB upstream-error HTTP 503"),
                LyricsErrorSentences.Verdict.SOURCE_BUSY);
    }

    @Test
    public void disabledSourcesAskForASourceInsteadOfARetry() {
        // LyricsSessionManager.settledReason and LyricsRepository.fetchLyrics both hand the
        // surface these literals when the plan has no enabled source to run.
        for (String chain : new String[]{
                "All lyric sources disabled", "No automatic lyric source is due"}) {
            LyricsErrorSentences.Report report = LyricsErrorSentences.describe(chain);

            assertVerdict(report, LyricsErrorSentences.Verdict.NO_SOURCE);
            assertEquals(chain, "No lyrics source is enabled.", report.title.fallback);
            assertNull(chain, report.detail);
            assertEquals(chain, "Enable one in settings.", report.action.fallback);
        }
    }

    @Test
    public void ineligibleSourceWithNothingLeftToFetchAsksForASource() {
        // Verified on device at B1065 with every source disabled:
        // LyricsSessionManager.acceptProviderView reports this literal at :819.
        LyricsErrorSentences.Report report =
                LyricsErrorSentences.describe("Source not eligible for this track");

        assertVerdict(report, LyricsErrorSentences.Verdict.NO_SOURCE);
        assertEquals("No lyrics source is enabled.", report.title.fallback);
        assertNull(report.detail);
        assertEquals("Enable one in settings.", report.action.fallback);
    }

    @Test
    public void genuineProviderFailureKeepsItsOwnVerdict() {
        // LyricsRepository.attemptOrderedSource: every enabled source answered, none had lyrics.
        assertVerdict(LyricsErrorSentences.describe("No lyric source in order produced lyrics"),
                LyricsErrorSentences.Verdict.GENERIC);
        assertVerdict(LyricsErrorSentences.describe("Apple Music upstream-error status 0: timeout"),
                LyricsErrorSentences.Verdict.NO_CONNECTION);
    }

    @Test
    public void unknownTerminalFailureFallsBackToTheGenericSentences() {
        LyricsErrorSentences.Report report =
                LyricsErrorSentences.describe("Apple Music parse failed: malformed json");

        assertVerdict(report, LyricsErrorSentences.Verdict.GENERIC);
        assertEquals("The lyrics could not be loaded.", report.title.fallback);
        assertNull(report.detail);
        assertEquals("Try again. Restart Spotify if it keeps failing.",
                report.action.fallback);
        assertVerdict(LyricsErrorSentences.describe(""), LyricsErrorSentences.Verdict.GENERIC);
        assertVerdict(LyricsErrorSentences.describe(null), LyricsErrorSentences.Verdict.GENERIC);
    }

    @Test
    public void everyVerdictStaysWithinTwoShortLines() {
        for (LyricsErrorSentences.Verdict verdict : LyricsErrorSentences.Verdict.values()) {
            LyricsErrorSentences.Report report = LyricsErrorSentences.forVerdict(verdict);
            List<LyricsErrorSentences.Line> lines = report.sentences();
            int expectedLines = verdict == LyricsErrorSentences.Verdict.NO_MATCH ? 1 : 2;

            assertEquals(verdict.token(), expectedLines, lines.size());
            assertNull(verdict.token(), report.detail);
            if (verdict == LyricsErrorSentences.Verdict.NO_MATCH) {
                assertNull("a plain miss has nothing to do", report.action);
            } else {
                assertNotNull("an actionable verdict needs one next step", report.action);
            }
            for (LyricsErrorSentences.Line line : lines) {
                assertTrue(line.fallback + " is not short",
                        line.fallback.length() <= 60);
            }
        }
    }

    @Test
    public void noProviderPayloadOrUrlReachesTheSentences() {
        String chain = "Apple Music upstream-error status 0: "
                + "Failed to connect to spicy.example.invalid/203.0.113.9:443 (token=SECRET42)";

        LyricsErrorSentences.Report report = LyricsErrorSentences.describe(chain);

        assertEquals(LyricsErrorSentences.Verdict.NO_CONNECTION, report.verdict);
        for (LyricsErrorSentences.Line line : report.sentences()) {
            String text = line.fallback.toLowerCase(java.util.Locale.ROOT);
            assertFalse(text, text.contains("http"));
            assertFalse(text, text.contains("secret42"));
            assertFalse(text, text.contains("example.invalid"));
            assertFalse(text, text.contains("token"));
            assertFalse(text, text.contains("apple music"));
            assertFalse(text, text.contains("upstream-error"));
        }
    }

    @Test
    public void diagnosticTokenKeepsTheReasonAndDropsWhitespace() {
        String token = LyricsErrorSentences.diagnosticToken(
                "Apple Music rate-limited HTTP 429; LRCLIB empty");

        assertTrue(token, token.contains("rate-limited"));
        assertTrue(token, token.contains("429"));
        assertFalse(token, token.contains(" "));
        assertFalse(token, token.contains(";"));
        assertTrue("token should stay bounded: " + token, token.length() <= 64);
    }

    @Test
    public void defaultResourcesCarryTheSameSentencesAsTheFallbacks() throws Exception {
        Map<String, String> strings = strings(resourceDir("values/strings.xml"));
        for (LyricsErrorSentences.Verdict verdict : LyricsErrorSentences.Verdict.values()) {
            LyricsErrorSentences.Report report = LyricsErrorSentences.forVerdict(verdict);
            for (LyricsErrorSentences.Line line : report.sentences()) {
                String text = strings.get(line.resource);
                assertNotNull("Missing strings.xml entry " + line.resource, text);
                assertEquals("Fallback drifted from strings.xml for " + line.resource,
                        line.fallback, text);
            }
        }
    }

    @Test
    public void everyLocaleCoveringLyricsErrorsKeepsTheSameKeys() throws Exception {
        Set<String> expected = errorKeys(strings(resourceDir("values/strings.xml")));
        for (File locale : localeStringFiles()) {
            Map<String, String> strings = strings(locale);
            Set<String> actual = errorKeys(strings);
            if (actual.isEmpty()) continue;
            assertEquals("Lyrics error keys drifted in " + locale, expected, actual);
        }
    }

    private static Set<String> errorKeys(Map<String, String> strings) {
        Set<String> out = new TreeSet<>();
        for (String name : strings.keySet()) {
            if (name.startsWith("lyrics_error_")) out.add(name);
        }
        return out;
    }

    private static List<File> localeStringFiles() {
        List<File> out = new ArrayList<>();
        File[] dirs = resourceRoot().listFiles();
        if (dirs == null) return out;
        for (File dir : dirs) {
            if (!dir.isDirectory() || !dir.getName().startsWith("values-")) continue;
            File strings = new File(dir, "strings.xml");
            if (strings.isFile()) out.add(strings);
        }
        return out;
    }

    private static File resourceDir(String path) {
        return new File(resourceRoot(), path);
    }

    private static File resourceRoot() {
        File root = new File("src/main/res");
        if (!root.isDirectory()) root = new File("app/" + root.getPath());
        assertTrue("Resource root not found from " + new File(".").getAbsolutePath(),
                root.isDirectory());
        return root;
    }

    private static void assertVerdict(LyricsErrorSentences.Report report,
                                      LyricsErrorSentences.Verdict expected) {
        assertEquals(expected, report.verdict);
        int expectedLines = expected == LyricsErrorSentences.Verdict.NO_MATCH ? 1 : 2;
        assertEquals(expected.token(), expectedLines, report.sentences().size());
        for (LyricsErrorSentences.Line line : report.sentences()) {
            assertNotNull(line.resource);
            assertFalse(line.resource.isEmpty());
            assertFalse(line.fallback.isEmpty());
        }
    }

    private static Map<String, String> strings(File file) throws Exception {
        assertTrue("Strings file not found: " + file.getAbsolutePath(), file.isFile());
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
        NodeList strings = document.getElementsByTagName("string");
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < strings.getLength(); i++) {
            Element element = (Element) strings.item(i);
            out.put(element.getAttribute("name"), element.getTextContent());
        }
        return out;
    }
}
