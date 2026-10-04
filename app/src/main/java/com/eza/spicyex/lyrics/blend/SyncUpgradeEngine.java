package com.eza.spicyex.lyrics.blend;

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;
import com.eza.spicyex.lyrics.catalog.CatalogCandidate;
import com.eza.spicyex.lyrics.catalog.CatalogDelivery;
import com.eza.spicyex.lyrics.catalog.CatalogSource;
import com.eza.spicyex.lyrics.catalog.CatalogSource.MatchMethod;
import com.eza.spicyex.lyrics.catalog.CatalogSource.SourceId;
import com.eza.spicyex.lyrics.providers.SpicyOrgPolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Conservative shared-text timing transfer. This engine does not insert donor-only vocals. */
public final class SyncUpgradeEngine {
    private static final long DURATION_TOLERANCE_MS = 1000;
    private static final long UNCERTAINTY_MS = 120;
    private static final int BARRIER = 0;

    private SyncUpgradeEngine() {}

    public static final class Decision {
        public final int row;
        public final boolean upgraded;
        public final String reason;
        public final String transform;
        public final double slope;
        public final double offsetMs;
        public final List<String> donorRefs;

        private Decision(Match match) {
            row = match.row;
            upgraded = match.upgraded;
            reason = match.reason;
            transform = match.transform == null ? "" : match.transform.kind;
            slope = match.transform == null ? 1 : match.transform.slope;
            offsetMs = match.transform == null ? 0 : match.transform.shift;
            List<String> refs = new ArrayList<>();
            for (Ref ref : match.refs) refs.add(ref.row + ":" + ref.segment);
            donorRefs = Collections.unmodifiableList(refs);
        }
    }

    public static final class Result {
        /** Authored spans remain intact. Accepted timing lives only in derivedSyllables. */
        public final LyricsDocument document;
        public final int upgradedRows;
        public final String rejectionReason;
        public final List<Decision> decisions;
        public final SyncUpgradeProvenance provenance;

        private Result(LyricsDocument document, int upgradedRows, String rejectionReason,
                       List<Decision> decisions, SyncUpgradeProvenance provenance) {
            this.document = document;
            this.upgradedRows = upgradedRows;
            this.rejectionReason = rejectionReason;
            this.decisions = Collections.unmodifiableList(decisions);
            this.provenance = provenance;
        }

        public LyricsDocument renderDocument() { return renderProjection(document, document); }
        public LyricsDocument renderDocument(LyricsDocument composed) {
            return renderProjection(composed, document);
        }
    }

    /** The caller supplies verified Spotify identity; catalog metadata binds provider identities. */
    public static Result upgrade(String targetSpotifyId, long targetDurationMs,
                                 LyricsDocument anchor, LyricsDocument donor, long nowMs) {
        return upgrade(targetSpotifyId, targetDurationMs, anchor, donor, null, null, nowMs);
    }

