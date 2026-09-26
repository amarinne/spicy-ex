package com.eza.spicyex.settings;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds settings from whatever the user types - in any language, misspelled, in their own words.
 *
 * <p>Nothing here knows a particular language. The words come from the app's strings: every
 * setting's name in every language the module ships, its options and section, an optional
 * description written for search ({@code settings_search_<key>}), and synonym groups
 * ({@code search_terms_<concept>}) that each translation fills in with its own words. Adding a
 * language is adding its strings; this file does not change.
 *
 * <p>What does the work is script-level, and holds for any language:
 * <ul>
 * <li>Tokens: text splits into words at spaces, punctuation and script changes. Chinese and
 *     Japanese have no spaces, so their runs are compared as overlapping character pairs
 *     (bigrams) - the usual dictionary-free way to search CJK text. A run of Hangul or Latin
 *     typed without spaces ("이중탭하트", "doubletapheart") is also tried split into known
 *     words.</li>
 * <li>Folding: lower case, accents stripped, Hangul taken apart into its letters (jamo), so
 *     a typo inside a syllable is one letter off and a Korean word typed on an English keyboard
 *     layout ("qmffj" for 블러) can be recovered.</li>
 * <li>Word forms: a typed word that starts with a known word ("블러를", "fonts") or shares a
 *     long start with it ("размытия"/"размытие") counts - particles, plurals, case endings.</li>
 * <li>Typos: Damerau-Levenshtein, one edit for a word of four letters, two from nine.</li>
 * <li>Korean initials: "ㄱㅅㅎㄹ" finds "가사 흐림".</li>
 * <li>Ideas: a typed word naming a synonym group finds entries that mention any other member.</li>
 * </ul>
 *
 * <p>Pure Java - no Android - so it is unit-tested directly.
 */
public final class SettingsSearch {

    /**
     * The synonym groups, by id. Their words live in the strings files as
     * {@code search_terms_<id>}, comma-separated, one set per language.
     */
    public static final String[] CONCEPT_IDS = {
            "blur", "font", "size", "weight", "translation", "reading", "language", "ads", "cache",
            "status_bar", "like", "double", "seek", "tap", "long_press", "gesture", "color",
            "background", "dark", "animation", "glow", "bounce", "artwork", "sync", "source",
            "lyrics", "karaoke", "home", "share", "ai", "hide", "button", "spacing", "position",
            "focus", "connect", "music", "mute", "quality", "debug", "reset", "layout", "title",
            "icon", "screen", "auto", "notification",
    };

    /** One searchable thing: a setting, a section, or an editor-only setting. */
    public static final class Entry {
        public final Object target;
        public final String title;
        /** Where it lives, shown under the title ("Gestures", "Layout editor"). */
        public final String place;
        /** Its section's id; related results share it. */
        public final String group;
        final Doc titleDoc;
        final Doc allDoc;
        final String initials;
        Set<Integer> concepts = Collections.emptySet();

        /**
         * @param titles the name in every language available (the first is the one shown)
         * @param extra  descriptions, section names, option labels, the preference key
         */
        public Entry(Object target, List<String> titles, String place, String group, List<String> extra) {
            this.target = target;
            this.title = titles.isEmpty() ? "" : titles.get(0);
            this.place = place == null ? "" : place;
            this.group = group == null ? "" : group;
            StringBuilder t = new StringBuilder();
            for (String s : titles) t.append(' ').append(s);
            StringBuilder a = new StringBuilder(t);
            if (extra != null) for (String s : extra) a.append(' ').append(s);
            a.append(' ').append(this.place);
            titleDoc = new Doc(t.toString());
            allDoc = new Doc(a.toString());
            initials = initialsOf(t.toString());
        }
    }

    public static final class Result {
        public final Entry entry;
        public final float score;
        /** Not a match itself: shown because it is close to what matched. */
        public final boolean related;

        Result(Entry entry, float score, boolean related) {
            this.entry = entry;
            this.score = score;
            this.related = related;
        }
    }

    /** A text, folded and tokenised once. */
    static final class Doc {
        final String compact;
        final String[] words;
        final Set<String> bigrams = new HashSet<>();
        final Set<Character> cjkChars = new HashSet<>();

