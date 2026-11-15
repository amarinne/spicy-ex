package com.eza.spicyex.lyrics.providers;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.Test;
import static org.junit.Assert.*;

public class F5XmlSafetyTest {
    private static final String VALID = "<tt><body><div><p begin=\"1s\" end=\"2s\">safe &amp; &#x4f60;</p></div></body></tt>";

    @Test public void unsupportedFeatureStillParsesValidTtmlWithIndependentDefenses() throws Exception {
        UnsupportedFactory factory = new UnsupportedFactory();
        assertEquals("safe & 你", AmllTtmlParser.parse(VALID, "track", 3000, factory).lines.get(0).text);
        assertEquals(1, factory.builders);
        assertTrue(factory.featureAttempted);
    }

    @Test public void unsupportedFeatureNeverPassesDtdToABuilder() throws Exception {
        for (String dtd : new String[]{"<!DOCTYPE tt>",
                "<!DOCTYPE tt [<!ENTITY word 'expanded'>]>",
                "<!DOCTYPE tt SYSTEM ''>",
                "<!DOCTYPE tt [<!ENTITY word SYSTEM ''>]>",
                "<!DOCTYPE tt [<!ENTITY % external SYSTEM ''>%external;]>"}) {
            UnsupportedFactory factory = new UnsupportedFactory();
            factory.forbidBuilder = true;
            try {
                AmllTtmlParser.parse(dtd + VALID, "track", 3000, factory);
                fail("DTD reached an insecure parser");
            } catch (org.xml.sax.SAXException expected) {
                assertEquals(0, factory.builders);
            }
        }
    }

    @Test public void rejectsAllDtdFormsWithoutFileOrNetworkAccess() throws Exception {
        // Empty identifiers are forbidden inputs, never real files or network destinations.
        for (String declaration : new String[]{
                "<!DOCTYPE tt>",
                "<!DOCTYPE tt [<!ENTITY word 'expanded'>]>",
                "<!DOCTYPE tt SYSTEM ''>",
                "<!DOCTYPE tt PUBLIC 'forbidden' ''>",
                "<!DOCTYPE tt [<!ENTITY word SYSTEM ''>]>",
                "<!DOCTYPE tt [<!ENTITY % external SYSTEM ''>%external;]>"}) {
            try {
                AmllTtmlParser.parse(declaration + VALID, "track", 3000);
                fail("DTD accepted: " + declaration);
            } catch (org.xml.sax.SAXException expected) { }
        }
    }

    @Test public void validEntitiesAndCdataKeepTheirText() throws Exception {
        assertEquals("safe & 你", AmllTtmlParser.parse(VALID, "track", 3000).lines.get(0).text);
        assertEquals("safe & 你", AmllTtmlParser.parse(
                "<?xml version=\"1.0\"?><!-- <!DOCTYPE tt> --><?note <!DOCTYPE tt>?>" + VALID, "t", 0).lines.get(0).text);
        assertEquals("<!DOCTYPE is lyric text", AmllTtmlParser.parse(
                "<tt><body><p><span><![CDATA[<!DOCTYPE is lyric text]]></span></p></body></tt>", "t", 0).lines.get(0).text);
    }

    @Test public void malformedXmlStillFails() throws Exception {
        try {
            AmllTtmlParser.parse("<tt><body><p>broken</tt>", "t", 0);
            fail("malformed XML accepted");
        } catch (org.xml.sax.SAXException expected) { }
    }

    private static final class UnsupportedFactory extends DocumentBuilderFactory {
        int builders;
        boolean featureAttempted;
        boolean forbidBuilder;
        public DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
            builders++;
            if (forbidBuilder) throw new AssertionError("DTD reached builder creation");
            return DocumentBuilderFactory.newInstance().newDocumentBuilder();
        }
        public void setAttribute(String name, Object value) { }
        public Object getAttribute(String name) { return null; }
        public void setFeature(String name, boolean value) throws ParserConfigurationException {
            featureAttempted = true;
            throw new ParserConfigurationException("unsupported in test");
        }
        public boolean getFeature(String name) { return false; }
    }
}
