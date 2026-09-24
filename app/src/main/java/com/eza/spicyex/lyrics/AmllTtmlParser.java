package com.eza.spicyex.lyrics;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import static com.eza.spicyex.lyrics.LyricUtils.cleanInvisibles;
import static com.eza.spicyex.lyrics.LyricUtils.isBlank;
import static com.eza.spicyex.lyrics.LyricUtils.safe;

/**
 * AMLL TTML DB adapter: word-timed {@code <p>/<span>} documents become {@link LyricsDocument}s
 * with {@code Word} timing and per-word {@link SyllableSegment}s. Translation-role spans
 * ({@code ttm:role="x-translation"}) are excluded from lyric text and kept as provider
 * translations instead.
 *
 * <p>{@code javax.xml} ships on Android and the JVM alike, so this stays unit-testable with no
 * new dependency. Background-vocal agents ride as main lines in v1; splitting them into
 * {@code backgroundLines} is a follow-up, not a correctness gate, since timing and text are
 * preserved either way.
 */
public final class AmllTtmlParser {
    private static final Pattern CLOCK = Pattern.compile(
            "(?:(\\d+):)?(\\d{1,3}):(\\d{2}(?:\\.\\d{1,3})?)|(\\d+(?:\\.\\d{1,3})?)s?");

    private AmllTtmlParser() {}

    /** Parses one AMLL TTML document; throws on malformed XML or lyric-less content. */
    public static LyricsDocument parse(String ttml, String trackId, long durationMs) throws Exception {
        if (isBlank(ttml)) throw new IllegalStateException("empty TTML");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setExpandEntityReferences(false);
        // Local fixed-schema community payloads: no external entities or DTD fetching.
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // Parser without the feature still honors the narrow element walk below.
        }
        Element root = factory.newDocumentBuilder()
                .parse(new InputSource(new StringReader(ttml))).getDocumentElement();
        if (!"tt".equals(root.getTagName())) throw new IllegalStateException("not TTML");

        LyricsDocument doc = new LyricsDocument();
        doc.trackId = safe(trackId);
        doc.durationMs = Math.max(0, durationMs);
        doc.fetchSource = "amll_ttml";
        doc.provider = "AMLL";
        doc.language = root.getAttribute("xml:lang");

        NodeList paragraphs = root.getElementsByTagName("p");
        boolean anyTimedWords = false;
        for (int i = 0; i < paragraphs.getLength(); i++) {
            LyricsLine line = parseParagraph((Element) paragraphs.item(i), i);
            if (line == null) continue;
            if (!line.syllables.isEmpty()) anyTimedWords = true;
            doc.lines.add(line);
        }
        if (doc.lines.isEmpty()) throw new IllegalStateException("TTML has no lyric lines");
        if (doc.startTimeMs <= 0) doc.startTimeMs = doc.lines.get(0).startMs;
        doc.type = anyTimedWords ? "Word" : doc.lines.get(0).startMs > 0 || doc.lines.get(0).endMs > 0
                ? "Line" : "Static";
        return doc;
    }

    private static LyricsLine parseParagraph(Element p, int index) {
        long startMs = parseTime(p.getAttribute("begin"), -1);
        long endMs = parseTime(p.getAttribute("end"), -1);
        StringBuilder text = new StringBuilder();
        StringBuilder translation = new StringBuilder();
        String translationLang = "";
        List<SyllableSegment> segments = new ArrayList<>();
        NodeList children = p.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                text.append(child.getNodeValue());
            } else if (child.getNodeType() == Node.ELEMENT_NODE
                    && "span".equals(((Element) child).getTagName())) {
                Element span = (Element) child;
                String role = span.getAttribute("ttm:role");
                if ("x-translation".equals(role)) {
                    if (translation.length() > 0) translation.append('\n');
                    translation.append(span.getTextContent());
                    if (translationLang.isEmpty()) translationLang = span.getAttribute("xml:lang");
                    continue;
                }
                String word = span.getTextContent();
                if (span.hasAttribute("begin") && !isBlank(word)) {
                    SyllableSegment seg = new SyllableSegment();
                    seg.spanId = String.valueOf(segments.size());
                    seg.text = cleanInvisibles(word).trim();
                    seg.sourceText = word;
                    seg.startMs = parseTime(span.getAttribute("begin"), startMs);
                    seg.endMs = parseTime(span.getAttribute("end"), endMs);
                    if (seg.endMs <= seg.startMs) seg.endMs = seg.startMs + 1;
                    seg.totalMs = Math.max(0, seg.endMs - seg.startMs);
                    seg.canonicalStartCp = text.length();
                    seg.boundaryAfter = true;
                    text.append(word);
                    seg.canonicalEndCp = text.length();
                    segments.add(seg);
                } else {
                    text.append(word);
                }
            }
        }
        String lineText = cleanInvisibles(text.toString()).trim();
        if (lineText.isEmpty()) return null;
        LyricsLine line = new LyricsLine();
        line.text = lineText;
        line.startMs = Math.max(0, startMs);
        line.endMs = Math.max(line.startMs, endMs);
        line.syllables = segments;
        line.providerTranslatedText = cleanInvisibles(translation.toString()).trim();
        line.providerTranslationLanguage = translationLang == null ? "" : translationLang;
        return line;
    }

    /** TTML clock values ({@code mm:ss.mmm}, {@code ss.mmm}, {@code 12.5s}); fallback on garbage. */
    static long parseTime(String value, long fallback) {
        if (isBlank(value)) return fallback;
        Matcher m = CLOCK.matcher(value.trim());
        if (!m.matches()) return fallback;
        try {
            if (m.group(4) != null) return Math.round(Double.parseDouble(m.group(4)) * 1000);
            long hours = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
            long minutes = Long.parseLong(m.group(2));
            double seconds = Double.parseDouble(m.group(3));
            return hours * 3600000 + minutes * 60000 + Math.round(seconds * 1000);
        } catch (NumberFormatException bad) {
            return fallback;
        }
    }
}
