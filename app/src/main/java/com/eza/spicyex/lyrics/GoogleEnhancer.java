package com.eza.spicyex.lyrics;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;

/** Google Translate-backed romanization and translation enhancer. */
public final class GoogleEnhancer {
    private static final int GOOGLE_REQUEST_RETRIES = 1;
    private static final long GOOGLE_REQUEST_MIN_INTERVAL_MS = 150L;
    private static final long GOOGLE_REQUEST_RETRY_DELAY_MS = 1000L;
    /**
     * One throttle per lane, not one for the whole client.
     *
     * <p>A single global gate made the Sound and Meaning lanes queue behind each other even though
     * they own separate executors, so translation waited on reading requests it has nothing to do
     * with. Keying the throttle by lane keeps each lane's own request rate polite while letting the
     * two run genuinely in parallel.
     */
    private static final Map<String, long[]> LANE_THROTTLES = new java.util.concurrent.ConcurrentHashMap<>();
    /** Shared by every lane: the endpoint's rate limit is per network address, not per lane. */
    private static final GoogleCooldown COOLDOWN = new GoogleCooldown();
    /**
     * A batch marker as Google may hand it back, not only as sent: its Japanese romanization
     * spaces it out ("[ [SPX _ 000] ]"), and CJK output can turn brackets, underscore and digits
     * full-width. The number is what matters.
     */
    private static final Pattern BATCH_MARKER_PATTERN = Pattern.compile(
            "[\\[［]\\s*[\\[［]\\s*(?:SPX|ＳＰＸ)\\s*[_＿]?\\s*([0-9０-９]{3})\\s*[\\]］]\\s*[\\]］]",
            Pattern.CASE_INSENSITIVE);

    private GoogleEnhancer() {
    }

    public static BatchResult translateBatch(
            Context context,
            OkHttpClient http,
            int processingVersion,
            String trackId,
            String sourceLang,
            String targetLang,
            List<BatchLine> lines,
            String cancelTag
    ) {
        BatchResult result = new BatchResult();
        if (lines == null || lines.isEmpty()) return result;

        String target = isBlank(targetLang) ? "en" : targetLang;
        List<BatchLine> pending = new ArrayList<>();
        for (BatchLine line : lines) {
            if (line == null || isBlank(line.text)) continue;
            result.requestedCount++;
            String cached = LyricCaches.getProcessingValue(context, processingVersion,
                    LyricCaches.translationKey(trackId, sourceLang, target, line.text));
            if (!isBlank(cached) && shouldDisplayTranslation(line.text, cached)) {
                result.translations.put(line.index, cached);
                result.cachedIndices.add(line.index);
            } else {
                pending.add(line);
            }
        }
        if (pending.isEmpty()) return result;

        String source = LyricCaches.sourceLanguageForCache(sourceLang);
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < pending.size(); i++) {
            if (i > 0) query.append('\n');
            query.append(marker(i)).append(' ').append(pending.get(i).text);
        }

        String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl="
                + Uri.encode(source)
                + "&tl=" + Uri.encode(target)
                + "&dt=t&q=" + Uri.encode(query.toString());
        HttpResult response = executeRequest(taggedRequest(url, cancelTag), http);
        result.networkAttempts = response.attempts;
        result.httpStatus = response.status;
        result.failureReason = response.failureReason;
        if (isBlank(response.body)) return result;