    public static Result upgrade(String targetSpotifyId, long targetDurationMs,
                                 LyricsDocument anchor, LyricsDocument donor,
                                 CatalogCandidate anchorCandidate, CatalogCandidate donorCandidate,
                                 long nowMs) {
        String target = CatalogSource.bareTrackId(targetSpotifyId);
        Binding a = new Binding(anchor, anchorCandidate);
        Binding d = new Binding(donor, donorCandidate);
        String anchorStructure = anchor == null ? "invalid_target_or_document" : validateDocument(anchor, true);
        String error = validateIdentity(target, targetDurationMs, anchor, donor, a, d, nowMs);
        if (error.isEmpty()) error = anchorStructure;
        if (error.isEmpty()) error = validateDocument(donor, false);
        LyricsDocument output = anchorStructure.isEmpty() ? LyricsDocument.copyOf(anchor) : null;
        if (output != null) {
            output.syncUpgradeProvenance = null;
            for (LyricsLine row : output.lines) row.derivedSyllables.clear();
        }
        if (!error.isEmpty()) return new Result(output, 0, error, new ArrayList<>(), null);

        Stream stream = donorStream(donor);
        List<Match> matches = matchRows(anchor, donor, stream);
        List<List<Match>> islands = islands(matches, stream);
        int count = 0;
        for (List<Match> island : islands) {
            Transform transform = fit(island);
            for (Match match : island) {
                if (transform == null) {
                    match.reason = "uncorroborated_timing";
                    continue;
                }
                prepare(output, donor, stream, match, transform, targetDurationMs);
            }
            // A rejected row cannot corroborate timing across a gap or authorize a lone neighbor.
            for (List<Match> confirmed : islands(island, stream)) {
                Transform accepted = fit(confirmed);
                boolean valid = accepted != null;
                if (valid) {
                    for (Match match : confirmed) {
                        prepare(output, donor, stream, match, accepted, targetDurationMs);
                        valid &= match.reason.isEmpty();
                    }
                }
                for (Match match : confirmed) {
                    if (!valid) {
                        if (match.reason.isEmpty()) match.reason = "uncorroborated_timing";
                        continue;
                    }
                    output.lines.get(match.row).derivedSyllables.addAll(match.spans);
                    match.upgraded = true;
                    match.reason = "shared_text_supported";
                    match.transform = accepted;
                    count++;
                }
            }
        }
        List<Decision> decisions = new ArrayList<>();
        for (Match match : matches) decisions.add(new Decision(match));
        SyncUpgradeProvenance provenance = count == 0 ? null : new SyncUpgradeProvenance(
                a.candidateId, a.digest, d.candidateId, d.digest, d.source.id,
                safe(donor.provider), "Syllable".equalsIgnoreCase(donor.type) ? "Syllable" : "Word",
                SpicyOrgPolicy.isRestricted(anchor)
                ? anchor.spicyOrgFetchedAtMs : 0, count);
        output.syncUpgradeProvenance = provenance;
        return new Result(output, count, "", decisions, provenance);
    }

    /** Apply timing after language composition. A changed row identity rejects the whole overlay. */
    public static LyricsDocument renderProjection(LyricsDocument composed, LyricsDocument derived) {
        LyricsDocument output = LyricsDocument.copyOf(composed);
        if (output == null || derived == null || derived.syncUpgradeProvenance == null
                || !Objects.equals(output.trackId, derived.trackId)
                || output.durationMs != derived.durationMs
                || !Objects.equals(output.fetchSource, derived.fetchSource)
                || !Objects.equals(output.catalogCandidateId, derived.catalogCandidateId)
                || output.spicyOrgFetchedAtMs != derived.spicyOrgFetchedAtMs
                || output.lines.size() != derived.lines.size()
                || !documentDigest(output).equals(documentDigest(derived))) return output;
        for (int i = 0; i < output.lines.size(); i++) {
            LyricsLine a = output.lines.get(i), b = derived.lines.get(i);
            if (!Objects.equals(a.text, b.text) || a.startMs != b.startMs || a.endMs != b.endMs
                    || a.interlude != b.interlude || a.oppositeAligned != b.oppositeAligned) return output;
        }
        boolean upgraded = false;
        for (int i = 0; i < output.lines.size(); i++) {
            LyricsLine row = output.lines.get(i), timing = derived.lines.get(i);
            if (timing.derivedSyllables.isEmpty()) continue;
            row.syllables.clear();
            row.derivedSyllables.clear();
            for (SyllableSegment span : timing.derivedSyllables) {
                row.syllables.add(SyllableSegment.copyOf(span));
                row.derivedSyllables.add(SyllableSegment.copyOf(span));
            }
            upgraded = true;
        }
        if (upgraded) {
            output.type = derived.syncUpgradeProvenance.donorTimingType;
            output.syncUpgradeProvenance = derived.syncUpgradeProvenance;
        }
        return output;
    }

