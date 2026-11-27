package com.eza.spicyex.lyrics.language;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Dev-only parity with the desktop tone-rhyme-v4 fixture. The fixture holds private lyrics, so it
 * is read from the owner's spicy-lyrics checkout (or {@code -Dspicy.rhymeFixture=PATH}) and is
 * never copied into this repository. The test is skipped when the file is absent.
 */
public class ChineseSongGuessParityTest {
    private static final String DEFAULT_FIXTURE = System.getProperty("user.home")
            + "/Projects/spotify/spicy-lyrics/docs/private/rhyme-limits/rhyme-guess-v4-parity.json";

    @Test
    public void matchesTheDesktopFixtureAndKeepsLabeledCallsCorrect() throws Exception {
        File file = new File(System.getProperty("spicy.rhymeFixture", DEFAULT_FIXTURE));
        Assume.assumeTrue("private rhyme fixture not present", file.isFile());
        JsonObject fixture;
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            fixture = JsonParser.parseReader(reader).getAsJsonObject();
        }
        assertEquals("spicy.chinese-rhyme-guess-parity.v1", fixture.get("schema").getAsString());
        assertEquals(ChineseSongGuess.REVISION, fixture.get("revision").getAsString());
        int rows = 0, agree = 0, labeled = 0, confident = 0, wrong = 0;
        List<String> mismatches = new ArrayList<>();
        List<String> wrongCalls = new ArrayList<>();
        for (JsonElement element : fixture.getAsJsonArray("songs")) {
            JsonObject song = element.getAsJsonObject();
            List<String> lines = new ArrayList<>();
            for (JsonElement line : song.getAsJsonArray("lines")) lines.add(line.getAsString());
            JsonObject want = song.getAsJsonObject("expected");
            String wantDecision = want.get("decision").isJsonNull() ? null : want.get("decision").getAsString();
            String label = song.get("label").isJsonNull() ? null : song.get("label").getAsString();
            ChineseSongGuess.Result got = ChineseSongGuess.guess(lines);
            rows++;
            if (same(wantDecision, got.language)) {
                agree++;
            } else {
                mismatches.add(String.format(Locale.ROOT,
                        "%s: want %s p=%.4f got %s p=%.4f | dJz=%+.3f dMz=%+.3f dTone=%+.4f markers %d/%d lines %d/%d",
                        song.get("id").getAsString(), wantDecision, want.get("probability").getAsDouble(),
                        got.language, got.probability,
                        got.jyutpingZ - want.get("jyutpingZ").getAsDouble(),
                        got.mandarinZ - want.get("mandarinZ").getAsDouble(),
                        got.toneExcess - want.get("toneExcess").getAsDouble(),
                        got.cantoneseMarkers, want.get("cantoneseMarkers").getAsInt(),
                        got.lineCount, want.get("lineCount").getAsInt()));
            }
            if (label != null) {
                labeled++;
                if (got.language != null) {
                    confident++;
                    if (!label.equals(got.language)) wrongCalls.add(song.get("id").getAsString() + " → " + got.language);
                }
            }
            if (song.get("id").getAsString().startsWith("cache:2kfufwz1iEl1y2D")) {
                assertTrue("K房 must not become a confident Mandarin call",
                        !ChineseSongGuess.CMN.equals(got.language));
            }
        }
        wrong = wrongCalls.size();
        System.out.println(String.format(Locale.ROOT,
                "tone-rhyme-v4 parity: %d/%d decisions agree (%.1f%%); labeled %d, confident %d, wrong %d",
                agree, rows, 100.0 * agree / rows, labeled, confident, wrong));
        for (String line : mismatches) System.out.println("  mismatch " + line);
        assertTrue("wrong confident calls: " + wrongCalls, wrongCalls.isEmpty());
        assertTrue("confident labeled calls " + confident, confident >= 160);
        assertTrue("decision agreement " + agree + "/" + rows, agree * 100 >= rows * 95);
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
