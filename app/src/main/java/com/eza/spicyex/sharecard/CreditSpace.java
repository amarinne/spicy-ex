package com.eza.spicyex.sharecard;

/**
 * Vertical room a template gives its credit block: the block hangs from a fixed bottom edge in the
 * template's metadata band, and the lyric box gives up only what the block would otherwise cover.
 */
final class CreditSpace {
    private CreditSpace() { }

    /** The provider row, then every uploader or maker row joined by newlines (empty when none). */
    static String[] rows(String credit) {
        if (credit == null || credit.isEmpty()) return new String[]{"", ""};
        int split = credit.indexOf('\n');
        if (split < 0) return new String[]{credit, ""};
        return new String[]{credit.substring(0, split), credit.substring(split + 1)};
    }

    private static final String UPLOADED = "uploaded by ";
    private static final String MADE = "made by ";

    /**
     * Character ranges {start, end} of the contributor names in {@code "uploaded by A, made by B"}
     * (either part may be missing); the lead-ins and the comma are not names. A name that itself
     * contains ", made by " ends early.
     */
    static int[][] nameSpans(String contributors) {
        if (contributors == null) return new int[0][];
        int length = contributors.length();
        int uploaderStart = contributors.startsWith(UPLOADED) ? UPLOADED.length() : -1;
        if (uploaderStart >= 0) {
            int joint = contributors.indexOf(", " + MADE, uploaderStart);
            int uploaderEnd = joint < 0 ? length : joint;
            int makerStart = joint < 0 ? -1 : joint + 2 + MADE.length();
            return spans(uploaderStart, uploaderEnd, makerStart, length);
        }
        if (contributors.startsWith(MADE)) return spans(MADE.length(), length, -1, length);
        return new int[0][];
    }

    private static int[][] spans(int firstStart, int firstEnd, int secondStart, int secondEnd) {
        boolean first = firstStart < firstEnd;
        boolean second = secondStart >= 0 && secondStart < secondEnd;
        int[][] out = new int[(first ? 1 : 0) + (second ? 1 : 0)][];
        int at = 0;
        if (first) out[at++] = new int[]{firstStart, firstEnd};
        if (second) out[at] = new int[]{secondStart, secondEnd};
        return out;
    }

    static boolean fits(float blockHeight, float room) {
        return blockHeight <= room;
    }

    /** Top of a block of {@code blockHeight} that ends at {@code anchorBottom}. */
    static float blockTop(float anchorBottom, float blockHeight) {
        return anchorBottom - blockHeight;
    }

    /**
     * How far a template's bottom band (title, code, rule) moves up to make room for a credit that
     * ends at {@code creditBottom}: the block and a gap, plus whatever the band's old foot hung below
     * that edge. Zero credit, zero lift.
     */
    static float lift(float blockHeight, float gap, float bandBottom, float creditBottom) {
        if (blockHeight <= 0f) return 0f;
        return blockHeight + gap + Math.max(0f, bandBottom - creditBottom);
    }

    /** The tallest block that still leaves the lyric {@code minLyric} of room above it. */
    static float maxBlockHeight(float boxTop, float anchorBottom, float gap, float minLyric) {
        return Math.max(0f, anchorBottom - gap - boxTop - minLyric);
    }

    /** The lyric box's height with the block reserved: never taller than before, never under minLyric. */
    static float lyricHeight(float boxTop, float boxHeight, float anchorBottom, float blockHeight,
                             float gap, float minLyric) {
        float free = blockTop(anchorBottom, blockHeight) - gap - boxTop;
        return Math.min(boxHeight, Math.max(Math.min(boxHeight, minLyric), free));
    }
}