        Map<Integer, String> parsed = parseBatchTranslation(response.body);
        if (parsed.isEmpty()) result.failureReason = "parse_empty";
        Map<String, String> cacheWrites = new LinkedHashMap<>();
        for (int i = 0; i < pending.size(); i++) {
            BatchLine line = pending.get(i);
            String translated = parsed.get(i);
            if (isBlank(translated)) continue;
            translated = stripMarkerEcho(translated, i).trim();
            if (!shouldDisplayTranslation(line.text, translated)) continue;
            result.translations.put(line.index, translated);
            result.networkTranslatedCount++;
            cacheWrites.put(LyricCaches.translationKey(trackId, sourceLang, target, line.text), translated);
        }
        if (result.networkTranslatedCount > 0 && result.networkTranslatedCount < pending.size()) {
            result.failureReason = "partial_parse";
        }
        LyricCaches.putProcessingValues(context, processingVersion, cacheWrites);
        return result;
    }

    /**
     * Romanizes many lines in one request, the way {@link #translateBatch} translates them:
     * each line goes in behind its own marker, and Google's romanization of the whole text keeps
     * the markers ("[[SPX_000]] YA tebya lyublyu\n[[SPX_001]] My idom domoy"), so it splits back
     * into lines. Replaces one request per line - 60 for a 60-line song, which is what kept
     * running into the endpoint's per-address rate limit. Lines must share one source language.
     *
     * @return romanized text by line index, for the lines it could romanize (cached ones too)
     */
    public static Map<Integer, String> romanizeBatch(
            Context context,
            OkHttpClient http,
            int processingVersion,
            String trackId,
            String sourceLang,
            List<BatchLine> lines,
            String cancelTag
    ) {
        Map<Integer, String> result = new LinkedHashMap<>();
        if (lines == null || lines.isEmpty()) return result;
        List<BatchLine> pending = new ArrayList<>();
        for (BatchLine line : lines) {
            if (line == null || isBlank(line.text)) continue;
            String cached = LyricCaches.getProcessingValue(context, processingVersion,
                    LyricCaches.romanizationKey(trackId, sourceLang, line.text));
            if (!isBlank(cached) && !SpicyTextDetection.hasRomanizableScript(cached)) {
                result.put(line.index, cached);
            } else {
                pending.add(line);
            }
        }
        if (pending.isEmpty()) return result;

        Map<String, String> cacheWrites = new LinkedHashMap<>();
        List<BatchLine> missing = romanizeRequest(http, sourceLang, pending, cancelTag, trackId,
                result, cacheWrites);
        // A line whose marker still did not survive gets one more try, in a batch of its own
        // kind - once, and only when the first request did work (a refused or garbled request
        // is not asked again).
        if (!missing.isEmpty() && missing.size() < pending.size()) {
            romanizeRequest(http, sourceLang, missing, cancelTag, trackId, result, cacheWrites);
        }
        LyricCaches.putProcessingValues(context, processingVersion, cacheWrites);
        return result;
    }

    /** One batch request; fills {@code result} and returns the lines it did not get back. */
    private static List<BatchLine> romanizeRequest(OkHttpClient http, String sourceLang,
                                                   List<BatchLine> lines, String cancelTag,
                                                   String trackId, Map<Integer, String> result,
                                                   Map<String, String> cacheWrites) {
        String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl="
                + Uri.encode(LyricCaches.sourceLanguageForCache(sourceLang))
                + "&tl=en&dt=t&dt=rm&q=" + Uri.encode(batchQuery(lines));
        HttpResult response = executeRequest(taggedRequest(url, cancelTag), http);
        if (isBlank(response.body)) return lines;
        Map<Integer, String> parsed = parseBatchRomanization(response.body);
        List<BatchLine> missing = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            BatchLine line = lines.get(i);
            String romanized = parsed.get(i);
            if (isBlank(romanized) || SpicyTextDetection.hasRomanizableScript(romanized)) {
                missing.add(line);
                continue;
            }
            result.put(line.index, romanized);
            cacheWrites.put(LyricCaches.romanizationKey(trackId, sourceLang, line.text), romanized);
        }
        return missing;
    }

    /** "[[SPX_000]] first line\n[[SPX_001]] second line..." - the batch request's text. */
    static String batchQuery(List<BatchLine> lines) {
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) query.append('\n');
            query.append(marker(i)).append(' ').append(lines.get(i).text);
        }
        return query.toString();
    }

    /**
     * Splits a batch into requests no longer than {@code maxLines} lines / {@code maxChars}
     * characters of query (the endpoint takes a GET, so the URL has a practical limit).
     */
    public static List<List<BatchLine>> chunk(List<BatchLine> lines, int maxLines, int maxChars) {
        List<List<BatchLine>> chunks = new ArrayList<>();
        List<BatchLine> current = new ArrayList<>();
        int chars = 0;
        for (BatchLine line : lines) {
            if (line == null || isBlank(line.text)) continue;
            int size = line.text.length() + 14;
            if (!current.isEmpty() && (current.size() >= Math.max(1, maxLines)
                    || chars + size > Math.max(128, maxChars))) {
                chunks.add(current);
                current = new ArrayList<>();
                chars = 0;
            }
            current.add(line);
            chars += size;
        }
        if (!current.isEmpty()) chunks.add(current);
        return chunks;
    }

    /** The source-side romanization of a batch response, split back into lines by marker. */
    static Map<Integer, String> parseBatchRomanization(String body) {
        StringBuilder all = new StringBuilder();
        try {
            JsonArray sentences = JsonParser.parseString(body).getAsJsonArray().get(0).getAsJsonArray();
            for (JsonElement element : sentences) {
                if (!element.isJsonArray()) continue;
                JsonArray sentence = element.getAsJsonArray();
                if (sentence.size() > 3 && !sentence.get(3).isJsonNull()) {
                    all.append(sentence.get(3).getAsString());
                }
            }
        } catch (Throwable ignored) {
            return new LinkedHashMap<>();
        }
        return splitByMarkers(all.toString());
    }

    private static int markerNumber(String digits) {
        if (digits == null) return -1;
        int value = 0;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            int d = c >= '０' && c <= '９' ? c - '０' : c - '0';
            if (d < 0 || d > 9) return -1;
            value = value * 10 + d;
        }
        return value;
    }

    private static Map<Integer, String> splitByMarkers(String text) {
        Map<Integer, String> result = new LinkedHashMap<>();
        if (isBlank(text)) return result;
        Matcher matcher = BATCH_MARKER_PATTERN.matcher(text);
        int current = -1;
        int textStart = -1;
        while (matcher.find()) {
            if (current >= 0 && textStart >= 0) {
                String value = text.substring(textStart, matcher.start()).trim();
                if (!isBlank(value)) result.put(current, value);
            }
            current = markerNumber(matcher.group(1));
            textStart = matcher.end();
        }
        if (current >= 0 && textStart >= 0) {
            String value = text.substring(textStart).trim();
            if (!isBlank(value)) result.put(current, value);
        }
        return result;
    }

    /**
     * Tags the call so a retired lane run can cancel it. Untagged calls stay uncancellable, which
     * is why every lyric request goes through here.
     */
    private static Request taggedRequest(String url, String cancelTag) {
        return new Request.Builder().url(url).get()
                .tag(String.class, cancelTag == null ? "" : cancelTag)
                .build();
    }

    /**
     * Cancels the calls carrying {@code cancelTag} that have not been sent yet. Called when a
     * lane run is retired by a track, source, or configuration change.
     *
     * <p>A call already on its way is left to finish: Google has counted it either way, and
     * finishing is what writes its lines to the cache - the run that replaced this one (the
     * same song, restarted because a better copy of its lyrics arrived) then finds them there,
     * or joins the same request in flight, instead of asking again. Cancelling it made every
     * restart cost another request.
     */
    public static int cancelTagged(OkHttpClient http, String cancelTag) {
        if (http == null || cancelTag == null || cancelTag.isEmpty()) return 0;
        int cancelled = 0;
        for (okhttp3.Call call : http.dispatcher().queuedCalls()) {
            if (cancelTag.equals(call.request().tag(String.class))) {
                call.cancel();
                cancelled++;
            }
        }
        return cancelled;
    }

    /** Requests on the wire, by URL: an identical request joins the one in flight. */
    private static final Map<String, java.util.concurrent.CompletableFuture<HttpResult>> IN_FLIGHT =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long JOIN_TIMEOUT_MS = 30_000L;

    /**
     * {@link #sendRequest}, but a request identical to one already on the wire (same text, same
     * languages - a lane restarted for the same song) waits for that one's answer instead of
     * sending its own.
     */
    private static HttpResult executeRequest(Request request, OkHttpClient http) {
        if (request == null) return sendRequest(null, http);
        String key = request.url().toString();
        java.util.concurrent.CompletableFuture<HttpResult> mine = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<HttpResult> theirs = IN_FLIGHT.putIfAbsent(key, mine);
        if (theirs != null) {
            try {
                HttpResult shared = theirs.get(JOIN_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                HttpResult copy = new HttpResult();
                copy.body = shared.body;
                copy.status = shared.status;
                copy.failureReason = shared.failureReason;
                copy.attempts = 0; // this caller sent nothing
                return copy;
            } catch (Throwable ignored) {
                HttpResult failed = new HttpResult();
                failed.failureReason = "joined_request_failed";
                return failed;
            }
        }
        HttpResult result = null;
        try {
            result = sendRequest(request, http);
            return result;
        } finally {
            IN_FLIGHT.remove(key, mine);
            HttpResult done = result;
            if (done == null) {
                done = new HttpResult();
                done.failureReason = "failed";
            }
            mine.complete(done);
        }
    }

    private static HttpResult sendRequest(Request request, OkHttpClient http) {
        HttpResult result = new HttpResult();
        if (http == null || request == null) {
            result.failureReason = "client_unavailable";
            return result;
        }
        String lane = laneOf(request);
        for (int attempt = 0; attempt <= GOOGLE_REQUEST_RETRIES; attempt++) {
            if (COOLDOWN.remainingMs(SystemClock.elapsedRealtime()) > 0L) {
                // Still rate-limited: answer as the server would, without asking it.
                result.status = 429;
                result.failureReason = "cooldown";
                return result;
            }
            result.attempts++;
            throttleGoogleRequest(lane);
            okhttp3.Call call = http.newCall(request);
            try (Response response = call.execute()) {
                result.status = response.code();
                if (response.isSuccessful() && response.body() != null) {
                    COOLDOWN.onSuccess();
                    result.body = response.body().string();
                    if (isBlank(result.body)) result.failureReason = "empty_body";
                    return result;
                }
                result.failureReason = "http_" + response.code();
                if (response.code() == 429) {
                    // Retrying a second later only extends the limit; back off instead.
                    COOLDOWN.onRateLimited(SystemClock.elapsedRealtime(),
                            GoogleCooldown.parseRetryAfterMs(response.header("Retry-After")));
                    return result;
                }
                if (response.code() < 500) return result;
            } catch (IOException failure) {
                result.failureReason = failure.getClass().getSimpleName();
                // Cancelled on purpose (its run was retired): sending it again a second later
                // is exactly the request the cancel was meant to save.
                if (call.isCanceled()) {
                    result.failureReason = "cancelled";
                    return result;
                }
            }
            if (attempt < GOOGLE_REQUEST_RETRIES) quietSleep(GOOGLE_REQUEST_RETRY_DELAY_MS);
        }
        return result;
    }

    /** Milliseconds until Google may be asked again after a 429; 0 when it may be now. */
    public static long cooldownRemainingMs() {
        return COOLDOWN.remainingMs(SystemClock.elapsedRealtime());
    }

    /** Lane identity from the call tag: "SOUND#12" and "MEANING#13" throttle independently. */
    static String laneOf(Request request) {
        String tag = request == null ? null : request.tag(String.class);
        if (tag == null || tag.isEmpty()) return "default";
        int hash = tag.indexOf('#');
        return hash <= 0 ? tag : tag.substring(0, hash);
    }

    private static void throttleGoogleRequest(String lane) {
        long[] last = LANE_THROTTLES.computeIfAbsent(lane == null ? "default" : lane,
                key -> new long[] {0L});
        synchronized (last) {
            long now = SystemClock.elapsedRealtime();
            long waitMs = last[0] + GOOGLE_REQUEST_MIN_INTERVAL_MS - now;
            if (waitMs > 0L) quietSleep(waitMs);
            last[0] = SystemClock.elapsedRealtime();
        }
    }

    private static void quietSleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String parseTranslation(String body) {
        try {
            JsonArray root = JsonParser.parseString(body).getAsJsonArray();
            JsonArray sentences = root.get(0).getAsJsonArray();
            StringBuilder out = new StringBuilder();
            for (JsonElement element : sentences) {
                if (!element.isJsonArray()) continue;
                JsonArray sentence = element.getAsJsonArray();
                if (sentence.size() > 0 && !sentence.get(0).isJsonNull()) {
                    out.append(sentence.get(0).getAsString());
                }
            }
            return out.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    static Map<Integer, String> parseBatchTranslation(String body) {
        return splitByMarkers(parseTranslation(body));
    }

    private static String marker(int index) {
        return String.format(java.util.Locale.US, "[[SPX_%03d]]", index);
    }

    private static String stripMarkerEcho(String text, int index) {
        if (text == null) return "";
        return BATCH_MARKER_PATTERN.matcher(text).replaceAll("").trim();
    }

    public static boolean sameText(String a, String b) {
        return normalizeCompare(a).equals(normalizeCompare(b));
    }

    public static boolean shouldDisplayTranslation(String source, String translated) {
        return !isBlank(translated)
                && !sameText(source, translated)
                && !looksLikeRomanizationEcho(source, translated);
    }

    static boolean looksLikeRomanizationEcho(String source, String translated) {
        if (isBlank(source) || isBlank(translated)) return false;
        String out = normalizeCompare(translated);
        if (isBlank(out)) return false;
        for (String candidate : romanizationCandidates(source)) {
            if (!isBlank(candidate) && out.equals(normalizeCompare(candidate))) return true;
        }
        return false;
    }

    private static List<String> romanizationCandidates(String source) {
        ArrayList<String> out = new ArrayList<>();
        if (SpicyTextDetection.itemCyrillicTest(source)) {
            out.add(SpicyRomanizer.romanizeCyrillic(source, SpicyRomanizer.CYRILLIC_RUSSIAN, false));
            out.add(SpicyRomanizer.romanizeCyrillic(source, SpicyRomanizer.CYRILLIC_UKRAINIAN, false));
            // Google romanizes Central-Asian Cyrillic via Russian base letters
            // (ң→n not ng), so echo detection needs that variant too.
            String folded = foldCentralAsianToRussianBase(source);
            if (!folded.equals(source)) {
                out.add(SpicyRomanizer.romanizeCyrillic(folded, SpicyRomanizer.CYRILLIC_RUSSIAN, false));
            }
        }
        if (SpicyTextDetection.itemGreekTest(source)) out.add(SpicyRomanizer.romanizeGreek(source));
        if (SpicyTextDetection.itemKoreanTest(source)) {
            out.add(SpicyRomanizer.romanizeKorean(source));
            out.add(SpicyKoreanG2P.romanize(source));
        }
        if (SpicyTextDetection.itemChineseTest(source) && !SpicyTextDetection.hasKana(source)) {
            out.add(SpicyJapaneseChineseProcessor.romanizeChineseLine(source, "pinyin", false));
        }
        if (SpicyTextDetection.hasKana(source)) out.add(SpicyJapaneseChineseProcessor.romanizeJapaneseLine(source));
        return out;
    }

    private static String foldCentralAsianToRussianBase(String value) {
        return value
                .replace('ң', 'н').replace('Ң', 'Н')
                .replace('ө', 'о').replace('Ө', 'О')
                .replace('ү', 'у').replace('Ү', 'У')
                .replace('ә', 'а').replace('Ә', 'А')
                .replace('ғ', 'г').replace('Ғ', 'Г')
                .replace('қ', 'к').replace('Қ', 'К')
                .replace('ұ', 'у').replace('Ұ', 'У')
                .replace('һ', 'х').replace('Һ', 'Х');
    }

    private static String normalizeCompare(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('ң', 'n')
                .replace('ŋ', 'n')
                .replace('’', '\'')
                .replace('‘', '\'')
                .replace('“', '"')
                .replace('”', '"')
                .replace('–', '-')
                .replace('—', '-')
                .replace("…", "...");
        return normalized
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\p{S}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static final class BatchLine {
        public final int index;
        public final String text;

        public BatchLine(int index, String text) {
            this.index = index;
            this.text = text == null ? "" : text;
        }
    }

    public static final class BatchResult {
        public final Map<Integer, String> translations = new LinkedHashMap<>();
        public final Set<Integer> cachedIndices = new HashSet<>();
        public int requestedCount;
        public int networkAttempts;
        public int networkTranslatedCount;
        public int httpStatus;
        public String failureReason = "";
    }

    private static final class HttpResult {
        String body = "";
        int attempts;
        int status;
        String failureReason = "";
    }
}