    private static final class Binding {
        final SourceId source;
        final MatchMethod method;
        final String trackId, itemId, candidateId, digest;
        final boolean healthy;
        Binding(LyricsDocument doc, CatalogCandidate candidate) {
            CatalogDelivery delivery = doc == null ? null : doc.catalogDelivery;
            source = candidate != null ? candidate.sourceId : delivery == null ? null : delivery.source;
            method = candidate != null ? candidate.matchMethod : delivery == null ? null : delivery.matchMethod;
            trackId = CatalogSource.bareTrackId(candidate != null ? candidate.trackId : doc == null ? "" : doc.trackId);
            itemId = candidate != null ? candidate.providerItemId : delivery == null ? "" : delivery.providerItemId;
            candidateId = candidate != null ? candidate.candidateId : doc == null ? "" : safe(doc.catalogCandidateId);
            digest = candidate != null ? candidate.canonicalDigest : documentDigest(doc);
            healthy = candidate == null || candidate.timingHealthy;
        }
    }

    private static String validateIdentity(String target, long duration, LyricsDocument anchor,
                                          LyricsDocument donor, Binding a, Binding d, long nowMs) {
        if (target.isEmpty() || duration <= 0 || anchor == null || donor == null) return "invalid_target_or_document";
        if (anchor.syncUpgradeProvenance != null || donor.syncUpgradeProvenance != null)
            return "derived_input_not_provider_candidate";
        if (!target.equals(a.trackId) || !target.equals(d.trackId)
                || !target.equals(CatalogSource.bareTrackId(anchor.trackId))
                || !target.equals(CatalogSource.bareTrackId(donor.trackId))) return "track_identity_mismatch";
        if (a.source != SourceId.APPLE && a.source != SourceId.SPOTIFY_NATIVE && a.source != SourceId.SPICY_ORG)
            return "unsupported_anchor_source";
        if (a.method != MatchMethod.EXACT_SPOTIFY_ID && a.method != MatchMethod.EXACT_PROVIDER_MAPPING)
            return "unverified_anchor_identity";
        if (a.itemId.isEmpty() || (a.method == MatchMethod.EXACT_SPOTIFY_ID
                && !target.equals(CatalogSource.bareTrackId(a.itemId)))) return "anchor_provider_identity_mismatch";
        if (a.source == SourceId.SPOTIFY_NATIVE && a.method != MatchMethod.EXACT_SPOTIFY_ID)
            return "unverified_anchor_identity";
        if (CatalogSource.inferSourceId(anchor.fetchSource, anchor.provider) != a.source)
            return "anchor_origin_mismatch";
        if (a.source == SourceId.SPICY_ORG && (!SpicyOrgPolicy.isRestricted(anchor)
                || SpicyOrgPolicy.expires(anchor, nowMs) || !("spicy_lyrics".equals(anchor.spicyOrgSource)
                || "apple_music".equals(anchor.spicyOrgSource) || "spotify".equals(anchor.spicyOrgSource))))
            return "invalid_spicy_org_origin";
        if (anchor.spicyPoisoned || !a.healthy || anchor.durationMs <= 0
                || Math.abs(anchor.durationMs - duration) > DURATION_TOLERANCE_MS)
            return "unhealthy_anchor";
        if (d.source != SourceId.QQ && d.source != SourceId.NETEASE) return "unsupported_donor_source";
        if (d.method == null || d.method == MatchMethod.WEAK || d.method == MatchMethod.KARAOKE_SUBSTITUTION)
            return "unverified_donor_identity";
        if (d.itemId.isEmpty() || CatalogSource.inferSourceId(donor.fetchSource, donor.provider) != d.source)
            return "donor_origin_mismatch";
        if (donor.spicyPoisoned || !d.healthy) return "unhealthy_donor";
        return "";
    }

    private static String validateDocument(LyricsDocument document, boolean anchor) {
        if (document.durationMs <= 0 || document.lines.isEmpty()) return "invalid_document_duration_or_rows";
        long previousEnd = -1;
        for (LyricsLine row : document.lines) {
            if (row == null || row.text == null || row.text.indexOf('\0') >= 0
                    || row.syllables == null || row.backgroundLines == null || row.derivedSyllables == null
                    || row.startMs < 0 || row.endMs <= row.startMs || row.endMs > document.durationMs)
                return "invalid_row_interval";
            if (!row.derivedSyllables.isEmpty()) return "derived_input_not_provider_candidate";
            if (anchor && !row.interlude && row.startMs < previousEnd) return "overlapping_anchor_rows";
            if (anchor && !row.interlude) previousEnd = row.endMs;
            for (SyllableSegment span : row.syllables) {
                if (span == null || span.text == null || span.text.indexOf('\0') >= 0
                        || span.startMs < row.startMs || span.endMs <= span.startMs || span.endMs > row.endMs)
                    return "invalid_segment_interval";
            }
        }
        return "";
    }

