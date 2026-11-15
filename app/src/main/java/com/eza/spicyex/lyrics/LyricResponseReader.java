package com.eza.spicyex.lyrics;

import java.io.IOException;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

/** Byte budgets for decoded HTTP bodies, independent of provider authentication and retry policy. */
public final class LyricResponseReader {
    // A lyric body includes word timing, translations and JSON/XML framing, not just source text.
    // Keep generous headroom above the minimized AMLL/QQ/YRC regression corpus. This is a
    // transport ceiling, not a row/timing limit or a claim about the largest provider document.
    public static final int LYRIC_BYTES = 4 * 1024 * 1024;
    // QQ and NetEase request 20 metadata results. AMLL search also returns metadata, not lyrics.
    public static final int SEARCH_BYTES = 1024 * 1024;
    // LRCLIB search embeds complete lyrics for multiple candidates; metadata's budget is too small.
    // Reserve four individual-document budgets for the aggregated search body.
    public static final int LYRIC_SEARCH_BYTES = 16 * 1024 * 1024;
    // Normal Google romanization batches have at most 100 rows / 3000 source chars.
    // The response also includes translation/romanization and JSON framing.
    public static final int GOOGLE_BYTES = 1024 * 1024;

    private LyricResponseReader() {}

    /**
     * Reads a complete body or throws, and closes it just as ResponseBody.string() does.
     * OkHttp supplies the decoded stream (including transparent gzip). The declared length only
     * allows an early rejection; the stream is always counted, even if its length is deceptive.
     */
    public static String read(ResponseBody body, int maxBytes) throws IOException {
        if (maxBytes < 0) throw new IllegalArgumentException("negative response budget");
        try (ResponseBody ignored = body) {
            if (body.contentLength() > maxBytes) throw oversized();
            BufferedSource source = body.source();
            Buffer bytes = new Buffer();
            while (true) {
                long count = source.read(bytes, Math.min(8192L, maxBytes + 1L - bytes.size()));
                if (count == -1) break;
                if (bytes.size() > maxBytes) throw oversized();
            }
            // Use OkHttp's own charset/BOM handling, after the byte check and at EOF. Decoding
            // cannot split a UTF-8 character or turn a truncated prefix into a successful result.
            try (ResponseBody complete = ResponseBody.create(body.contentType(), bytes.size(), bytes)) {
                return complete.string();
            }
        }
    }

    private static IOException oversized() {
        return new IOException("Lyric response exceeds byte budget");
    }
}
