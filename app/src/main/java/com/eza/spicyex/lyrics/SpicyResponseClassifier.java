package com.eza.spicyex.lyrics;

/** Pure quality classifier for parsed Spicy API lyric documents. */
public final class SpicyResponseClassifier {
    static final double MIXED_CONFUSABLE_WORD_RATIO_THRESHOLD = 0.30d;

    private SpicyResponseClassifier() {
    }

    public static Result classify(LyricsDocument doc) {
        if (doc == null) return Result.ok();

        Integer status = doc.spicyQueryStatus;
        if (status != null && status != 200) {
            return Result.poisoned("QUERY_STATUS_NON_200");
        }

        String format = LyricsDocument.safe(doc.spicyFormat).trim();
        boolean formatPresent = !format.isEmpty();
        boolean jsonFormat = "json".equalsIgnoreCase(format);
        boolean staticType = "Static".equalsIgnoreCase(doc.type);
        if (formatPresent && !jsonFormat) {
            if (staticType && !doc.spicyPackedPayload && "plain".equalsIgnoreCase(format)) {
                return Result.poisoned("DOWNGRADED_STATIC_PLAIN");
            }
            return Result.poisoned("UNKNOWN_SUSPICIOUS");
        }

        if (mixedConfusableWordRatio(doc) > MIXED_CONFUSABLE_WORD_RATIO_THRESHOLD) {
            return Result.poisoned("POISON_HOMOGLYPH_NOTICE");
        }

        return Result.ok();
    }

    public static void apply(LyricsDocument doc) {
        if (doc == null) return;
        Result result = classify(doc);
        doc.spicyPoisoned = result.spicyPoisoned;
        doc.spicyQualityReason = result.spicyQualityReason;
    }

    static double mixedConfusableWordRatio(LyricsDocument doc) {
        WordCounts counts = new WordCounts();
        if (doc == null) return 0d;
        for (LyricsLine line : doc.lines) {
            addLine(counts, line == null ? "" : line.text);
            addLine(counts, line == null ? "" : line.romanizedText);
            addLine(counts, line == null ? "" : line.translatedText);
        }
        if (counts.totalWords == 0) return 0d;
        return (double) counts.mixedConfusableWords / (double) counts.totalWords;
    }

    private static void addLine(WordCounts counts, String text) {
        if (text == null || text.isEmpty()) return;
        boolean hasLatin = false;
        boolean hasConfusableCyrillic = false;
        int letters = 0;
        for (int offset = 0; offset < text.length(); ) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isLetter(cp)) {
                letters++;
                if (isLatin(cp)) hasLatin = true;
                if (isConfusableCyrillic(cp)) hasConfusableCyrillic = true;
                continue;
            }
            finishWord(counts, letters, hasLatin, hasConfusableCyrillic);
            hasLatin = false;
            hasConfusableCyrillic = false;
            letters = 0;
        }
        finishWord(counts, letters, hasLatin, hasConfusableCyrillic);
    }

    private static void finishWord(WordCounts counts, int letters, boolean hasLatin, boolean hasConfusableCyrillic) {
        if (letters <= 0) return;
        counts.totalWords++;
        if (hasLatin && hasConfusableCyrillic) counts.mixedConfusableWords++;
    }

    private static boolean isLatin(int cp) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
        return block == Character.UnicodeBlock.BASIC_LATIN
                || block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT
                || block == Character.UnicodeBlock.LATIN_EXTENDED_A
                || block == Character.UnicodeBlock.LATIN_EXTENDED_B
                || block == Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL;
    }

    private static boolean isConfusableCyrillic(int cp) {
        switch (cp) {
            case '\u0410':
            case '\u0412':
            case '\u0421':
            case '\u0415':
            case '\u041D':
            case '\u041A':
            case '\u041C':
            case '\u041E':
            case '\u0420':
            case '\u0422':
            case '\u0425':
            case '\u0423':
            case '\u0430':
            case '\u0441':
            case '\u0435':
            case '\u043E':
            case '\u0440':
            case '\u0445':
            case '\u0443':
                return true;
            default:
                return false;
        }
    }

    public static final class Result {
        public final boolean spicyPoisoned;
        public final String spicyQualityReason;

        private Result(boolean spicyPoisoned, String spicyQualityReason) {
            this.spicyPoisoned = spicyPoisoned;
            this.spicyQualityReason = spicyQualityReason;
        }

        static Result ok() {
            return new Result(false, null);
        }

        static Result poisoned(String reason) {
            return new Result(true, reason);
        }
    }

    private static final class WordCounts {
        int totalWords;
        int mixedConfusableWords;
    }
}