    private static final class Normalized {
        final int[] chars, owners;
        Normalized(List<Integer> chars, List<Integer> owners) {
            this.chars = chars.stream().mapToInt(Integer::intValue).toArray();
            this.owners = owners.stream().mapToInt(Integer::intValue).toArray();
        }
    }

    /** Match Python casefold without erasing lexical spaces, including expanded codepoints. */
    private static String fold(int cp) {
        if (cp == 0x131) return "\u0131";
        if (cp == 0x1e9e) return "ss";
        if (cp >= 0x13a0 && cp <= 0x13f5) return new String(Character.toChars(cp));
        if (cp >= 0x13f8 && cp <= 0x13fd) return new String(Character.toChars(cp - 8));
        if (cp >= 0xab70 && cp <= 0xabbf) return new String(Character.toChars(cp - 0xab70 + 0x13a0));
        return new String(Character.toChars(cp)).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }

    private static boolean lexical(int cp) {
        int type = Character.getType(cp);
        return Character.isLetterOrDigit(cp) || type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.LETTER_NUMBER || type == Character.OTHER_NUMBER;
    }

    private static boolean eastAsian(int cp) {
        return (cp >= 0x3040 && cp <= 0x30ff) || (cp >= 0x3400 && cp <= 0x9fff);
    }

