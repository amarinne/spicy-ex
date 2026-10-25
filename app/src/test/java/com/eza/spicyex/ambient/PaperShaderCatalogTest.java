package com.eza.spicyex.ambient;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Contracts for the generated Paper shader catalog and its pinned provenance. */
public class PaperShaderCatalogTest {
    /** Upstream {@code PaperShaders.catalog} showcase order at the pinned revision. */
    private static final String[] EXPECTED_IDS = {
            "simplex-noise",
            "mesh-gradient",
            "dot-orbit",
            "god-rays",
            "voronoi",
            "waves",
            "spiral",
            "swirl",
            "neuro-noise",
            "static-mesh-gradient",
            "dot-grid",
            "static-radial-gradient",
            "perlin-noise",
            "color-panels",
            "dithering",
            "image-dithering",
            "halftone-dots",
            "halftone-cmyk",
            "paper-texture",
            "water",
            "fluted-glass",
            "heatmap",
            "gem-smoke",
            "liquid-metal",
            "smoke-ring",
            "warp",
            "metaballs",
            "pulsing-border",
            "grain-gradient",
    };

    /** Bound by the renderer for every frame instead of stored as a default. */
    private static final Set<String> DYNAMIC_UNIFORMS = new HashSet<>(Arrays.asList(
            "u_resolution",
            "u_time",
            "u_pixelRatio",
            "u_imageAspectRatio",
            "u_imageSize",
            "u_noiseTextureSize",
            "ambient_dark",
            "ambient_brightness"));

    private static final Pattern FLOAT_UNIFORM = Pattern.compile(
            "\\buniform\\s+(float|vec2|vec3|vec4)\\s+([^;]+);");
    private static final Pattern SHADER_UNIFORM = Pattern.compile(
            "\\buniform\\s+shader\\s+([^;]+);");
    private static final Pattern ENTRY_POINT = Pattern.compile(
            "vec4\\s+paperMain\\s*\\(vec2\\s+fragCoord\\)");

    @Test
    public void catalogHasEveryPinnedShaderInUpstreamOrder() {
        List<PaperShaderCatalog.Spec> specs = PaperShaderCatalog.all();
        assertEquals(EXPECTED_IDS.length, specs.size());
        for (int i = 0; i < EXPECTED_IDS.length; i++) {
            assertEquals(EXPECTED_IDS[i], specs.get(i).id);
        }
    }

    @Test
    public void lookupNormalisesNamesAndRejectsUnknownOnes() {
        assertNotNull(PaperShaderCatalog.get(" mesh-gradient "));
        assertNotNull(PaperShaderCatalog.get("MESH-GRADIENT"));
        assertNull(PaperShaderCatalog.get("mesh"));
        assertNull(PaperShaderCatalog.get(null));
        assertNull(PaperShaderCatalog.get(""));
        assertNull(PaperShaderCatalog.get("   "));
        assertNull(PaperShaderCatalog.get("compose"));
    }

    @Test
    public void everySourceIsThePackagedAgslAndHasOneWrappedEntryPoint() {
        for (PaperShaderCatalog.Spec spec : PaperShaderCatalog.all()) {
            assertFalse(spec.id, spec.source.isEmpty());
            assertEquals(spec.id, readText("paper-shaders/" + spec.id + ".agsl"), spec.source);
            assertTrue(spec.id, ENTRY_POINT.matcher(spec.source).find());
            assertEquals(spec.id, 1, count(spec.source, "vec4 main(vec2 fragCoord)"));
            assertEquals(spec.id, 2, count(spec.source, "paperMain("));
        }
    }