        Doc(String raw) {
            String folded = fold(raw);
            compact = folded.replace(" ", "");
            words = tokens(folded);
            for (String w : words) {
                if (!isCjk(w.charAt(0))) continue;
                for (int i = 0; i < w.length(); i++) {
                    cjkChars.add(w.charAt(i));
                    if (i + 1 < w.length()) bigrams.add(w.substring(i, i + 2));
                }
            }
        }
    }

    private final List<Entry> entries;
    /** Folded members of each concept, by index into {@link #CONCEPT_IDS} order given. */
    private final List<String[]> concepts = new ArrayList<>();
    /** Known non-CJK words, for splitting a run typed without spaces. */
    private final Set<String> vocabulary = new HashSet<>();
    private int longestWord;

    /**
     * @param conceptTerms each concept's words in every language, in any form (they are
     *                     folded here); order is the concept's identity
     */
    public SettingsSearch(List<Entry> entries, List<List<String>> conceptTerms) {
        this.entries = entries == null ? new ArrayList<>() : entries;
        if (conceptTerms != null) {
            for (List<String> terms : conceptTerms) {
                List<String> folded = new ArrayList<>();
                for (String term : terms) {
                    String f = fold(term).replace(" ", "");
                    if (!f.isEmpty()) folded.add(f);
                }
                concepts.add(folded.toArray(new String[0]));
            }
        }
        for (Entry entry : this.entries) {
            entry.concepts = conceptsIn(entry.allDoc);
            for (String w : entry.allDoc.words) addWord(w);
        }
        for (String[] members : concepts) for (String m : members) addWord(m);
    }

    private void addWord(String w) {
        if (w.length() < 2 || isCjk(w.charAt(0))) return;
        vocabulary.add(w);
        longestWord = Math.max(longestWord, w.length());
    }

    // --- Searching ---

    /** Matches, best first, then up to {@code maxRelated} related entries marked as such. */
    public List<Result> search(String query, int maxResults, int maxRelated) {
        List<Result> out = new ArrayList<>();
        List<String[]> readings = readings(query);
        if (readings.isEmpty()) return out;
        Set<Integer> queryConcepts = new HashSet<>();
        for (String[] reading : readings) for (String token : reading) queryConcepts.addAll(conceptsNamedBy(token));

        List<Result> matches = new ArrayList<>();
        for (Entry entry : entries) {
            float best = 0f;
            for (String[] reading : readings) best = Math.max(best, score(entry, reading));
            if (best > 0f) matches.add(new Result(entry, best, false));
        }
        Collections.sort(matches, (a, b) -> Float.compare(b.score, a.score));
        // Keep what is close to the best; a long tail of weak hits reads as noise.
        float cut = matches.isEmpty() ? 0f : matches.get(0).score * 0.45f;
        for (int i = 0; i < matches.size() && out.size() < maxResults; i++) {
            if (matches.get(i).score >= cut) out.add(matches.get(i));
        }

        if (maxRelated > 0 && !out.isEmpty()) {
            Set<Entry> shown = new HashSet<>();
            for (Result r : out) shown.add(r.entry);
            String group = out.get(0).entry.group;
            Set<Integer> shared = new HashSet<>(queryConcepts);
            shared.addAll(out.get(0).entry.concepts);
            List<Result> related = new ArrayList<>();
            for (Entry entry : entries) {
                if (shown.contains(entry)) continue;
                float closeness = group.equals(entry.group) ? 0.3f : 0f;
                for (int c : entry.concepts) if (shared.contains(c)) closeness += 0.25f;
                if (closeness >= 0.5f) related.add(new Result(entry, closeness, true));
            }
            Collections.sort(related, (a, b) -> Float.compare(b.score, a.score));
            for (int i = 0; i < related.size() && i < maxRelated; i++) out.add(related.get(i));
        }
        return out;
    }

    /**
     * The ways to read what was typed: its words as typed; runs typed without spaces split into
     * known words; and, for Latin letters only, the same keys read on a Korean keyboard.
     */
    List<String[]> readings(String query) {
        List<String[]> out = new ArrayList<>();
        String folded = fold(query);
        String[] typed = tokens(folded);
        if (typed.length == 0) return out;
        out.add(typed);
        String[] split = splitCompounds(typed);
        if (split != null) out.add(split);
        String raw = query == null ? "" : query.trim();
        if (!raw.isEmpty() && raw.matches("[A-Za-z ]+")) {
            String hangul = fromKoreanKeyboard(raw);
            String[] keyed = tokens(fold(hangul));
            if (keyed.length > 0) {
                out.add(keyed);
                String[] keyedSplit = splitCompounds(keyed);
                if (keyedSplit != null) out.add(keyedSplit);
            }
        }
        return out;
    }