    private static Normalized normalize(List<Integer> raw, List<Integer> refs) {
        List<Integer> chars = new ArrayList<>(), owners = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            int cp = raw.get(i);
            String folded = cp == BARRIER ? "\0" : lexical(cp) ? fold(cp) : " ";
            for (int item : folded.codePoints().toArray()) {
                if (item == ' ' && (chars.isEmpty() || chars.get(chars.size() - 1) == ' ')) continue;
                chars.add(item);
                owners.add(refs.get(i));
            }
        }
        List<Integer> kept = new ArrayList<>(), keptOwners = new ArrayList<>();
        for (int i = 0; i < chars.size(); i++) {
            if (chars.get(i) == ' ' && (i == 0 || i == chars.size() - 1
                    || (eastAsian(chars.get(i - 1)) && eastAsian(chars.get(i + 1))))) continue;
            kept.add(chars.get(i));
            keptOwners.add(owners.get(i));
        }
        return new Normalized(kept, keptOwners);
    }

    private static Normalized normalize(String text) {
        List<Integer> chars = new ArrayList<>(), owners = new ArrayList<>();
        int[] cps = safe(text).codePoints().toArray();
        for (int i = 0; i < cps.length; i++) { chars.add(cps[i]); owners.add(i); }
        return normalize(chars, owners);
    }

    private static final class Ref {
        final int row, segment;
        Ref(int row, int segment) { this.row = row; this.segment = segment; }
    }

    private static final class Stream {
        final Normalized text;
        final List<Ref> refs;
        final int[][] bounds;
        Stream(Normalized text, List<Ref> refs) {
            this.text = text;
            this.refs = refs;
            bounds = new int[refs.size()][2];
            for (int[] bound : bounds) Arrays.fill(bound, -1);
            for (int i = 0; i < text.chars.length; i++) {
                int ref = text.owners[i];
                if (ref < 0 || text.chars[i] == ' ' || text.chars[i] == BARRIER) continue;
                if (bounds[ref][0] < 0) bounds[ref][0] = i;
                bounds[ref][1] = i;
            }
        }
    }

    private static void append(List<Integer> raw, List<Integer> refs, String text, int ref) {
        for (int cp : text.codePoints().toArray()) { raw.add(cp); refs.add(ref); }
    }

    private static boolean supported(LyricsLine row) {
        return !row.interlude && row.backgroundLines.isEmpty()
                && row.syllables.stream().noneMatch(s -> s.bgWord || s.dot);
    }

    private static Stream donorStream(LyricsDocument donor) {
        List<Integer> raw = new ArrayList<>(), owners = new ArrayList<>();
        List<Ref> refs = new ArrayList<>();
        long previousEnd = -1;
        for (int i = 0; i < donor.lines.size(); i++) {
            LyricsLine row = donor.lines.get(i);
            List<Integer> parts = new ArrayList<>(), partOwners = new ArrayList<>();
            boolean eligible = supported(row) && !row.syllables.isEmpty() && row.startMs >= previousEnd;
            long last = row.startMs;
            for (int j = 0; j < row.syllables.size(); j++) {
                SyllableSegment span = row.syllables.get(j);
                eligible &= span.startMs >= last && normalize(span.text).chars.length > 0;
                last = span.endMs;
                int ref = refs.size();
                refs.add(new Ref(i, j));
                append(parts, partOwners, span.text + (span.boundaryAfter ? " " : ""), ref);
            }
            eligible &= Arrays.equals(normalize(parts, partOwners).chars, normalize(row.text).chars);
            if (eligible) {
                raw.addAll(parts); owners.addAll(partOwners);
                raw.add((int) ' '); owners.add(-1);
                previousEnd = last;
            } else { raw.add(BARRIER); owners.add(-1); }
        }
        return new Stream(normalize(raw, owners), refs);
    }

    private static final class Match {
        final int row;
        String reason = "";
        int start, end;
        long donorStart, anchorStart;
        List<Ref> refs = new ArrayList<>();
        List<SyllableSegment> spans = new ArrayList<>();
        boolean upgraded;
        Transform transform;
        Match(int row) { this.row = row; }
    }

    private static boolean lowInformation(int[] text) {
        int count = 0;
        Set<Integer> unique = new HashSet<>();
        Set<String> words = new HashSet<>();
        StringBuilder word = new StringBuilder();
        int wordCount = 0;
        for (int cp : text) {
            if (cp != ' ') { count++; unique.add(cp); word.appendCodePoint(cp); }
            else { words.add(word.toString()); word.setLength(0); wordCount++; }
        }
        if (word.length() > 0) { words.add(word.toString()); wordCount++; }
        return count < 6 || unique.size() < 3 || (wordCount > 1 && words.size() == 1);
    }

    private static boolean sameAt(int[] stream, int[] needle, int start) {
        for (int i = 0; i < needle.length; i++) if (stream[start + i] != needle[i]) return false;
        return true;
    }

    private static List<Match> matchRows(LyricsDocument anchor, LyricsDocument donor, Stream stream) {
        List<Match> matches = new ArrayList<>();
        int[] text = stream.text.chars;
        for (int rowIndex = 0; rowIndex < anchor.lines.size(); rowIndex++) {
            LyricsLine row = anchor.lines.get(rowIndex);
            Match match = new Match(rowIndex);
            matches.add(match);
            int[] needle = normalize(row.text).chars;
            if (!supported(row)) match.reason = "unsupported_role_or_interlude";
            else if (!row.syllables.isEmpty()) match.reason = "anchor_has_authored_timing";
            else if (lowInformation(needle)) match.reason = "low_information";
            if (!match.reason.isEmpty()) continue;
            int hits = 0;
            for (int pos = 0; pos + needle.length <= text.length; pos++) {
                if (!sameAt(text, needle, pos)) continue;
                int end = pos + needle.length;
                int first = stream.text.owners[pos], last = stream.text.owners[end - 1];
                if (first < 0 || last < 0 || stream.bounds[first][0] != pos
                        || stream.bounds[last][1] != end - 1) continue;
                if (pos > 0 && text[pos - 1] != ' ' && text[pos - 1] != BARRIER && !eastAsian(text[pos])) continue;
                if (end < text.length && text[end] != ' ' && text[end] != BARRIER && !eastAsian(text[end - 1])) continue;
                hits++; match.start = pos; match.end = end;
            }
            if (hits != 1) { match.reason = hits == 0 ? "no_exact_match" : "ambiguous_match"; continue; }
            LinkedHashSet<Integer> ids = new LinkedHashSet<>();
            for (int i = match.start; i < match.end; i++) if (stream.text.owners[i] >= 0) ids.add(stream.text.owners[i]);
            for (int ref : ids) match.refs.add(stream.refs.get(ref));
            if (match.refs.size() < 2) {
                match.reason = "donor_has_no_finer_timing"; continue;
            }
            if (match.refs.stream().anyMatch(ref -> donor.lines.get(ref.row).oppositeAligned != row.oppositeAligned)) {
                match.reason = "unsupported_role_or_interlude"; continue;
            }
            Ref first = match.refs.get(0);
            match.donorStart = donor.lines.get(first.row).syllables.get(first.segment).startMs;
            match.anchorStart = row.startMs;
        }
        for (Match match : matches) {
            if (!match.reason.isEmpty()) continue;
            long repeats = matches.stream().filter(other -> other.reason.isEmpty()
                    && other.start == match.start && other.end == match.end).count();
            if (repeats > 1) {
                for (Match other : matches) if (other.start == match.start && other.end == match.end
                        && other.reason.isEmpty()) other.reason = "ambiguous_match";
            }
        }
        return matches;
    }

    private static List<List<Match>> islands(List<Match> matches, Stream stream) {
        List<List<Match>> result = new ArrayList<>();
        List<Match> island = new ArrayList<>();
        for (Match match : matches) {
            if (!match.reason.isEmpty()) {
                if (!island.isEmpty()) { result.add(island); island = new ArrayList<>(); }
                continue;
            }
            if (!island.isEmpty()) {
                Match prior = island.get(island.size() - 1);
                long dx = match.donorStart - prior.donorStart, dy = match.anchorStart - prior.anchorStart;
                boolean plausible = dx > 0 && Math.abs(dy - dx) <= 2 * UNCERTAINTY_MS;
                boolean gap = match.start < prior.end;
                for (int i = prior.end; i < match.start; i++) gap |= stream.text.chars[i] != ' ';
                if (gap || !plausible) { result.add(island); island = new ArrayList<>(); }
            }
            island.add(match);
        }
        if (!island.isEmpty()) result.add(island);
        return result;
    }

    private static final class Transform {
        final String kind;
        final double slope, shift;
        Transform(String kind, double slope, double shift) {
            this.kind = kind; this.slope = slope; this.shift = shift;
        }
    }

    private static Transform fit(List<Match> island) {
        if (island.size() < 2) return null;
        // Never start a donor word before its authoritative anchor row to average onset noise.
        double shift = island.stream().mapToLong(m -> m.anchorStart - m.donorStart).max().orElse(0);
        final double offset = shift;
        if (island.stream().allMatch(m -> Math.abs(m.anchorStart - m.donorStart - offset) <= UNCERTAINTY_MS))
            return new Transform("offset", 1, shift);
        return null;
    }

    private static void prepare(LyricsDocument output, LyricsDocument donor, Stream stream,
                                Match match, Transform transform, long targetDurationMs) {
        LyricsLine row = output.lines.get(match.row);
        match.spans = map(row, donor, stream, match, transform);
        if (!match.reason.isEmpty()) return;
        long activeEnd = Math.min(targetDurationMs, row.endMs);
        for (int i = match.row + 1; i < output.lines.size(); i++) {
            if (!output.lines.get(i).interlude) {
                activeEnd = Math.min(activeEnd, output.lines.get(i).startMs);
                break;
            }
        }
        long previousEnd = -1;
        for (SyllableSegment span : match.spans) {
            if (span.startMs < row.startMs || span.startMs >= span.endMs
                    || span.endMs > activeEnd || span.startMs < previousEnd) {
                match.reason = "mapped_interval_out_of_bounds";
                return;
            }
            previousEnd = span.endMs;
        }
    }

    private static List<SyllableSegment> map(LyricsLine row, LyricsDocument donor, Stream stream,
                                              Match match, Transform transform) {
        List<SyllableSegment> spans = new ArrayList<>();
        Normalized anchor = normalize(row.text);
        for (Ref ref : match.refs) {
            SyllableSegment original = donor.lines.get(ref.row).syllables.get(ref.segment);
            int min = Integer.MAX_VALUE, max = -1;
            for (int i = match.start; i < match.end; i++) {
                int owner = stream.text.owners[i];
                if (owner < 0 || stream.text.chars[i] == ' ' || stream.text.chars[i] == BARRIER) continue;
                Ref actual = stream.refs.get(owner);
                if (actual != ref) continue;
                min = Math.min(min, i - match.start); max = Math.max(max, i - match.start);
            }
            if (max < 0 || max >= anchor.owners.length) { match.reason = "unrepresentable_text_boundary"; return spans; }
            SyllableSegment mapped = SyllableSegment.copyOf(original);
            mapped.startMs = original.startMs + (long) transform.shift;
            mapped.endMs = original.endMs + (long) transform.shift;
            mapped.totalMs = mapped.endMs - mapped.startMs;
            mapped.canonicalStartCp = anchor.owners[min];
            mapped.canonicalEndCp = anchor.owners[max] + 1;
            mapped.sourceText = original.text;
            mapped.romanizedText = "";
            mapped.boundaryProvenance = "sync_upgrade_v1:" + ref.row + ":" + ref.segment;
            mapped.spanId = "derived:" + match.row + ":" + ref.row + ":" + ref.segment;
            spans.add(mapped);
        }
        for (int i = 1; i < spans.size(); i++) if (spans.get(i - 1).canonicalEndCp > spans.get(i).canonicalStartCp) {
            match.reason = "unrepresentable_text_boundary"; return spans;
        }
        int cpCount = row.text.codePointCount(0, row.text.length());
        for (int i = 0; i < spans.size(); i++) {
            SyllableSegment span = spans.get(i);
            if (i == 0) span.canonicalStartCp = 0;
            span.canonicalEndCp = i + 1 < spans.size() ? spans.get(i + 1).canonicalStartCp : cpCount;
            span.text = row.text.substring(row.text.offsetByCodePoints(0, span.canonicalStartCp),
                    row.text.offsetByCodePoints(0, span.canonicalEndCp));
        }
        return spans;
    }

    private static String documentDigest(LyricsDocument document) {
        if (document == null) return "";
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            addDigest(hash, document.trackId); addDigest(hash, document.fetchSource);
            addDigest(hash, document.provider);
            addDigest(hash, Long.toString(document.durationMs));
            addDigest(hash, document.type);
            if (document.catalogDelivery != null) {
                addDigest(hash, String.valueOf(document.catalogDelivery.source));
                addDigest(hash, document.catalogDelivery.providerItemId);
                addDigest(hash, String.valueOf(document.catalogDelivery.matchMethod));
            }
            addDigest(hash, document.spicyOrgSource);
            addDigest(hash, document.spicyOrgRawPayload);
            addDigest(hash, Long.toString(document.spicyOrgFetchedAtMs));
            for (LyricsLine row : document.lines) {
                if (row == null) { addDigest(hash, "null"); continue; }
                addDigest(hash, row.text); addDigest(hash, Long.toString(row.startMs));
                addDigest(hash, Long.toString(row.endMs));
                addDigest(hash, Boolean.toString(row.interlude)); addDigest(hash, Boolean.toString(row.oppositeAligned));
                addDigest(hash, row.backgroundLines == null ? "null-backgrounds" : Integer.toString(row.backgroundLines.size()));
                if (row.syllables == null) { addDigest(hash, "null-spans"); continue; }
                for (SyllableSegment span : row.syllables) {
                    if (span == null) { addDigest(hash, "null"); continue; }
                    addDigest(hash, span.text); addDigest(hash, Long.toString(span.startMs));
                    addDigest(hash, Long.toString(span.endMs)); addDigest(hash, Boolean.toString(span.boundaryAfter));
                    addDigest(hash, Boolean.toString(span.bgWord)); addDigest(hash, Boolean.toString(span.dot));
                }
            }
            StringBuilder result = new StringBuilder();
            for (byte b : hash.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void addDigest(MessageDigest hash, String value) {
        byte[] bytes = safe(value).getBytes(StandardCharsets.UTF_8);
        hash.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        hash.update((byte) ':'); hash.update(bytes);
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
