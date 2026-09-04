package com.eza.spicyex.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Projects authoritative line text onto timed child views without losing or doubling spaces. */
final class TimedTextRowProjection {
    private TimedTextRowProjection() {}

    static List<Chunk> project(List<String> rawChunks, String authoritativeText) {
        if (rawChunks == null || rawChunks.isEmpty()) return Collections.emptyList();
        List<String> pieces = alignedPieces(rawChunks, authoritativeText);
        ArrayList<Chunk> out = new ArrayList<>(pieces.size());
        for (int index = 0; index < pieces.size(); index++) {
            String piece = safe(pieces.get(index));
            int next = nextVisible(pieces, index + 1);
            boolean spaceAfter = !trimEdgeWhitespace(piece).isEmpty() && next >= 0
                    && (hasTrailingWhitespace(piece) || hasLeadingWhitespace(pieces.get(next)));
            out.add(new Chunk(trimEdgeWhitespace(piece), spaceAfter));
        }
        return out;
    }

    static boolean exactlyReconstructs(List<String> rawChunks, String authoritativeText) {
        return rawChunks != null && !rawChunks.isEmpty()
                && !safe(authoritativeText).isEmpty()
                && compact(rawChunks).equals(compact(authoritativeText));
    }

    private static List<String> alignedPieces(List<String> rawChunks, String authoritativeText) {
        String authoritative = safe(authoritativeText);
        if (!exactlyReconstructs(rawChunks, authoritative)) {
            return new ArrayList<>(rawChunks);
        }

        ArrayList<String> out = new ArrayList<>(rawChunks.size());
        int cursor = 0;
        int lastVisible = -1;
        for (String raw : rawChunks) {
            int needed = codePointCount(compact(safe(raw)));
            if (needed == 0) {
                out.add("");
                continue;
            }
            int start = cursor;
            int seen = 0;
            while (cursor < authoritative.length() && seen < needed) {
                int cp = authoritative.codePointAt(cursor);
                cursor += Character.charCount(cp);
                if (!Character.isWhitespace(cp)) seen++;
            }
            if (seen != needed) return new ArrayList<>(rawChunks);
            out.add(authoritative.substring(start, cursor));
            lastVisible = out.size() - 1;
        }
        if (cursor < authoritative.length() && lastVisible >= 0) {
            out.set(lastVisible, out.get(lastVisible) + authoritative.substring(cursor));
        }
        return out;
    }

    private static int nextVisible(List<String> pieces, int start) {
        for (int index = start; index < pieces.size(); index++) {
            if (!trimEdgeWhitespace(safe(pieces.get(index))).isEmpty()) return index;
        }
        return -1;
    }

    private static String compact(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) out.append(compact(safe(value)));
        return out.toString();
    }

    private static String compact(String value) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < value.length();) {
            int cp = value.codePointAt(index);
            if (!Character.isWhitespace(cp)) out.appendCodePoint(cp);
            index += Character.charCount(cp);
        }
        return out.toString();
    }

    private static String trimEdgeWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int cp = value.codePointAt(start);
            if (!Character.isWhitespace(cp)) break;
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = value.codePointBefore(end);
            if (!Character.isWhitespace(cp)) break;
            end -= Character.charCount(cp);
        }
        return value.substring(start, end);
    }

    private static boolean hasLeadingWhitespace(String value) {
        return value != null && !value.isEmpty() && Character.isWhitespace(value.codePointAt(0));
    }

    private static boolean hasTrailingWhitespace(String value) {
        return value != null && !value.isEmpty()
                && Character.isWhitespace(value.codePointBefore(value.length()));
    }

    private static int codePointCount(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    static final class Chunk {
        final String text;
        final boolean spaceAfter;

        Chunk(String text, boolean spaceAfter) {
            this.text = text;
            this.spaceAfter = spaceAfter;
        }
    }
}