    @Test
    public void everyCatalogSourceMatchesItsRecordedHash() {
        List<String> hashes = readLines("paper-shaders/source-hashes.tsv");
        assertEquals(EXPECTED_IDS.length, hashes.size());
        for (int i = 0; i < hashes.size(); i++) {
            String[] columns = hashes.get(i).split("\t", -1);
            assertEquals(4, columns.length);
            assertEquals(EXPECTED_IDS[i], columns[0]);
            PaperShaderCatalog.Spec spec = PaperShaderCatalog.all().get(i);
            assertEquals(columns[0], columns[3], sha256(spec.source.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    public void defaultsCoverEveryDeclaredUniformExactlyOnce() {
        for (PaperShaderCatalog.Spec spec : PaperShaderCatalog.all()) {
            Map<String, Decl> declared = declaredUniforms(spec.source);
            Set<String> expected = new HashSet<>(declared.keySet());
            expected.removeAll(DYNAMIC_UNIFORMS);
            assertEquals(spec.id, expected, spec.uniforms.keySet());

            for (Map.Entry<String, Decl> entry : declared.entrySet()) {
                String name = entry.getKey();
                Decl decl = entry.getValue();
                if (DYNAMIC_UNIFORMS.contains(name)) {
                    assertFalse(spec.id + "." + name, spec.uniforms.containsKey(name));
                    continue;
                }
                float[] values = spec.uniforms.get(name);
                assertNotNull(spec.id + "." + name, values);
                Integer capacity = spec.colorCapacities.get(name);
                if (capacity == null) {
                    assertEquals(spec.id + "." + name, 0, decl.arrayLength);
                    assertEquals(spec.id + "." + name, 1, values.length);
                } else {
                    assertEquals(spec.id + "." + name, "vec4", decl.type);
                    int declaredCapacity = decl.arrayLength == 0 ? 1 : decl.arrayLength;
                    assertEquals(spec.id + "." + name, capacity.intValue(), declaredCapacity);
                    assertEquals(spec.id + "." + name, capacity.intValue() * 4, values.length);
                }
            }
            assertTrue(spec.id, declared.keySet().containsAll(spec.colorCapacities.keySet()));
        }
    }

    @Test
    public void colorCapacitiesStayWithinPapersPackedLimit() {
        for (PaperShaderCatalog.Spec spec : PaperShaderCatalog.all()) {
            assertFalse(spec.id, spec.colorCapacities.isEmpty());
            for (Map.Entry<String, Integer> entry : spec.colorCapacities.entrySet()) {
                assertTrue(spec.id, entry.getKey().startsWith("u_"));
                assertTrue(spec.id, entry.getValue() >= 1);
                assertTrue(spec.id, entry.getValue() <= PaperShaderCatalog.MAX_COLORS);
            }
        }
    }

    @Test
    public void textureFlagsMatchTheDeclaredShaderInputs() {
        for (PaperShaderCatalog.Spec spec : PaperShaderCatalog.all()) {
            assertEquals(spec.id, spec.usesImageTexture, declaresShaderInput(spec.source, "u_image"));
            assertEquals(spec.id, spec.usesNoiseTexture,
                    declaresShaderInput(spec.source, "u_noiseTexture"));
        }
        assertTrue(PaperShaderCatalog.hasNoiseTexture());
    }

    @Test
    public void staticShadersDoNotAdvanceAndAnimatedOnesDo() {
        for (PaperShaderCatalog.Spec spec : PaperShaderCatalog.all()) {
            assertEquals(spec.id, spec.animated, spec.speed > 0f);
            assertFalse(spec.id, Float.isNaN(spec.speed) || Float.isInfinite(spec.speed));
        }
    }

    @Test
    public void noiseAssetIsThePinnedPng() {
        byte[] png = readBytes("paper-shaders/ps_noise.png");
        assertTrue(png.length > 8);
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 'P', png[1]);
        assertEquals((byte) 'N', png[2]);
        assertEquals((byte) 'G', png[3]);
        assertEquals("5116a06c428a75e2db9bd55062c560bb02600383ee54da007f1628e845b2b73a",
                sha256(png));
    }

    @Test
    public void provenancePinsTheRevisionLicenseAndEverySource() {
        String provenance = readText("paper-shaders/provenance.json");
        assertTrue(provenance.contains("\"repository\": "
                + "\"https://github.com/AndreFrelicot/paper-shaders-android\""));
        assertTrue(provenance.contains("\"revision\": "
                + "\"fc2d75d4dad54b63bbb07df16d8a2d9a2cc467f3\""));
        assertTrue(provenance.contains("\"tag\": \"0.0.3\""));
        assertTrue(provenance.contains("\"license\": \"Apache-2.0\""));
        assertEquals(EXPECTED_IDS.length, count(provenance, "\"id\": \""));
        assertTrue(readText("paper-shaders/LICENSE.txt").contains("Apache License"));
        assertTrue(readText("paper-shaders/NOTICE.txt").contains("Paper Shaders"));
    }

    private static boolean declaresShaderInput(String source, String name) {
        Matcher matcher = SHADER_UNIFORM.matcher(source);
        while (matcher.find()) {
            for (String token : matcher.group(1).split(",")) {
                if (token.trim().equals(name)) return true;
            }
        }
        return false;
    }

    private static Map<String, Decl> declaredUniforms(String source) {
        Map<String, Decl> result = new LinkedHashMap<>();
        Matcher matcher = FLOAT_UNIFORM.matcher(source);
        while (matcher.find()) {
            String type = matcher.group(1);
            for (String token : matcher.group(2).split(",")) {
                String declaration = token.trim();
                int bracket = declaration.indexOf('[');
                String name = bracket < 0 ? declaration : declaration.substring(0, bracket).trim();
                int arrayLength = 0;
                if (bracket >= 0) {
                    int close = declaration.indexOf(']', bracket);
                    arrayLength = Integer.parseInt(declaration.substring(bracket + 1, close).trim());
                }
                if (result.put(name, new Decl(type, arrayLength)) != null) {
                    throw new AssertionError("Duplicate declaration " + name);
                }
            }
        }
        return result;
    }

    private static final class Decl {
        final String type;
        final int arrayLength;

        Decl(String type, int arrayLength) {
            this.type = type;
            this.arrayLength = arrayLength;
        }
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    private static String readText(String resource) {
        return new String(readBytes(resource), StandardCharsets.UTF_8);
    }

    private static List<String> readLines(String resource) {
        List<String> lines = new ArrayList<>();
        for (String line : readText(resource).split("\n", -1)) {
            if (!line.trim().isEmpty()) lines.add(line);
        }
        return lines;
    }

    private static byte[] readBytes(String resource) {
        try (InputStream input = PaperShaderCatalogTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull("Missing resource " + resource, input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new AssertionError("Cannot read " + resource, e);
        }
    }

    private static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xf, 16));
                hex.append(Character.forDigit(value & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