    float score(Entry entry, String[] tokens) {
        float total = 0f;
        int matched = 0;
        Set<Integer> named = new HashSet<>();
        for (String token : tokens) {
            float s = Math.max(tokenScore(token, entry.titleDoc, entry) * 1.2f,
                    tokenScore(token, entry.allDoc, null));
            Set<Integer> c = conceptsNamedBy(token);
            named.addAll(c);
            for (int id : c) if (entry.concepts.contains(id)) s = Math.max(s, 0.66f);
            if (s > 0f) matched++;
            total += s;
        }
        int n = tokens.length;
        // Most of what was typed must be found: all of one or two words, half of more.
        int needed = n <= 2 ? n : (n + 1) / 2;
        if (matched < needed) return 0f;
        float score = total / n;
        if (matched == n) score += 0.1f;
        if (named.size() >= 2 && entry.concepts.containsAll(named)) score += 0.2f;
        if (n > 1) {
            StringBuilder phrase = new StringBuilder();
            for (String t : tokens) phrase.append(t);
            if (entry.titleDoc.compact.contains(phrase)) score += 0.4f;
        }
        return score;
    }

    /** How well one token matches a text; 0 when it does not. */
    static float tokenScore(String token, Doc doc, Entry initialsOf) {
        if (token.isEmpty()) return 0f;
        if (isCjk(token.charAt(0))) return cjkScore(token, doc);
        if (initialsOf != null && token.length() >= 2 && allInitials(token)) {
            return initialsOf.initials.contains(token) ? 0.9f : 0f;
        }
        float best = 0f;
        for (String w : doc.words) {
            if (isCjk(w.charAt(0))) continue;
            if (w.startsWith(token)) return 1f;
            // The typed word carries an ending the known one lacks: a particle, plural, case.
            if (w.length() >= 4 && token.startsWith(w) && token.length() - w.length() <= 5) {
                best = Math.max(best, 0.88f);
            }
            // Same stem, different ending: a long shared start.
            int common = commonPrefix(w, token);
            if (common >= 5 && common >= 0.7f * Math.min(w.length(), token.length())) {
                best = Math.max(best, 0.8f);
            }
        }
        if (best < 0.78f && token.length() >= 3 && doc.compact.contains(token)) best = 0.78f;
        if (best > 0f || token.length() < 4) return best;
        int allowed = token.length() >= 9 ? 2 : 1;
        for (String w : doc.words) {
            if (w.length() < 3 || isCjk(w.charAt(0))) continue;
            int d = distance(token, w);
            // A word still being typed: compare with the start of a longer one.
            if (w.length() > token.length()) d = Math.min(d, distance(token, w.substring(0, token.length())));
            if (d <= allowed) best = Math.max(best, 0.7f - 0.12f * d);
        }
        return best;
    }

    /** Overlapping character pairs found, as a share; single characters by presence. */
    private static float cjkScore(String token, Doc doc) {
        if (token.length() == 1) return doc.cjkChars.contains(token.charAt(0)) ? 0.6f : 0f;
        int found = 0;
        int total = token.length() - 1;
        for (int i = 0; i < total; i++) if (doc.bigrams.contains(token.substring(i, i + 2))) found++;
        float share = found / (float) total;
        if (share >= 0.999f && doc.compact.contains(token)) return 1f;
        return share >= 0.5f ? 0.5f + 0.4f * share : 0f;
    }

    /** The concepts a token names: equal to, the start of, extending, or one typo off a member. */
    Set<Integer> conceptsNamedBy(String token) {
        Set<Integer> out = new HashSet<>();
        if (token.length() < 2) return out;
        boolean cjk = isCjk(token.charAt(0));
        for (int i = 0; i < concepts.size(); i++) {
            for (String m : concepts.get(i)) {
                boolean hit;
                if (cjk) {
                    hit = isCjk(m.charAt(0)) && m.length() >= 2
                            && (token.contains(m) || m.startsWith(token));
                } else {
                    hit = m.equals(token)
                            || (token.length() >= 3 && m.startsWith(token))
                            || (m.length() >= 4 && token.startsWith(m) && token.length() - m.length() <= 5)
                            || (token.length() >= 5 && Math.abs(m.length() - token.length()) <= 1
                                && distance(token, m) <= 1);
                }
                if (hit) {
                    out.add(i);
                    break;
                }
            }
        }
        return out;
    }

