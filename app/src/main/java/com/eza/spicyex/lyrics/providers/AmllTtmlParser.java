package com.eza.spicyex.lyrics.providers;

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

import com.eza.spicyex.lyrics.LyricsDocument;
import com.eza.spicyex.lyrics.LyricsLine;
import com.eza.spicyex.lyrics.SyllableSegment;

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
        return parse(ttml, trackId, durationMs, DocumentBuilderFactory.newInstance());
    }

    /** Local files reject unsupported timing instead of silently changing authored timestamps. */
    public static LyricsDocument parseLocal(String ttml, String trackId, long durationMs) throws Exception {
        return parse(ttml, trackId, durationMs, DocumentBuilderFactory.newInstance(), true);
    }

    static LyricsDocument parse(String ttml, String trackId, long durationMs,
                                DocumentBuilderFactory factory) throws Exception {
        return parse(ttml, trackId, durationMs, factory, false);
    }

    private static LyricsDocument parse(String ttml, String trackId, long durationMs,
                                        DocumentBuilderFactory factory, boolean local) throws Exception {
        if (isBlank(ttml)) throw new IllegalStateException("empty TTML");
        rejectDeclarations(ttml);
        factory.setValidating(false);
        factory.setNamespaceAware(false);
        factory.setExpandEntityReferences(false);
        // Android XML factories do not all expose Xerces features. The lexical guard above
        // rejects DTDs before builder creation on every factory; the resolver below independently
        // refuses external resolution. Feature support is an extra defense, not the fallback.
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (javax.xml.parsers.ParserConfigurationException unsupported) {
            // Safe without this feature: no document declaration can reach the parser.
        }
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> {
            throw new org.xml.sax.SAXException("External XML entities are forbidden");
        });
        Element root = builder.parse(new InputSource(new StringReader(ttml))).getDocumentElement();
        if (!"tt".equals(root.getTagName())) throw new IllegalStateException("not TTML");
        if (local) validateLocal(root);

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

    /** Iterative traversal bounds nesting before getTextContent can recurse through a file. */
    private static void validateLocal(Element root) {
        Node node = root;
        int depth = 0;
        int elements = 0;
        while (node != null) {
            if (node instanceof Element) {
                if (++elements > 100000) throw new IllegalArgumentException("TTML has too many elements");
                Element element = (Element) node;
                for (String attribute : new String[]{"begin", "end"}) {
                    if (element.hasAttribute(attribute)
                            && parseTime(element.getAttribute(attribute), -1) < 0) {
                        throw new IllegalArgumentException("Unsupported TTML timing");
                    }
                }
                if (!"p".equals(element.getTagName()) && !"span".equals(element.getTagName())
                        && (parseTime(element.getAttribute("begin"), 0) > 0
                        || parseTime(element.getAttribute("end"), 0) > 0)) {
                    throw new IllegalArgumentException("Unsupported TTML parent timing");
                }
                if (element.hasAttribute("dur") || element.hasAttribute("timeContainer")
                        && !"par".equals(element.getAttribute("timeContainer"))) {
                    throw new IllegalArgumentException("Unsupported TTML timing mode");
                }
                if (element.hasAttribute("begin") && element.hasAttribute("end")
                        && parseTime(element.getAttribute("end"), -1)
                        <= parseTime(element.getAttribute("begin"), -1)) {
                    throw new IllegalArgumentException("Invalid TTML time range");
                }
            }
            if (node.getFirstChild() != null) {
                if (++depth > 64) throw new IllegalArgumentException("TTML nesting exceeds limit");
                node = node.getFirstChild();
                continue;
            }
            while (node != root && node.getNextSibling() == null) {
                node = node.getParentNode();
                depth--;
            }
            if (node == root) break;
            node = node.getNextSibling();
        }
    }

    /** XML markup declarations are case-sensitive. Only comments, CDATA and processing instructions
     * may hide declaration-like text; all other <! markup (including every DTD) is forbidden. */
    private static void rejectDeclarations(String xml) throws org.xml.sax.SAXException {
        int at = 0;
        while ((at = xml.indexOf('<', at)) >= 0) {
            String end;
            if (xml.startsWith("<!--", at)) end = "-->";
            else if (xml.startsWith("<![CDATA[", at)) end = "]]>";
            else if (xml.startsWith("<?", at)) end = "?>";
            else {
                if (xml.startsWith("<!", at)) {
                    throw new org.xml.sax.SAXException("DTD and markup declarations are forbidden");
                }
                at++;
                continue;
            }
            int close = xml.indexOf(end, at + 2);
            if (close < 0) throw new org.xml.sax.SAXException("Unclosed XML markup");
            at = close + end.length();
        }
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
