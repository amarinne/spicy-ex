package com.eza.spicyex.lyrics;

import com.atilika.kuromoji.unidic.Tokenizer;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * Owner of the full build's Japanese analysis resources.
 *
 * <p>The UniDic tokenizer and both JMdict tables are loaded lazily and can be dropped again under
 * memory pressure through {@link #trimMemory()}. This replaces the old static fields on
 * {@link SpicyJapaneseChineseProcessor}: the processor keeps its static API as a compatibility
 * wrapper, but residency now lives here and is reclaimable.
 *
 * <p>Persistent reading artifacts on disk are untouched by trimming; the next analysis reloads
 * from the packaged dictionaries.
 */
public final class JapaneseReadingEngine {
    private static volatile JapaneseReadingEngine shared;

    private volatile Tokenizer tokenizer;
    private volatile FuriganaTable jmdictFurigana;
    private volatile Map<String, String> jmdictPreferredReadings;
    /** Active analysis count. A trim never drops resources an analysis is still using. */
    private int activeUses;
    private boolean trimPending;
    private int tokenizerLoads;
    /** Test seam: fired each time {@link #tokenizer()} is consulted, before returning. */
    volatile Runnable tokenizerObserverForTest;

    private static volatile android.content.Context appContext;

    /**
     * Application context, used only to reach the extracted dictionary cache that
     * {@link MappedTokenizerBuilder} maps. A null one simply means the tokenizer loads the way it
     * always did.
     */
    public static void attachContext(android.content.Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    public static JapaneseReadingEngine shared() {
        JapaneseReadingEngine local = shared;
        if (local != null) return local;
        synchronized (JapaneseReadingEngine.class) {
            if (shared == null) shared = new JapaneseReadingEngine();
            return shared;
        }
    }

    /**
     * Marks the start of one analysis that may touch the tokenizer or dictionaries.
     *
     * <p>{@link #trimMemory()} defers its release while any use is active, so a memory callback
     * racing with processing cannot force a second resource load for interleaved work.
     */
    void beginUse() {
        synchronized (this) {
            activeUses++;
        }
    }

    /** Ends one analysis; a deferred trim releases resources when the last use ends. */
    void endUse() {
        synchronized (this) {
            if (activeUses > 0) activeUses--;
            if (activeUses == 0 && trimPending) clearLocked();
        }
    }

    /** UniDic tokenizer, built on first use. */
    public Tokenizer tokenizer() {
        Runnable observer = tokenizerObserverForTest;
        if (observer != null) observer.run();
        Tokenizer local = tokenizer;
        if (local != null) return local;
        synchronized (this) {
            if (tokenizer == null) {
                tokenizer = MappedTokenizerBuilder.build(appContext);
                tokenizerLoads++;
            }
            return tokenizer;
        }
    }

    /** Exposed for tests: how many times the tokenizer was constructed. */
    int tokenizerLoadsForTest() {
        synchronized (this) {
            return tokenizerLoads;
        }
    }

    /** Exposed for tests: how many analyses currently hold a resource lease. */
    int activeUsesForTest() {
        synchronized (this) {
            return activeUses;
        }
    }

    /** JMdict furigana span table, loaded only when decomposition needs it. */
    public FuriganaTable jmdictFurigana() {
        FuriganaTable local = jmdictFurigana;
        if (local != null) return local;
        synchronized (this) {
            if (jmdictFurigana == null) jmdictFurigana = loadJmdictFurigana();
            return jmdictFurigana;
        }
    }

    /** JMdict preferred readings, loaded only when the preferred-reading fallback is reached. */
    public Map<String, String> jmdictPreferredReadings() {
        Map<String, String> local = jmdictPreferredReadings;
        if (local != null) return local;
        synchronized (this) {
            if (jmdictPreferredReadings == null) {
                jmdictPreferredReadings = loadJmdictPreferredReadings();
            }
            return jmdictPreferredReadings;
        }
    }

    /** Releases the tokenizer and dictionary tables when no analysis is using them. */
    public void trimMemory() {
        synchronized (this) {
            if (activeUses > 0) {
                // Defer: the resident resources stay available to every analysis still running.
                trimPending = true;
                return;
            }
            clearLocked();
        }
    }

    private void clearLocked() {
        tokenizer = null;
        jmdictFurigana = null;
        jmdictPreferredReadings = null;
        trimPending = false;
    }

    private static FuriganaTable loadJmdictFurigana() {
        FuriganaTable.Builder out = new FuriganaTable.Builder();
        readJmdict("JmdictFurigana.txt.gz", line -> {
            int first = line.indexOf('|');
            int second = first < 0 ? -1 : line.indexOf('|', first + 1);
            if (first <= 0 || second <= first + 1 || second >= line.length() - 1) return;
            List<SpicyJapaneseChineseProcessor.FuriganaSegment> segments =
                    parseJmdictSpanSpec(line.substring(second + 1));
            if (segments.isEmpty()) return;
            // Builder.add keeps the first entry for a key.
            out.add(kataToHira(line.substring(0, first)) + "|"
                    + kataToHira(line.substring(first + 1, second)), segments);
        });
        return out.build();
    }

    private static Map<String, String> loadJmdictPreferredReadings() {
        HashMap<String, String> out = new HashMap<>();
        readJmdict("JmdictPreferredReadings.txt.gz", line -> {
            int separator = line.indexOf('|');
            if (separator <= 0 || separator >= line.length() - 1) return;
            out.put(kataToHira(line.substring(0, separator)),
                    kataToHira(line.substring(separator + 1)));
        });
        return out;
    }

    /** Feeds each line of a gzipped JMdict table from the language model pack, BOM stripped. A
     *  missing pack or a damaged file just yields fewer (or no) lines. */
    private static void readJmdict(String name, Consumer<String> onLine) {
        try (InputStream in = LanguageModelPack.openOrClasspath("jmdict/" + name, "/jmdict/" + name)) {
            if (in == null) return;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1);
                    onLine.accept(line);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static List<SpicyJapaneseChineseProcessor.FuriganaSegment> parseJmdictSpanSpec(String spec) {
        ArrayList<SpicyJapaneseChineseProcessor.FuriganaSegment> segments = new ArrayList<>();
        if (spec == null || spec.trim().isEmpty()) return segments;
        String[] parts = spec.split(";");
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;
            int colon = part.indexOf(':');
            if (colon <= 0 || colon >= part.length() - 1) continue;
            String range = part.substring(0, colon);
            String reading = kataToHira(part.substring(colon + 1));
            int dash = range.indexOf('-');
            try {
                int start;
                int end;
                if (dash > 0) {
                    start = Integer.parseInt(range.substring(0, dash));
                    end = Integer.parseInt(range.substring(dash + 1)) + 1;
                } else {
                    start = Integer.parseInt(range);
                    end = start + 1;
                }
                if (start >= 0 && end > start && !reading.isEmpty()) {
                    segments.add(new SpicyJapaneseChineseProcessor.FuriganaSegment(start, end, reading));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return segments;
    }

    private static String kataToHira(String text) {
        String input = text == null ? "" : text;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c >= 'ァ' && c <= 'ヶ') out.append((char) (c - 0x60));
            else out.append(c);
        }
        return out.toString();
    }
}