    private Set<Integer> conceptsIn(Doc doc) {
        Set<Integer> out = new HashSet<>();
        Set<String> words = new HashSet<>();
        Collections.addAll(words, doc.words);
        for (int i = 0; i < concepts.size(); i++) {
            for (String m : concepts.get(i)) {
                // A short Latin member must be a whole word ("ad" is not in "shadow"); longer
                // ones, and every Hangul/CJK one, may sit inside a longer run.
                boolean shortLatin = m.length() <= 3 && m.chars().allMatch(ch -> ch < 0x80);
                boolean found = shortLatin ? words.contains(m) : doc.compact.contains(m);
                if (found) {
                    out.add(i);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Splits runs typed without spaces into known words when that explains most of the run
     * ("이중탭하트" -> 이중탭, 하트). Null when nothing splits.
     */
    String[] splitCompounds(String[] tokens) {
        List<String> out = new ArrayList<>();
        boolean changed = false;
        for (String token : tokens) {
            List<String> pieces = token.length() >= 4 && !isCjk(token.charAt(0)) ? segment(token) : null;
            if (pieces != null && pieces.size() >= 2) {
                out.addAll(pieces);
                changed = true;
            } else {
                out.add(token);
            }
        }
        return changed ? out.toArray(new String[0]) : null;
    }

    /** Fewest unknown letters, then fewest pieces; null unless 70% is known words. */
    private List<String> segment(String s) {
        int n = s.length();
        int[] unknown = new int[n + 1];
        int[] pieces = new int[n + 1];
        int[] from = new int[n + 1];
        boolean[] known = new boolean[n + 1];
        java.util.Arrays.fill(unknown, Integer.MAX_VALUE / 2);
        unknown[0] = 0;
        for (int i = 1; i <= n; i++) {
            // One unknown letter.
            if (unknown[i - 1] + 1 < unknown[i]
                    || (unknown[i - 1] + 1 == unknown[i] && pieces[i - 1] + 1 < pieces[i])) {
                unknown[i] = unknown[i - 1] + 1;
                pieces[i] = pieces[i - 1] + 1;
                from[i] = i - 1;
                known[i] = false;
            }
            for (int j = Math.max(0, i - longestWord); j <= i - 2; j++) {
                if (!vocabulary.contains(s.substring(j, i))) continue;
                if (unknown[j] < unknown[i] || (unknown[j] == unknown[i] && pieces[j] + 1 < pieces[i])) {
                    unknown[i] = unknown[j];
                    pieces[i] = pieces[j] + 1;
                    from[i] = j;
                    known[i] = true;
                }
            }
        }
        if (unknown[n] > n * 0.3f) return null;
        List<String> out = new ArrayList<>();
        StringBuilder stray = new StringBuilder();
        for (int i = n; i > 0; i = from[i]) {
            if (known[i]) {
                if (stray.length() >= 2) out.add(stray.reverse().toString());
                stray.setLength(0);
                out.add(s.substring(from[i], i));
            } else {
                stray.append(s.charAt(i - 1));
            }
        }
        if (stray.length() >= 2) out.add(stray.reverse().toString());
        Collections.reverse(out);
        return out;
    }

    // --- Text ---

    private static final String LEAD = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
    private static final String[] VOWEL = {"ㅏ", "ㅐ", "ㅑ", "ㅒ", "ㅓ", "ㅔ", "ㅕ", "ㅖ", "ㅗ", "ㅗㅏ", "ㅗㅐ",
            "ㅗㅣ", "ㅛ", "ㅜ", "ㅜㅓ", "ㅜㅔ", "ㅜㅣ", "ㅠ", "ㅡ", "ㅡㅣ", "ㅣ"};
    private static final String[] TAIL = {"", "ㄱ", "ㄲ", "ㄱㅅ", "ㄴ", "ㄴㅈ", "ㄴㅎ", "ㄷ", "ㄹ", "ㄹㄱ", "ㄹㅁ",
            "ㄹㅂ", "ㄹㅅ", "ㄹㅌ", "ㄹㅍ", "ㄹㅎ", "ㅁ", "ㅂ", "ㅂㅅ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"};
    /** Compound letters typed on their own, taken apart the same way. */
    private static final Map<Character, String> COMPOUND = new HashMap<>();

    static {
        String[][] pairs = {{"ㅘ", "ㅗㅏ"}, {"ㅙ", "ㅗㅐ"}, {"ㅚ", "ㅗㅣ"}, {"ㅝ", "ㅜㅓ"}, {"ㅞ", "ㅜㅔ"}, {"ㅟ", "ㅜㅣ"},
                {"ㅢ", "ㅡㅣ"}, {"ㄳ", "ㄱㅅ"}, {"ㄵ", "ㄴㅈ"}, {"ㄶ", "ㄴㅎ"}, {"ㄺ", "ㄹㄱ"}, {"ㄻ", "ㄹㅁ"}, {"ㄼ", "ㄹㅂ"},
                {"ㄽ", "ㄹㅅ"}, {"ㄾ", "ㄹㅌ"}, {"ㄿ", "ㄹㅍ"}, {"ㅀ", "ㄹㅎ"}, {"ㅄ", "ㅂㅅ"}};
        for (String[] p : pairs) COMPOUND.put(p[0].charAt(0), p[1]);
    }

    /** Lower case, accents stripped, Hangul as its letters, punctuation to spaces. */
    public static String fold(String value) {
        if (value == null) return "";
        String lower = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder(lower.length() * 2);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) {
                int s = c - 0xAC00;
                out.append(LEAD.charAt(s / 588)).append(VOWEL[(s % 588) / 28]).append(TAIL[s % 28]);
            } else if (COMPOUND.containsKey(c)) {
                out.append(COMPOUND.get(c));
            } else {
                out.append(c);
            }
        }
        // Accents off Latin/Cyrillic letters; Hangul is already letters, CJK has none.
        String stripped = Normalizer.normalize(out, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return stripped.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** Words, also split where the script changes ("lrclib가사" -> lrclib, 가사). */
    static String[] tokens(String folded) {
        List<String> out = new ArrayList<>();
        for (String word : folded.split(" +")) {
            if (word.isEmpty()) continue;
            int start = 0;
            for (int i = 1; i <= word.length(); i++) {
                if (i == word.length() || script(word.charAt(i)) != script(word.charAt(i - 1))) {
                    out.add(word.substring(start, i));
                    start = i;
                }
            }
        }
        return out.toArray(new String[0]);
    }

    private static int script(char c) {
        if (c >= 0x3131 && c <= 0x318E) return 1; // Hangul letters
        if (isCjk(c)) return 2;
        return 0;
    }

    static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x3040 && c <= 0x30FF) || (c >= 0x31F0 && c <= 0x31FF) || (c >= 0xF900 && c <= 0xFAFF);
    }

    private static boolean allInitials(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < 0x3131 || c > 0x314E) return false;
        }
        return true;
    }

