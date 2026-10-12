package com.eza.spicyex.lyrics.language;

import org.junit.Test;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ChineseCharacterProjectionTest {
    @Test public void bundledDictionariesPreserveReadingCodePointCoordinates() throws Exception {
        String[] dictionaries = {"CJK_Compatibility_Ideographs", "STCharacters", "STPhrases",
                "STPhrases_GeneratedFromRegionalPhrases", "TWVariants", "TWVariantsPhrases",
                "TSCharacters", "TSPhrases"};
        for (String name : dictionaries) {
            try (InputStream stream = ChineseCharacterConverter.class.getResourceAsStream("/opencc/" + name + ".txt")) {
                assertNotNull("Bundled dictionary is available without a language model pack: " + name, stream);
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                for (String line; (line = reader.readLine()) != null;) {
                    if (line.isEmpty() || line.charAt(0) == '#') continue;
                    String[] pair = line.split("\t", -1);
                    assertEquals(name, 2, pair.length);
                    assertEquals("Reading range projection must change before a length-changing bundle: " + name,
                            pair[0].codePointCount(0, pair[0].length()),
                            pair[1].codePointCount(0, pair[1].length()));
                }
            }
        }
    }

    private static ChineseCharacterConverter.Trie dictionary(String entries) throws Exception {
        ChineseCharacterConverter.TrieBuilder builder = new ChineseCharacterConverter.TrieBuilder();
        builder.read(new StringReader(entries), false);
        return builder.build();
    }

    @Test public void lengthChangesUseMonotonicCodePointBoundariesAcrossStages() throws Exception {
        ChineseCharacterConverter.Projection projected = ChineseCharacterConverter.Projection.identity("🙂甲乙Z")
                .through(dictionary("甲乙\t𠀾汉语\n"))
                .through(dictionary("𠀾\t𠁞\n汉语\t漢語\n"));
        assertEquals("🙂𠁞漢語Z", projected.text);
        assertEquals(0, projected.mapBoundary(0));
        assertEquals(1, projected.mapBoundary(1));
        assertEquals(2, projected.mapBoundary(2));
        assertEquals(4, projected.mapBoundary(3));
        assertEquals(5, projected.mapBoundary(4));
        assertEquals("𠁞", projected.slice(1, 2));
        assertEquals("漢語", projected.slice(2, 3));
        assertEquals(projected.text, projected.slice(0, 2) + projected.slice(2, 4));
    }

    @Test public void contractionKeepsAdjacentSpanOwnershipAndFinalBoundary() throws Exception {
        ChineseCharacterConverter.Projection projected = ChineseCharacterConverter.Projection.identity("甲乙丙🙂")
                .through(dictionary("甲乙丙\t𠀾汉\n"));
        assertEquals("𠀾汉🙂", projected.text);
        assertEquals("", projected.slice(0, 1));
        assertEquals("𠀾", projected.slice(1, 2));
        assertEquals("汉", projected.slice(2, 3));
        assertEquals("🙂", projected.slice(3, 4));
        assertEquals(3, projected.mapBoundary(4));
    }

    @Test public void longestMatchAndDictionaryPriorityAreIndependent() throws Exception {
        ChineseCharacterConverter.TrieBuilder builder = new ChineseCharacterConverter.TrieBuilder();
        builder.read(new StringReader("发\t發\n头发\t頭發\n"), false);
        builder.read(new StringReader("头发\t頭髮\n"), false);
        ChineseCharacterConverter.Projection projected = ChineseCharacterConverter.Projection.identity("头发发")
                .through(builder.build());
        assertEquals("頭髮發", projected.text);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void rejectsInvalidCanonicalRange() {
        ChineseCharacterConverter.project("汉语", ChineseCharacterForm.TRADITIONAL).slice(0, 3);
    }
}