    /** The leading consonant of each Hangul syllable ("가사 흐림" -> ㄱㅅㅎㄹ). */
    static String initialsOf(String raw) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) out.append(LEAD.charAt((c - 0xAC00) / 588));
        }
        return out.toString();
    }

    private static final String KEYS = "qwertyuiopasdfghjklzxcvbnmQWERTOP";
    private static final String JAMO = "ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡㅃㅉㄸㄲㅆㅒㅖ";

    /** The letters those keys type on a standard (2-set) Korean keyboard. */
    static String fromKoreanKeyboard(String keys) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < keys.length(); i++) {
            char c = keys.charAt(i);
            int at = KEYS.indexOf(c);
            if (at < 0) at = KEYS.indexOf(Character.toLowerCase(c));
            out.append(at >= 0 ? JAMO.charAt(at) : c);
        }
        return out.toString();
    }

    private static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }

    /** Damerau-Levenshtein (optimal string alignment) distance; 3 means "far". */
    static int distance(String a, String b) {
        int n = a.length();
        int m = b.length();
        if (Math.abs(n - m) > 2) return 3;
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, d[i - 2][j - 2] + 1);
                }
                d[i][j] = v;
            }
        }
        return d[n][m];
    }

    /** Splits a comma-separated {@code search_terms_*} string. */
    public static List<String> splitTerms(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        for (String part : value.split("[,，、;]")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Merges several comma-separated term strings (one per language) into one list. */
    public static List<String> mergeTerms(List<String> perLanguage) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : perLanguage) out.addAll(splitTerms(value));
        return new ArrayList<>(out);
    }
}
